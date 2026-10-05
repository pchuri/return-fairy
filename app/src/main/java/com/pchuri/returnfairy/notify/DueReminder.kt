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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.data.BookRepository
import com.pchuri.returnfairy.data.db.AppDatabase
import com.pchuri.returnfairy.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

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
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

/** (Re)schedule the daily reminder check at the given local hour. */
fun scheduleDailyReminder(context: Context, hour: Int) {
    val now = LocalDateTime.now()
    var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
    if (!next.isAfter(now)) next = next.plusDays(1)
    val initialDelay = Duration.between(now, next)

    val request = PeriodicWorkRequestBuilder<DueReminderWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(initialDelay.toMinutes(), TimeUnit.MINUTES)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

class DueReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = BookRepository(AppDatabase.get(context).bookDao())
        val today = LocalDate.now()
        // dueSoon only returns entries with a due date; pair with it non-null
        val books = repository.dueSoon(today.plusDays(1))
            .mapNotNull { book -> book.dueDate?.let { book to it } }
        if (books.isEmpty()) return Result.success()

        val overdue = books.filter { (_, due) -> due.isBefore(today) }
        val dueToday = books.filter { (_, due) -> due == today }

        val title = when {
            overdue.isNotEmpty() -> context.getString(R.string.notif_title_overdue, overdue.size)
            dueToday.isNotEmpty() -> context.getString(R.string.notif_title_due_today, dueToday.size)
            else -> context.getString(R.string.notif_title_due_tomorrow, books.size)
        }
        val body = books.joinToString("\n") { (book, due) ->
            val label = when {
                due.isBefore(today) -> context.getString(R.string.badge_overdue)
                due == today -> context.getString(R.string.badge_due_today)
                else -> "D-1"
            }
            "$label · ${book.title}"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        ensureNotificationChannel(context)
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DUE_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(books.first().first.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return Result.success()
    }
}
