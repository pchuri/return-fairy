package com.pchuri.returnfairy.notify

import com.pchuri.returnfairy.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyCheckPlanTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val good = AccountStatus("good", listOf(
        LibraryBook("due book", BookStatus.LOANED, today, "", null, false, "library", ""),
    ), emptyList(), userId = "good")
    private fun failure(kind: SplibErrorKind) = AccountStatus("failed", emptyList(), emptyList(), kind, userId = "failed")
    private fun snapshot(vararg accounts: AccountStatus) = Snapshot(today.atTime(9, 0), accounts.toList())

    @Test
    fun allTransientFailuresRetryOnlyWithinTheBudget() {
        val fresh = snapshot(failure(SplibErrorKind.NETWORK), failure(SplibErrorKind.TIMEOUT))
        for (attempt in 0 until MAX_DAILY_CHECK_RETRIES) {
            val plan = planDailyCheck(fresh, today, attempt)
            assertTrue(plan.retry)
            assertTrue(plan.digest.isEmpty)
        }
        assertFalse(planDailyCheck(fresh, today, MAX_DAILY_CHECK_RETRIES).retry)
        assertFalse(planDailyCheck(fresh, today, MAX_DAILY_CHECK_RETRIES + 1).retry)
    }

    @Test
    fun partialFailuresDeferNotificationsSoRetriesDoNotDuplicateHealthyAccounts() {
        val fresh = snapshot(good, failure(SplibErrorKind.TIMEOUT))
        val plans = (0..MAX_DAILY_CHECK_RETRIES).map { planDailyCheck(fresh, today, it) }
        assertEquals(1, plans.count { !it.digest.isEmpty })
        assertEquals(listOf("good · due book"), plans.last().digest.dueToday)
        assertFalse(plans.last().retry)
    }

    @Test
    fun recoveryNotifiesOnceWithoutWaitingForRetryBudget() {
        val first = planDailyCheck(snapshot(good, failure(SplibErrorKind.NETWORK)), today, 0)
        val recovered = planDailyCheck(snapshot(good), today, 1)
        assertTrue(first.retry)
        assertTrue(first.digest.isEmpty)
        assertFalse(recovered.retry)
        assertEquals(listOf("good · due book"), recovered.digest.dueToday)
    }

    @Test
    fun permanentFailuresAndEmptySnapshotsDoNotRetry() {
        for (kind in listOf(SplibErrorKind.LOGIN_FAILED, SplibErrorKind.SESSION_EXPIRED, SplibErrorKind.SITE_CHANGED)) {
            val plan = planDailyCheck(snapshot(good, failure(kind)), today, 0)
            assertFalse(plan.retry)
            assertEquals(listOf("good · due book"), plan.digest.dueToday)
        }
        assertFalse(planDailyCheck(snapshot(), today, 0).retry)
    }

    @Test
    fun staleLoansAndPickupsNeverCreateRemindersEvenAfterRetriesAreExhausted() {
        val stale = good.copy(error = SplibErrorKind.NETWORK, lastSuccessfulAt = today.minusDays(1).atStartOfDay(),
            reservations = listOf(Reservation("old pickup", "library", 1, 1, today)))
        val plan = planDailyCheck(snapshot(stale), today, MAX_DAILY_CHECK_RETRIES)
        assertFalse(plan.retry)
        assertTrue(plan.digest.isEmpty)
        assertTrue(buildDailyDigest(snapshot(stale), today).isEmpty)
    }
}
