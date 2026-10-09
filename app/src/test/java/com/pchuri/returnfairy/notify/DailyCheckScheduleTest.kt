package com.pchuri.returnfairy.notify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

class DailyCheckScheduleTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val start = ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, seoul)

    @Test
    fun exhaustedRetriesKeepNextMorningAtNineInsteadOfTenFortyFive() = runBlocking<Unit> {
        var now = start
        for (backoff in listOf(15L, 30L, 60L)) {
            finishDailyCheck(Result.retry()) { fail("A retry must not override backoff") }
            now = now.plusMinutes(backoff)
        }
        assertEquals(10, now.hour)
        assertEquals(45, now.minute)
        finishDailyCheck(Result.success()) {
            assertNextRun(now, start.plusDays(1))
        }
    }

    @Test
    fun recoveryOnEveryRetryReturnsToTheChosenHour() = runBlocking {
        for (elapsedMinutes in listOf(15L, 45L, 105L)) {
            finishDailyCheck(Result.success()) {
                assertNextRun(start.plusMinutes(elapsedMinutes), start.plusDays(1))
            }
        }
    }

    @Test
    fun repeatedDaysDoNotAccumulateRetryDrift() {
        for (day in 0L..6L) {
            assertNextRun(start.plusDays(day).plusMinutes(105), start.plusDays(day + 1))
        }
    }

    @Test
    fun delayedWorkAndNewReminderHourUseTheNextFutureLocalSlot() {
        assertNextRun(start.plusDays(2).plusHours(4), start.plusDays(3))
        assertNextRun(start.plusMinutes(105), start.withHour(18), hour = 18)
        assertNextRun(start.plusMinutes(105), start.plusDays(1).withHour(7), hour = 7)
    }

    @Test
    fun initialRunKeepsSecondsAndMillisecondPrecisionUntilTheChosenHour() {
        val now = start.minusSeconds(1).minusNanos(123_000_000)
        val request = dailyCheckRequest(ScheduleWorker::class.java, 9, now)
        request.workSpec.lastEnqueueTime = now.toInstant().toEpochMilli()
        assertEquals(start.toInstant().toEpochMilli(), request.workSpec.calculateNextRunTime())
    }

    @Test
    fun exactlyAtTheChosenHourSchedulesTheFollowingDay() {
        assertNextRun(start, start.plusDays(1))
    }

    @Test
    fun daylightSavingChangesKeepTheLocalHourAcrossShortAndLongDays() {
        val zone = ZoneId.of("America/New_York")
        val spring = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone)
        val autumn = ZonedDateTime.of(2026, 10, 31, 9, 0, 0, 0, zone)
        assertEquals(23, Duration.ofMillis(nextDailyCheckAt(9, spring) - spring.toInstant().toEpochMilli()).toHours())
        assertEquals(25, Duration.ofMillis(nextDailyCheckAt(9, autumn) - autumn.toInstant().toEpochMilli()).toHours())
        assertNextRun(spring, spring.plusDays(1))
        assertNextRun(autumn, autumn.plusDays(1))
    }

    @Test
    fun missingDaylightSavingHourUsesTheFirstValidLocalTime() {
        val beforeGap = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ZoneId.of("America/New_York"))
        assertEquals(beforeGap.withHour(3).toInstant().toEpochMilli(), nextDailyCheckAt(2, beforeGap))
    }

    @Test
    fun requestPreservesWorkerIdentityNetworkConstraintAndRetryPolicy() {
        val id = UUID.randomUUID()
        val request = dailyCheckRequest(ScheduleWorker::class.java, 9, start, id)
        assertEquals(id, request.id)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.backoffDelayDuration)
        assertEquals(TimeUnit.DAYS.toMillis(1), request.workSpec.intervalDuration)
    }

    @Test
    fun nativeRetryBackoffRemainsFifteenThirtySixtyMinutesAfterOverrideIsConsumed() {
        val spec = dailyCheckRequest(ScheduleWorker::class.java, 9, start).workSpec
        // WorkManager consumes the current run's override when that attempt finishes.
        spec.nextScheduleTimeOverride = Long.MAX_VALUE
        spec.periodCount = 1
        spec.lastEnqueueTime = start.toInstant().toEpochMilli()
        for ((index, minutes) in listOf(15L, 30L, 60L).withIndex()) {
            spec.runAttemptCount = index + 1
            assertEquals(start.plusMinutes(minutes).toInstant().toEpochMilli(), spec.calculateNextRunTime())
        }
    }

    @Test
    fun everyTerminalResultReanchorsEvenWithoutANotification() = runBlocking {
        // All successful early exits (no accounts, empty digest, no permission) are wrapped
        // by finishDailyCheck in doWork, rather than relying on the notification branch.
        for (result in listOf(Result.success(), Result.failure())) {
            var updates = 0
            assertSame(result, finishDailyCheck(result) { updates++ })
            assertEquals(1, updates)
        }
    }

    @Test
    fun completionWaitsUntilTheScheduleUpdateIsPersisted() = runBlocking {
        val persisted = CompletableDeferred<Unit>()
        val finished = async { finishDailyCheck(Result.success()) { persisted.await() } }
        yield()
        assertFalse(finished.isCompleted)
        persisted.complete(Unit)
        assertEquals(Result.success(), finished.await())
    }

    @Test
    fun workManagerMinimumSpacingStillAppliesWhenCompletionIsJustBeforeTheHour() {
        val now = start.minusMinutes(5)
        val spec = dailyCheckRequest(ScheduleWorker::class.java, 9, now).workSpec
        spec.periodCount = 1
        spec.lastEnqueueTime = now.toInstant().toEpochMilli()
        assertEquals(now.plusMinutes(15).toInstant().toEpochMilli(), spec.calculateNextRunTime())
    }

    @Test
    fun failedSchedulePersistenceKeepsSuccessWithoutRetryingANotification() = runBlocking {
        val failure = IllegalStateException("database unavailable")
        var attempts = 0
        var reported: Exception? = null
        val result = finishDailyCheck(Result.success(), onScheduleFailure = { reported = it }) {
            attempts++
            throw failure
        }
        assertEquals(Result.success(), result)
        assertEquals(1, attempts)
        assertSame(failure, reported)
    }

    @Test
    fun cancellationIsPropagatedInsteadOfReportedAsASchedulingFailure() = runBlocking<Unit> {
        val cancellation = CancellationException("worker stopped")
        try {
            finishDailyCheck(Result.success(), onScheduleFailure = { fail("Cancellation was swallowed") }) {
                throw cancellation
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun assertNextRun(now: ZonedDateTime, expected: ZonedDateTime, hour: Int = 9) {
        val spec = dailyCheckRequest(ScheduleWorker::class.java, hour, now).workSpec
        // These are the fields WorkManager resets when a periodic attempt completes.
        spec.periodCount = 1
        spec.lastEnqueueTime = now.toInstant().toEpochMilli()
        spec.runAttemptCount = 0
        assertEquals(expected.toInstant().toEpochMilli(), spec.calculateNextRunTime())
    }

    class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }
}
