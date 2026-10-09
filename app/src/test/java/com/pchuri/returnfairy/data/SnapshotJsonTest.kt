package com.pchuri.returnfairy.data

import com.pchuri.returnfairy.core.AccountStatus
import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.LibraryBook
import com.pchuri.returnfairy.core.Reservation
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SnapshotJsonTest {
    @Test
    fun roundTripsEveryField() {
        val snapshot = Snapshot(
            LocalDateTime.of(2026, 10, 5, 19, 38, 12),
            listOf(
                AccountStatus(
                    "홍길동",
                    listOf(
                        LibraryBook("모모", BookStatus.LOANED, LocalDate.of(2026, 10, 11), "2026.10.11", null, true, "스마트", "위례"),
                        LibraryBook("나니아 연대기", BookStatus.READY_FOR_PICKUP, null, "입수", "입수", true, "스마트", "거마"),
                        LibraryBook("날짜 없음", BookStatus.LOANED, null, "", null, false, "거마", ""),
                    ),
                    listOf(Reservation("1984", "잠실", 1, 2, LocalDate.of(2026, 10, 8)), Reservation("이방인", "거마", 3, 5, null)),
                ),
                AccountStatus("김영희", emptyList(), emptyList(), SplibErrorKind.LOGIN_FAILED),
            ),
        )
        assertEquals(snapshot, SnapshotJson.decode(SnapshotJson.encode(snapshot)))
    }
    @Test
    fun staleAccountRetainsItsErrorAndSuccessTimeAcrossRestart() {
        val at = LocalDateTime.of(2026, 10, 5, 9, 0)
        val cached = Snapshot(at, listOf(AccountStatus("name", emptyList(), emptyList(), userId = "id")))
        val fresh = Snapshot(at.plusHours(1), listOf(AccountStatus("name", emptyList(), emptyList(), SplibErrorKind.TIMEOUT, "id")))
        val merged = fresh.withCachedFallback(cached)
        val decoded = SnapshotJson.decode(SnapshotJson.encode(merged))
        assertEquals(merged, decoded)
        assertEquals(true, decoded.hasStaleResults)
        assertEquals(at, decoded.accounts.single().lastSuccessfulAt)
    }

    @Test
    fun oldCacheWithoutSuccessMetadataStillDecodesAndCanBeRetained() {
        val at = LocalDateTime.of(2026, 10, 5, 9, 0)
        val decoded = SnapshotJson.decode("""{"fetchedAt":"2026-10-05T09:00","accounts":[{"label":"name","userId":"id","error":null,"books":[],"reservations":[]}]}""")
        assertEquals(null, decoded.accounts.single().lastSuccessfulAt)
        val fresh = Snapshot(at.plusHours(1), listOf(AccountStatus("name", emptyList(), emptyList(), SplibErrorKind.NETWORK, "id")))
        assertEquals(at, fresh.withCachedFallback(decoded).accounts.single().lastSuccessfulAt)
    }

}
