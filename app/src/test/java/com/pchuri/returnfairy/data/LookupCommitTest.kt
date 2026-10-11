package com.pchuri.returnfairy.data

import android.content.Context
import com.pchuri.returnfairy.core.*
import com.pchuri.returnfairy.notify.buildDailyDigest
import com.pchuri.returnfairy.notify.planDailyCheck
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Production preference/file/commit paths, synthetic accounts, no Keystore/network/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LookupCommitTest {
    private lateinit var context: Context
    private lateinit var accounts: AccountStore
    private lateinit var store: SnapshotStore
    private val at = LocalDateTime.of(2026, 10, 11, 9, 0)
    private val a = Account("removed", "synthetic", "A")
    private val b = Account("kept", "synthetic", "B")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("returnfairy_accounts", Context.MODE_PRIVATE).edit().clear().commit()
        // Identity codec is injected only into this test instance; production uses Android Keystore.
        accounts = AccountStore(context, { it }, { it })
        store = SnapshotStore(context, accounts::load)
        store.clear()
        accounts.save(listOf(a, b))
    }

    private fun fresh(session: LookupSession, time: LocalDateTime = at, title: String = "due",
                      error: SplibErrorKind? = null): Snapshot = Snapshot(time, session.accounts.map {
        AccountStatus(it.label, if (error == null) listOf(LibraryBook(title, BookStatus.LOANED,
            at.toLocalDate(), "", null, false, "synthetic", "")) else emptyList(), emptyList(), error, it.userId)
    })

    // Decode the actual file rather than the process fallback, proving durable reload.
    private fun persisted() = SnapshotJson.decode(java.io.File(context.filesDir, "last_snapshot.json").readText())

    @Test fun deletingOneAccountPreservesUnchangedResultsAndExcludesRemovedReminder() {
        val pending = accounts.beginLookup()
        accounts.save(listOf(b))
        var reminders = emptyList<String>()
        store.mergeAndSave(fresh(pending), pending) { _, accepted ->
            reminders = buildDailyDigest(accepted, at.toLocalDate()).dueToday
        }
        assertEquals(listOf(b.userId), persisted().accounts.map { it.userId })
        assertEquals(listOf("B · due"), reminders)
    }

    @Test fun deletingLastAccountRejectsCacheAndEverySideEffect() {
        val pending = accounts.beginLookup()
        store.mergeAndSave(fresh(pending), pending)
        accounts.save(emptyList())
        store.mergeAndSave(fresh(pending), pending) { shown, accepted ->
            assertTrue(shown.accounts.isEmpty())
            assertTrue(buildDailyDigest(accepted, at.toLocalDate()).isEmpty)
            assertFalse(planDailyCheck(accepted, at.toLocalDate(), 0).retry)
        }
        assertNull(store.load())
    }

    @Test fun changedCredentialsInvalidateMemoryDiskAndOldResponseButKeepOtherAccount() {
        val pending = accounts.beginLookup()
        val memory = store.mergeAndSave(fresh(pending), pending)
        accounts.save(listOf(a.copy(password = "changed"), b))
        assertEquals(listOf(b.userId), persisted().accounts.map { it.userId })
        store.mergeAndSave(fresh(pending), pending, memory) { shown, accepted ->
            assertEquals(listOf(b.userId), shown.accounts.map { it.userId })
            assertTrue(accepted.accounts.isEmpty()) // B's same attempt was already accepted.
        }
        val current = accounts.beginLookup()
        store.mergeAndSave(fresh(current, at.plusMinutes(1), "new"), current)
        assertEquals(listOf("new", "new"), persisted().accounts.map { it.books.single().title })
    }

    @Test fun changingLabelRejectsOldLabelAndPreservesUnchangedRevision() {
        val pending = accounts.beginLookup()
        val saved = accounts.save(listOf(a.copy(label = "changed"), b))
        assertNotEquals(pending.accounts[0].revision, saved[0].revision)
        assertEquals(pending.accounts[1].revision, saved[1].revision)
        store.mergeAndSave(fresh(pending), pending)
        assertEquals(listOf(b.userId), persisted().accounts.map { it.userId })
    }

    @Test fun removeThenReaddSameCredentialsCannotReviveOldIncarnation() {
        val old = accounts.beginLookup()
        accounts.save(listOf(b))
        accounts.save(listOf(a, b))
        store.mergeAndSave(fresh(old), old)
        assertEquals(listOf(b.userId), persisted().accounts.map { it.userId })
    }

    @Test fun invertedForegroundBackgroundSuccessesKeepNewestPerAccountAcrossReload() {
        val foreground = accounts.beginLookup()
        val background = accounts.beginLookup()
        store.mergeAndSave(fresh(background, at.plusMinutes(1), "latest"), background)
        store.mergeAndSave(fresh(foreground, at, "obsolete"), foreground) { _, accepted ->
            assertTrue(accepted.accounts.isEmpty())
        }
        persisted().accounts.forEach {
            assertEquals("latest", it.books.single().title)
            assertEquals(at.plusMinutes(1), it.lastSuccessfulAt)
            assertEquals(background.sequence, it.requestSequence)
        }
    }

    @Test fun equalTimeUsesRequestSequenceAndDuplicateCommitKeepsFirst() {
        val first = accounts.beginLookup()
        val second = accounts.beginLookup()
        store.mergeAndSave(fresh(second, title = "second"), second)
        store.mergeAndSave(fresh(first, title = "first"), first)
        store.mergeAndSave(fresh(second, title = "duplicate"), second)
        assertEquals("second", persisted().accounts.first().books.single().title)
    }

    @Test fun mixedAccountsAcceptOlderAttemptOnlyWhereNoNewerResultWasAccepted() {
        val first = accounts.beginLookup()
        val second = accounts.beginLookup()
        store.mergeAndSave(fresh(second, at.plusMinutes(1), "new").let {
            it.copy(accounts = it.accounts.filter { result -> result.userId == a.userId })
        }, second)
        store.mergeAndSave(fresh(first, title = "old"), first) { _, accepted ->
            assertEquals(listOf(b.userId), accepted.accounts.map { it.userId })
        }
        assertEquals(listOf("new", "old"), persisted().accounts.map { it.books.single().title })
    }

    @Test fun newerTransientFailureRetainsSuccessAndBlocksOldSuccess() {
        val old = accounts.beginLookup()
        store.mergeAndSave(fresh(old), old)
        val next = accounts.beginLookup()
        store.mergeAndSave(fresh(next, at.plusMinutes(1), error = SplibErrorKind.TIMEOUT), next) { _, accepted ->
            assertTrue(planDailyCheck(accepted, at.toLocalDate(), 0).retry)
            assertTrue(buildDailyDigest(accepted, at.toLocalDate()).isEmpty)
        }
        store.mergeAndSave(fresh(old, title = "obsolete"), old)
        persisted().accounts.forEach {
            assertTrue(it.isStale)
            assertEquals(at, it.lastSuccessfulAt)
            assertEquals("due", it.books.single().title)
            assertEquals(next.sequence, it.requestSequence)
        }
    }

    @Test fun permanentFailureClearsDataBlocksOldSuccessAndAllowsNewRecovery() {
        val old = accounts.beginLookup()
        store.mergeAndSave(fresh(old), old)
        val failure = accounts.beginLookup()
        store.mergeAndSave(fresh(failure, at.plusMinutes(1), error = SplibErrorKind.LOGIN_FAILED), failure)
        store.mergeAndSave(fresh(old), old)
        persisted().accounts.forEach { assertTrue(it.books.isEmpty()); assertNull(it.lastSuccessfulAt) }
        val recovery = accounts.beginLookup()
        store.mergeAndSave(fresh(recovery, at.plusMinutes(2), "recovered"), recovery)
        assertEquals("recovered", persisted().accounts.first().books.single().title)
    }

    @Test fun olderFailuresCannotReplaceNewerSuccess() {
        for (error in SplibErrorKind.entries) {
            setup()
            val old = accounts.beginLookup()
            val next = accounts.beginLookup()
            store.mergeAndSave(fresh(next, at.plusMinutes(1), "new"), next)
            store.mergeAndSave(fresh(old, error = error), old)
            assertNull(persisted().accounts.first().error)
            assertEquals("new", persisted().accounts.first().books.single().title)
        }
    }

    @Test fun clockRollbackCannotMoveSuccessfulDataBackwards() {
        val first = accounts.beginLookup()
        store.mergeAndSave(fresh(first), first)
        val second = accounts.beginLookup()
        store.mergeAndSave(fresh(second, at.minusMinutes(1), "backwards"), second)
        assertEquals(at, persisted().accounts.first().lastSuccessfulAt)
        assertEquals("due", persisted().accounts.first().books.single().title)
    }

    @Test fun sequenceAndRevisionsSurviveNewStoreInstancesAndNoopEdits() {
        val first = accounts.beginLookup()
        accounts.save(listOf(a, b))
        accounts = AccountStore(context, { it }, { it })
        val next = accounts.beginLookup()
        assertTrue(next.sequence > first.sequence)
        assertEquals(first.accounts, next.accounts)
    }

    @Test fun legacyAccountsAndCacheRemainReadableThenGainOrderedProvenance() {
        store.clear()
        context.getSharedPreferences("returnfairy_accounts", Context.MODE_PRIVATE).edit()
            .putString("accounts", """[{"userId":"kept","encryptedPassword":"synthetic","label":"B"}]""").commit()
        val legacy = fresh(accounts.beginLookup())
        // v4.0.2 wrote neither of these provenance fields.
        val json = org.json.JSONObject(SnapshotJson.encode(legacy))
        json.getJSONArray("accounts").getJSONObject(0).apply {
            remove("accountRevision"); remove("requestSequence"); remove("lastSuccessfulAt")
        }
        java.io.File(context.filesDir, "last_snapshot.json").writeText(json.toString())
        assertEquals("due", store.load()!!.accounts.single().books.single().title)
        val session = accounts.beginLookup()
        store.mergeAndSave(fresh(session, at.plusMinutes(1), error = SplibErrorKind.NETWORK), session)
        assertTrue(persisted().accounts.single().isStale)
        assertEquals(at, persisted().accounts.single().lastSuccessfulAt)
        assertEquals(session.sequence, persisted().accounts.single().requestSequence)
        accounts.save(listOf(b.copy(password = "changed")))
        assertNull(store.load())
    }

    @Test fun failedDiskWriteStillFencesOldResultsAcrossWorkerStoreInstances() {
        val blocker = java.io.File(context.filesDir, "cache-parent-is-a-file")
        blocker.writeText("synthetic")
        val blockedContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): java.io.File = blocker
        }
        val blockedStore = SnapshotStore(blockedContext, accounts::load)
        try {
            val old = accounts.beginLookup()
            val next = accounts.beginLookup()
            blockedStore.mergeAndSave(fresh(next, at.plusMinutes(1), "new"), next)
            // A regular file cannot be the directory parent of an AtomicFile cache.
            assertTrue(blocker.isFile)
            assertFalse(java.io.File(blocker, "last_snapshot.json").exists())
            SnapshotStore(blockedContext, accounts::load).mergeAndSave(fresh(old), old) { shown, accepted ->
                assertTrue(accepted.accounts.isEmpty())
                assertEquals("new", shown.accounts.first().books.single().title)
            }
            accounts.save(emptyList())
            assertNull(blockedStore.load())
        } finally { blockedStore.clear(); blocker.delete() }
    }

    @Test fun accountMutationCannotEnterBetweenValidationAndNotificationCallback() {
        val pending = accounts.beginLookup()
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val mutationThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        try {
            lateinit var mutation: java.util.concurrent.Future<*>
            store.mergeAndSave(fresh(pending), pending) { _, accepted ->
                mutation = pool.submit { mutationThread.set(Thread.currentThread()); started.countDown(); accounts.save(emptyList()) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                // Wait for the actual monitor contention rather than relying on thread timing.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (mutationThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline)
                    Thread.yield()
                assertEquals(Thread.State.BLOCKED, mutationThread.get().state)
                assertFalse(mutation.isDone)
                assertEquals(2, accepted.accounts.size)
                assertEquals(2, accounts.load().size)
            }
            mutation.get(5, TimeUnit.SECONDS)
            assertNull(store.load())
            store.mergeAndSave(fresh(pending), pending) { _, accepted -> assertTrue(accepted.accounts.isEmpty()) }
        } finally { pool.shutdownNow() }
    }
}
