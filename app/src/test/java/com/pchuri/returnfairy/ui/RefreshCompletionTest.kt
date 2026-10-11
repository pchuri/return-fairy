package com.pchuri.returnfairy.ui

import com.pchuri.returnfairy.core.AccountStatus
import com.pchuri.returnfairy.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RefreshCompletionTest {
    private val shown = Snapshot(LocalDateTime.of(2026, 10, 11, 9, 0),
        listOf(AccountStatus("synthetic", emptyList(), emptyList(), userId = "id")))

    @Test fun cancelledCompletionCannotStopReplacementOrDisplayItsOldResult() {
        val flow = MutableStateFlow(DashboardUiState(refreshing = true, refreshSequence = 2))
        flow.completeLookup(1, shown) { false }
        assertTrue(flow.value.refreshing)
        assertNull(flow.value.snapshot)
        flow.completeLookup(2, shown) { true }
        assertFalse(flow.value.refreshing)
        assertEquals(shown, flow.value.snapshot)
    }

    @Test fun replacementAfterActiveCheckStillWinsWhenCompletionRetriesItsCas() {
        val flow = MutableStateFlow(DashboardUiState(refreshing = true, refreshSequence = 1))
        val checked = CountDownLatch(1)
        val continueCompletion = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit {
                flow.completeLookup(1, shown) {
                    checked.countDown()
                    check(continueCompletion.await(5, TimeUnit.SECONDS))
                    true // Previous job looked active, but refresh starts before its CAS.
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            flow.update { it.copy(refreshing = true, refreshSequence = 2) }
            continueCompletion.countDown()
            old.get(5, TimeUnit.SECONDS)
            assertEquals(2L, flow.value.refreshSequence)
            assertTrue(flow.value.refreshing)
            assertNull(flow.value.snapshot)
        } finally { continueCompletion.countDown(); pool.shutdownNow() }
    }
}
