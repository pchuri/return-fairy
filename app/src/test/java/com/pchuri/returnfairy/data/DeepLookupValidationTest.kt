package com.pchuri.returnfairy.data

import android.content.Context
import com.pchuri.returnfairy.core.*
import com.pchuri.returnfairy.notify.planDailyCheck
import com.pchuri.returnfairy.notify.commitDailyLookup
import androidx.work.ListenableWorker
import com.pchuri.returnfairy.ui.DashboardUiState
import com.pchuri.returnfairy.ui.completeLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Independent validation: isolated synthetic preferences and cache, no network or real device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeepLookupValidationTest {
    private lateinit var context: Context
    private lateinit var accounts: AccountStore
    private lateinit var store: SnapshotStore
    private val at = LocalDateTime.of(2026, 10, 11, 9, 0)
    private val account = Account("deep-synthetic", "not-a-real-password", "Deep")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("returnfairy_accounts", 0).edit().clear().commit()
        accounts = AccountStore(context, { it }, { it })
        store = SnapshotStore(context, accounts::load)
        store.clear()
        accounts.save(listOf(account))
    }

    private fun result(session: LookupSession, title: String, error: SplibErrorKind? = null) =
        Snapshot(at.plusSeconds(session.sequence), session.accounts.map {
            AccountStatus(it.label, if (error != null) emptyList() else listOf(
                LibraryBook(title, BookStatus.LOANED, at.toLocalDate(), "", null, false, "", "")
            ), emptyList(), error = error, userId = it.userId)
        })

    /** A new store alone does not simulate restart: explicitly remove the process-only fallback. */
    private fun coldRestart() {
        val field = SnapshotStore::class.java.getDeclaredField("memory")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableMap<String, Snapshot>).clear()
        accounts = AccountStore(context, { it }, { it })
        store = SnapshotStore(context, accounts::load)
    }

    @Test fun coldRestartPreservesResultFenceAndAccountIncarnation() {
        val old = accounts.beginLookup()
        val newest = accounts.beginLookup()
        store.mergeAndSave(result(newest, "durable-new"), newest)
        coldRestart()
        assertEquals(newest.accounts, accounts.load())
        assertTrue(accounts.beginLookup().sequence > newest.sequence)
        store.mergeAndSave(result(old, "old"), old) { _, accepted ->
            assertTrue(accepted.accounts.isEmpty())
            assertTrue(planDailyCheck(accepted, at.toLocalDate(), 0).digest.isEmpty)
        }
        assertEquals("durable-new", store.load()!!.accounts.single().books.single().title)
    }

    @Test fun staleBytesAfterFailedCleanupCannotResurrectRemovedOrChangedAccountOnRestart() {
        val pending = accounts.beginLookup()
        store.mergeAndSave(result(pending, "private-old"), pending)
        val staleBytes = File(context.filesDir, "last_snapshot.json").readText()
        accounts.save(emptyList())
        accounts.save(listOf(account.copy(password = "new-password")))
        // Simulate stale disk bytes surviving failed cleanup, then a fresh process.
        File(context.filesDir, "last_snapshot.json").writeText(staleBytes)
        coldRestart()
        assertNull(store.load())
        store.mergeAndSave(result(pending, "late-old"), pending) { shown, accepted ->
            assertTrue(shown.accounts.isEmpty())
            assertTrue(accepted.accounts.isEmpty())
        }
        assertNull(store.load())
    }

    @Test fun latestWorkerOutcomeIsShownWhenOlderForegroundCompletes() {
        val foreground = accounts.beginLookup()
        val worker = accounts.beginLookup()
        val flow = MutableStateFlow(DashboardUiState(refreshing = true, refreshSequence = foreground.sequence))
        store.mergeAndSave(result(worker, "worker-new"), worker)
        store.mergeAndSave(result(foreground, "foreground-old"), foreground) { shown, accepted ->
            flow.completeLookup(foreground.sequence, shown) { true }
            assertTrue(accepted.accounts.isEmpty())
        }
        assertFalse(flow.value.refreshing)
        assertEquals("worker-new", flow.value.snapshot!!.accounts.single().books.single().title)
    }

    @Test fun cancellationSuppressesDashboardSideEffectButDoesNotLoseValidCacheCommit() {
        val session = accounts.beginLookup()
        val flow = MutableStateFlow(DashboardUiState(refreshing = true, refreshSequence = session.sequence))
        store.mergeAndSave(result(session, "valid"), session) { shown, _ ->
            flow.completeLookup(session.sequence, shown) { false }
        }
        assertTrue(flow.value.refreshing)
        assertNull(flow.value.snapshot)
        coldRestart()
        assertEquals("valid", store.load()!!.accounts.single().books.single().title)
    }

    @Test fun callbackFailureReleasesMonitorAndDurableFenceRejectsDuplicateNotification() {
        val session = accounts.beginLookup()
        try {
            store.mergeAndSave(result(session, "saved-before-side-effect"), session) { _, _ ->
                throw IllegalStateException("synthetic callback failure")
            }
            fail("expected callback failure")
        } catch (e: IllegalStateException) { assertEquals("synthetic callback failure", e.message) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { accounts.beginLookup() }.get(5, TimeUnit.SECONDS)
            coldRestart()
            store.mergeAndSave(result(session, "duplicate"), session) { _, accepted ->
                assertTrue(accepted.accounts.isEmpty())
            }
            assertEquals("saved-before-side-effect", store.load()!!.accounts.single().books.single().title)
        } finally { pool.shutdownNow() }
    }

    @Test fun simultaneousCompletionPermutationNeverRegressesDurableResult() {
        val sessions = (1..12).map { accounts.beginLookup() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            sessions.shuffled(java.util.Random(42)).map { session ->
                pool.submit { store.mergeAndSave(result(session, "attempt-${session.sequence}"), session) }
            }.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        coldRestart()
        assertEquals(sessions.last().sequence, store.load()!!.accounts.single().requestSequence)
        assertEquals("attempt-${sessions.last().sequence}", store.load()!!.accounts.single().books.single().title)
    }

    @Test fun workerCancelledWhileWaitingForCommitMustNotSubmitReminder() = runBlocking {
        val session = accounts.beginLookup()
        val thread = AtomicReference<Thread>()
        val approachingCommit = CountDownLatch(1)
        val notified = AtomicBoolean(false)
        lateinit var worker: kotlinx.coroutines.Job
        synchronized(LookupCoordination.lock) {
            worker = launch(Dispatchers.Default) {
                thread.set(Thread.currentThread())
                approachingCommit.countDown()
                // Invoke the same commit/planning path called by production DailyCheckWorker.
                commitDailyLookup(store, result(session, "due-now"), session, at.toLocalDate(), 0) {
                    notified.set(true)
                }
            }
            assertTrue(approachingCommit.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, thread.get().state)
            worker.cancel()
        }
        worker.join()
        assertFalse("Cancelled Worker submitted a fresh valid reminder after monitor wait", notified.get())
        assertEquals("due-now", store.load()!!.accounts.single().books.single().title)
    }

    @Test fun productionWorkerCommitPlansFreshSuccessRetryAndPermanentFailure() = runBlocking {
        var count = 0
        val success = accounts.beginLookup()
        assertEquals(ListenableWorker.Result.success(), commitDailyLookup(store, result(success, "notify"),
            success, at.toLocalDate(), 0) { count++ })
        assertEquals(1, count)
        val transient = accounts.beginLookup()
        assertEquals(ListenableWorker.Result.retry(), commitDailyLookup(store,
            result(transient, "", SplibErrorKind.TIMEOUT), transient, at.toLocalDate(), 0) { count++ })
        assertEquals(1, count)
        assertTrue(store.load()!!.accounts.single().isStale)
        val permanent = accounts.beginLookup()
        assertEquals(ListenableWorker.Result.success(), commitDailyLookup(store,
            result(permanent, "", SplibErrorKind.LOGIN_FAILED), permanent, at.toLocalDate(), 0) { count++ })
        assertEquals(1, count)
        assertTrue(store.load()!!.accounts.single().books.isEmpty())
    }

    @Test fun productionWorkerRejectsRemovedRevisionAndOldTransientRetry() = runBlocking {
        var count = 0
        val removed = accounts.beginLookup()
        accounts.save(emptyList())
        accounts.save(listOf(account))
        assertEquals(ListenableWorker.Result.success(), commitDailyLookup(store, result(removed, "old"),
            removed, at.toLocalDate(), 0) { count++ })
        assertEquals(0, count)
        val old = accounts.beginLookup()
        val fresh = accounts.beginLookup()
        commitDailyLookup(store, result(fresh, "new"), fresh, at.toLocalDate(), 0) { count++ }
        assertEquals(1, count)
        assertEquals(ListenableWorker.Result.success(), commitDailyLookup(store,
            result(old, "", SplibErrorKind.NETWORK), old, at.toLocalDate(), 0) { count++ })
        assertEquals(1, count)
        assertEquals("new", store.load()!!.accounts.single().books.single().title)
    }
}
