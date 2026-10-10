package com.pchuri.returnfairy.notify

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pchuri.returnfairy.data.SettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Exercise the real enqueue API/database, including the guard that crashed first launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DailyCheckEnqueueTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private lateinit var settings: SettingsStore
    private val name = "due_reminder_daily"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("returnfairy_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsStore(context)
        settings.reminderHour = (ZonedDateTime.now().hour + 2) % 24
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        manager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun firstLaunchEnqueuesSuccessfullyThroughTheActualWorkManagerApi() {
        assertEquals(0, settings.scheduleVersion)
        scheduleDailyCheck(context, settings)
        val work = activeWork()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(settings.scheduleVersion > 0)
        assertInitialDelayAtSelectedHour(work)
    }

    @Test
    fun reopeningTheAppKeepsTheExistingWorkAndItsSchedule() {
        scheduleDailyCheck(context, settings)
        val first = activeWork()
        scheduleDailyCheck(context, settings)
        val reopened = activeWork()
        assertEquals(first.id, reopened.id)
        assertEquals(first.lastEnqueueTime, reopened.lastEnqueueTime)
        assertEquals(first.initialDelay, reopened.initialDelay)
    }

    @Test
    fun migratingAnOldScheduleReplacesItWithoutAnIllegalOverride() {
        val legacy = PeriodicWorkRequest.Builder(DailyCheckWorker::class.java, 1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, legacy)
            .result.get(10, TimeUnit.SECONDS)
        settings.scheduleVersion = 3
        scheduleDailyCheck(context, settings)
        val replacement = activeWork()
        assertNotEquals(legacy.id.toString(), replacement.id)
        assertNull(manager.getWorkInfoById(legacy.id).get())
        assertInitialDelayAtSelectedHour(replacement)
    }

    @Test
    fun changingTheHourCancelsTheOldRetryChainAndResetsAttempts() {
        scheduleDailyCheck(context, settings)
        val first = activeWork()
        val dao = (manager as WorkManagerImpl).workDatabase.workSpecDao()
        dao.incrementWorkSpecRunAttemptCount(first.id)
        dao.incrementWorkSpecRunAttemptCount(first.id)
        settings.reminderHour = (settings.reminderHour + 1) % 24
        scheduleDailyCheck(context, settings.reminderHour, reschedule = true)
        val replacement = activeWork()
        assertNotEquals(first.id, replacement.id)
        assertEquals(0, replacement.runAttemptCount)
        assertNull(dao.getWorkSpec(first.id))
        assertInitialDelayAtSelectedHour(replacement)
    }

    @Test
    fun terminalOverrideUpdatesTheSameWorkWithoutReplacingItsIdentity() {
        scheduleDailyCheck(context, settings)
        val first = activeWork()
        val request = dailyCheckRequest(DailyCheckWorker::class.java, settings.reminderHour,
            id = java.util.UUID.fromString(first.id))
        manager.updateWork(request).get(10, TimeUnit.SECONDS)
        val updated = activeWork()
        assertEquals(first.id, updated.id)
        assertEquals(request.workSpec.nextScheduleTimeOverride, updated.nextScheduleTimeOverride)
    }

    @Test
    fun anOldCancelledWorkerCannotOverrideTheNewHourSchedule() = runBlocking {
        scheduleDailyCheck(context, settings)
        val first = activeWork()
        settings.reminderHour = (settings.reminderHour + 1) % 24
        scheduleDailyCheck(context, settings.reminderHour, reschedule = true)
        val replacement = activeWork()
        var reported: Exception? = null
        val result = finishDailyCheck(ListenableWorker.Result.success(), onScheduleFailure = { reported = it }) {
            manager.updateWork(dailyCheckRequest(DailyCheckWorker::class.java, 0,
                id = java.util.UUID.fromString(first.id))).get(10, TimeUnit.SECONDS)
        }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(reported?.cause is IllegalArgumentException)
        val after = activeWork()
        assertEquals(replacement.id, after.id)
        assertEquals(replacement.initialDelay, after.initialDelay)
        assertEquals(Long.MAX_VALUE, after.nextScheduleTimeOverride)
    }

    private fun activeWork(): WorkSpec {
        val info = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
            .single { !it.state.isFinished }
        return (manager as WorkManagerImpl).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
    }

    private fun assertInitialDelayAtSelectedHour(work: WorkSpec) {
        assertEquals(Long.MAX_VALUE, work.nextScheduleTimeOverride)
        assertTrue(work.initialDelay > 0)
        val expected = nextDailyCheckAt(settings.reminderHour, ZonedDateTime.now())
        assertTrue(kotlin.math.abs(expected - work.calculateNextRunTime()) < 5_000)
    }
}
