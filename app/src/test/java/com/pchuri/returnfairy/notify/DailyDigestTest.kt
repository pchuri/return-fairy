package com.pchuri.returnfairy.notify

import com.pchuri.returnfairy.core.AccountStatus
import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.LibraryBook
import com.pchuri.returnfairy.core.Reservation
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DailyDigestTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun loan(title: String, due: LocalDate) =
        LibraryBook(title, BookStatus.LOANED, due, due.toString(), null, false, "거마", "")

    @Test
    fun groupsOverdueTodayTomorrowAndPickups() {
        val snapshot = Snapshot(
            LocalDateTime.of(2026, 10, 5, 9, 0),
            listOf(
                AccountStatus(
                    "홍길동",
                    listOf(
                        loan("데미안", today.minusDays(2)),
                        loan("모모", today),
                        loan("어린 왕자", today.plusDays(1)),
                        loan("샬롯의 거미줄", today.plusDays(9)),
                        LibraryBook("나니아 연대기", BookStatus.READY_FOR_PICKUP, null, "입수", "입수", true, "스마트", "거마"),
                        LibraryBook("80일간의 세계 일주", BookStatus.IN_TRANSIT, null, "발송", "발송", true, "글마루", ""),
                    ),
                    listOf(Reservation("1984", "잠실", 1, 1, today.plusDays(3)), Reservation("이방인", "거마", 2, 4, null)),
                ),
                AccountStatus("김영희", emptyList(), emptyList(), SplibErrorKind.NETWORK),
            ),
        )
        val digest = buildDailyDigest(snapshot, today)
        assertEquals(listOf("홍길동 · 데미안"), digest.overdue)
        assertEquals(listOf("홍길동 · 모모"), digest.dueToday)
        assertEquals(listOf("홍길동 · 어린 왕자"), digest.dueTomorrow)
        assertEquals(listOf("홍길동 · 나니아 연대기 (스마트)", "홍길동 · 1984 (잠실)"), digest.pickups)
    }

    @Test
    fun nothingActionableIsEmpty() {
        val snapshot = Snapshot(LocalDateTime.now(), listOf(AccountStatus("홍길동", listOf(loan("모모", today.plusDays(5))), emptyList())))
        assertTrue(buildDailyDigest(snapshot, today).isEmpty)
    }
}
