package com.pchuri.returnfairy.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.core.SplibClient
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.data.AccountStore
import com.pchuri.returnfairy.data.LookupSession
import com.pchuri.returnfairy.data.SettingsStore
import com.pchuri.returnfairy.data.SnapshotStore
import com.pchuri.returnfairy.ui.MainActivity
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val CHANNEL_DUE_REMINDERS = "due_reminders"
private const val WORK_NAME = "due_reminder_daily"
private const val NOTIFICATION_ID = 1001

fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_DUE_REMINDERS,
            context.getString(R.string.channel_due_reminders),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

/** How the work is scheduled. Bump to replace work enqueued by an older version once. */
private const val SCHEDULE_VERSION = 4

/**
 * Makes sure the daily check is scheduled. Existing work is kept, because re-enqueueing a
 * periodic request on every launch would keep the first run drifting away from the chosen hour.
 */
fun scheduleDailyCheck(context: Context, settings: SettingsStore) {
    val replace = settings.scheduleVersion < SCHEDULE_VERSION
    scheduleDailyCheck(context, settings.reminderHour, reschedule = replace)
    if (replace) settings.scheduleVersion = SCHEDULE_VERSION
}

/** Schedules the check at [hour]; [reschedule] replaces existing work (needed when the hour changes). */
fun scheduleDailyCheck(context: Context, hour: Int, reschedule: Boolean) {
    // Hour changes must cancel the old retry chain and give the new schedule a new ID.
    // An initial delay is compatible with replacement; next-run overrides are not.
    val request = dailyCheckEnqueueRequest(DailyCheckWorker::class.java, hour)
    val policy = if (reschedule) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
}

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = finishDailyCheck(
        checkAndNotify(),
        onScheduleFailure = { Log.w("DailyCheck", "Could not reanchor the daily check; keeping periodic fallback", it) },
    ) {
        // Periodic work normally starts its next 24-hour interval at completion. Override
        // that only after the terminal attempt, otherwise it would suppress retry backoff.
        // Update this exact work ID, so a concurrent hour change cannot be overwritten and
        // this running worker is never cancelled/re-enqueued by its own completion.
        val request = dailyCheckRequest(
            DailyCheckWorker::class.java, SettingsStore(applicationContext).reminderHour, id = id,
        )
        runInterruptible(Dispatchers.IO) {
            // Persist the override before returning success to WorkManager.
            WorkManager.getInstance(applicationContext).updateWork(request).get()
        }
    }

    private suspend fun checkAndNotify(): Result {
        val context = applicationContext
        val session = AccountStore(context).beginLookup()
        if (session.accounts.isEmpty()) return Result.success()

        val snapshot = SplibClient().fetchAll(session.accounts)
        return commitDailyLookup(SnapshotStore(context), snapshot, session, LocalDate.now(), runAttemptCount) {
            notifyDigest(context, it)
        }
    }

    private fun notifyDigest(context: Context, digest: DailyDigest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val title = when {
            digest.overdue.isNotEmpty() -> context.getString(R.string.notif_title_overdue, digest.overdue.size)
            digest.dueToday.isNotEmpty() -> context.getString(R.string.notif_title_due_today, digest.dueToday.size)
            digest.dueTomorrow.isNotEmpty() -> context.getString(R.string.notif_title_due_tomorrow, digest.dueTomorrow.size)
            else -> context.getString(R.string.notif_title_pickup, digest.pickups.size)
        }
        val lines = digest.overdue.map { context.getString(R.string.notif_line_overdue, it) } +
            digest.dueToday.map { context.getString(R.string.notif_line_due_today, it) } +
            digest.dueTomorrow.map { context.getString(R.string.notif_line_due_tomorrow, it) } +
            digest.pickups.map { context.getString(R.string.notif_line_pickup, it) }

        ensureNotificationChannel(context)
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DUE_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

/** Keep valid cache data and recheck cancellation before reminder planning and submission.
 * Cancellation can arrive while a synchronous monitor acquisition waits after network I/O.
 * This synchronous callback checks it once the mutation monitor has actually been acquired.
 */
internal suspend fun commitDailyLookup(
    store: SnapshotStore,
    snapshot: Snapshot,
    session: LookupSession,
    today: LocalDate,
    runAttemptCount: Int,
    onNotify: (DailyDigest) -> Unit,
): ListenableWorker.Result {
    val lookupContext = currentCoroutineContext()
    var outcome = ListenableWorker.Result.success()
    store.mergeAndSave(snapshot, session) { _, accepted ->
        lookupContext.ensureActive()
        // Only this attempt's accepted fresh accounts may cause retries or reminders.
        val plan = planDailyCheck(accepted, today, runAttemptCount)
        if (plan.retry) outcome = ListenableWorker.Result.retry()
        else if (!plan.digest.isEmpty) onNotify(plan.digest)
    }
    return outcome
}
