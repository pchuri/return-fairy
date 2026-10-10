package com.pchuri.returnfairy.notify

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** The next local reminder hour, including days that are not exactly 24 hours long. */
internal fun nextDailyCheckAt(hour: Int, now: ZonedDateTime): Long {
    var date = now.toLocalDate()
    var next = date.atTime(hour, 0).atZone(now.zone)
    if (!next.isAfter(now)) {
        date = date.plusDays(1)
        next = date.atTime(hour, 0).atZone(now.zone)
    }
    return next.toInstant().toEpochMilli()
}

/** New/replacement work uses an initial delay, never an override (REPLACE rejects it). */
internal fun dailyCheckEnqueueRequest(
    workerClass: Class<out ListenableWorker>,
    hour: Int,
    now: ZonedDateTime = ZonedDateTime.now(),
): PeriodicWorkRequest = dailyCheckBuilder(workerClass)
    .setInitialDelay(nextDailyCheckAt(hour, now) - now.toInstant().toEpochMilli(), TimeUnit.MILLISECONDS)
    .build()

private fun dailyCheckBuilder(workerClass: Class<out ListenableWorker>) =
    PeriodicWorkRequest.Builder(workerClass, 1, TimeUnit.DAYS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DAILY_CHECK_BACKOFF_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())

/** Only for updating existing work: enqueueing this with CANCEL_AND_REENQUEUE is invalid. */
internal fun dailyCheckRequest(
    workerClass: Class<out ListenableWorker>,
    hour: Int,
    now: ZonedDateTime = ZonedDateTime.now(),
    id: UUID? = null,
): PeriodicWorkRequest {
    val builder = dailyCheckBuilder(workerClass)
        .setNextScheduleTimeOverride(nextDailyCheckAt(hour, now))
    if (id != null) builder.setId(id)
    return builder.build()
}

/** Every terminal path reanchors; retries must keep WorkManager's 15/30/60-minute backoff. */
internal suspend fun finishDailyCheck(
    result: ListenableWorker.Result,
    onScheduleFailure: (Exception) -> Unit = {},
    scheduleNext: suspend () -> Unit,
): ListenableWorker.Result {
    if (result is ListenableWorker.Result.Retry) return result
    try {
        scheduleNext()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A notification may already have been posted. Keep the periodic fallback rather
        // than retrying the digest or letting an exception permanently fail this worker.
        onScheduleFailure(error)
    }
    return result
}
