package com.pchuri.returnfairy.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibClient
import com.pchuri.returnfairy.data.AccountStore
import com.pchuri.returnfairy.data.SettingsStore
import com.pchuri.returnfairy.data.SnapshotStore
import com.pchuri.returnfairy.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

const val CHANNEL_DUE_REMINDERS = "due_reminders"
private val DEADLINE = java.time.format.DateTimeFormatter.ofPattern("MM.dd")
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
private const val SCHEDULE_VERSION = 2

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
    val now = LocalDateTime.now()
    var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
    if (!next.isAfter(now)) next = next.plusDays(1)
    val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    val policy = if (reschedule) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
}

/** What deserves a nudge today. Kept free of Android types so it can be unit-tested. */
data class DailyDigest(
    val overdue: List<String>,
    val dueToday: List<String>,
    val dueTomorrow: List<String>,
    /** Interlibrary arrivals and arrived reservations: "label · title (library)". */
    val pickups: List<String>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && dueToday.isEmpty() && dueTomorrow.isEmpty() && pickups.isEmpty()
}

fun buildDailyDigest(snapshot: Snapshot, today: LocalDate): DailyDigest {
    val overdue = mutableListOf<String>()
    val dueToday = mutableListOf<String>()
    val dueTomorrow = mutableListOf<String>()
    val pickups = mutableListOf<String>()
    for (account in snapshot.accounts) {
        for (book in account.sortedBooks(today)) {
            when (book.status) {
                BookStatus.READY_FOR_PICKUP -> pickups += "${account.label} · ${book.title} (${book.library})"
                BookStatus.LOANED -> when (val left = book.daysLeft(today)) {
                    null -> Unit
                    else -> when {
                        left < 0 -> overdue += "${account.label} · ${book.title}"
                        left == 0L -> dueToday += "${account.label} · ${book.title}"
                        left == 1L -> dueTomorrow += "${account.label} · ${book.title}"
                    }
                }
                BookStatus.IN_TRANSIT -> Unit
            }
        }
        for (reservation in account.reservations) {
            val deadline = reservation.pickupDeadline ?: continue
            pickups += "${account.label} · ${reservation.title} (${reservation.library}, ~${deadline.format(DEADLINE)})"
        }
    }
    return DailyDigest(overdue, dueToday, dueTomorrow, pickups)
}

class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val accounts = AccountStore(context).load()
        if (accounts.isEmpty()) return Result.success()

        val snapshot = SplibClient().fetchAll(accounts)
        // Offline: keep the old cache and try again tomorrow.
        if (snapshot.isOffline()) return Result.success()
        SnapshotStore(context).save(snapshot)

        val digest = buildDailyDigest(snapshot, LocalDate.now())
        if (digest.isEmpty) return Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

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
        return Result.success()
    }
}
