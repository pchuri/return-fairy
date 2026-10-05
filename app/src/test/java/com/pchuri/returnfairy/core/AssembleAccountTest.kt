package com.pchuri.returnfairy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AssembleAccountTest {
    private val today = LocalDate.of(2026, 10, 5)

    private val account = assembleAccount(
        label = "홍길동",
        loans = listOf(
            LoanRow("어린 왕자", "글마루", "2026.09.20", "2026.10.20", isBooksole = false),
            LoanRow("데미안", "잠실", "2026.09.20", "2026.10.03", isBooksole = false),
            LoanRow("모모", "스마트", "2026.09.20", "2026.10.11", isBooksole = true),
            LoanRow("읽을 수 없는 날짜", "거마", "2026.09.20", "", isBooksole = false),
        ),
        doorae = listOf(
            DooraeRow("데미안", "입수", "잠실", "돌마리"),
            DooraeRow("나니아 연대기", "입수", "스마트", "거마"),
            DooraeRow("80일간의 세계 일주", "발송", "글마루", "소나무언덕2호"),
        ),
        reservations = listOf(Reservation("1984", "잠실", 1, 1, LocalDate.of(2026, 10, 8))),
    )

    @Test
    fun loansThatAreAlsoInterlibraryCarryTheInterlibraryDetail() {
        val demian = account.books.single { it.title == "데미안" }
        assertEquals(true, demian.isInterlibrary)
        assertEquals("잠실", demian.library)
        assertEquals("돌마리", demian.providingLibrary)
        assertEquals(BookStatus.LOANED, demian.status)
    }

    @Test
    fun interlibraryEntriesNotYetLoanedAreAddedAsPickupOrTransit() {
        val narnia = account.books.single { it.title == "나니아 연대기" }
        assertEquals(BookStatus.READY_FOR_PICKUP, narnia.status)
        val travel = account.books.single { it.title == "80일간의 세계 일주" }
        assertEquals(BookStatus.IN_TRANSIT, travel.status)
        assertEquals("발송", travel.transitStatus)
        assertNull(travel.dueDate)
    }

    @Test
    fun countsMatchTheDesktopAndWidget() {
        assertEquals(6, account.books.size)
        assertEquals(4, account.interlibraryCount)
        assertEquals(1, account.pickupBookCount)
        assertEquals(1, account.readyReservationCount)
    }

    @Test
    fun sortedPickupFirstThenLoansByDueThenTransit() {
        assertEquals(
            listOf("나니아 연대기", "읽을 수 없는 날짜", "데미안", "모모", "어린 왕자", "80일간의 세계 일주"),
            account.sortedBooks(today).map { it.title },
        )
        assertEquals(-2L, account.books.single { it.title == "데미안" }.daysLeft(today))
    }

    @Test
    fun unreadableDueDateStaysALoan() {
        val book = account.books.single { it.title == "읽을 수 없는 날짜" }
        assertEquals(BookStatus.LOANED, book.status)
        assertNull(book.daysLeft(today))
    }

    @Test
    fun onlyConnectionFailuresCountAsOffline() {
        val at = java.time.LocalDateTime.of(2026, 10, 5, 9, 0)
        fun failed(kind: SplibErrorKind) = AccountStatus("홍길동", emptyList(), emptyList(), kind)
        assertEquals(true, Snapshot(at, listOf(failed(SplibErrorKind.NETWORK), failed(SplibErrorKind.TIMEOUT))).isOffline())
        assertEquals(false, Snapshot(at, listOf(failed(SplibErrorKind.NETWORK), failed(SplibErrorKind.LOGIN_FAILED))).isOffline())
        assertEquals(false, Snapshot(at, listOf(failed(SplibErrorKind.NETWORK), account)).isOffline())
        assertEquals(false, Snapshot(at, emptyList()).isOffline())
    }

    @Test
    fun offlineFallbackUsesCacheForCurrentAccountsOnly() {
        val at = java.time.LocalDateTime.of(2026, 10, 5, 9, 0)
        val cached = Snapshot(at, listOf(account.copy(userId = "hong"), account.copy(label = "지운 계정", userId = "gone")))
        val fresh = Snapshot(
            at.plusDays(1),
            listOf(
                AccountStatus("길동", emptyList(), emptyList(), SplibErrorKind.NETWORK, userId = "hong"),
                AccountStatus("새 계정", emptyList(), emptyList(), SplibErrorKind.TIMEOUT, userId = "new"),
            ),
        )
        val shown = fresh.withOfflineFallback(cached)
        assertEquals(at, shown.fetchedAt)
        assertEquals(listOf("길동", "새 계정"), shown.accounts.map { it.label })
        assertEquals(account.books, shown.accounts[0].books)
        assertEquals(SplibErrorKind.TIMEOUT, shown.accounts[1].error)
    }

    @Test
    fun labelFallsBackToSiteNameThenId() {
        val account = com.pchuri.returnfairy.data.Account("hong", "pw")
        assertEquals("홍길동", displayLabel(account, "홍길동"))
        assertEquals("hong", displayLabel(account, ""))
        assertEquals("길동이", displayLabel(account.copy(label = "길동이"), "홍길동"))
        assertEquals(false, account.toString().contains("pw"))
    }

    @Test
    fun offlineFallbackWithoutMatchingCacheKeepsTheFreshResult() {
        val at = java.time.LocalDateTime.of(2026, 10, 5, 9, 0)
        val fresh = Snapshot(at, listOf(AccountStatus("길동", emptyList(), emptyList(), SplibErrorKind.NETWORK, userId = "hong")))
        val oldFormatCache = Snapshot(at.minusDays(1), listOf(account))
        assertEquals(true, fresh.withOfflineFallback(oldFormatCache) === fresh)
    }
}
