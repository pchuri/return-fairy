package com.pchuri.returnfairy.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SnapshotFallbackTest {
    private val at = LocalDateTime.of(2026, 10, 5, 9, 0)
    private fun success(id: String, title: String = "book") = AccountStatus(
        label = id, userId = id,
        books = listOf(LibraryBook(title, BookStatus.LOANED, LocalDate.of(2026, 10, 6), "", null, false, "library", "")),
        reservations = listOf(Reservation("pickup", "library", 1, 1, LocalDate.of(2026, 10, 7))),
    )
    private fun failed(id: String, error: SplibErrorKind) =
        AccountStatus("renamed $id", emptyList(), emptyList(), error, userId = id)

    @Test
    fun mixedSuccessRetainsOnlyFailedAccountsAndTheirOriginalSuccessTimes() {
        val old = Snapshot(at, listOf(success("a"), success("b")))
        val fresh = Snapshot(at.plusDays(1), listOf(success("a", "new book"), failed("b", SplibErrorKind.TIMEOUT)))
        val merged = fresh.withCachedFallback(old)
        assertEquals(at.plusDays(1), merged.fetchedAt)
        assertEquals("new book", merged.accounts[0].books.single().title)
        assertEquals(at.plusDays(1), merged.accounts[0].lastSuccessfulAt)
        assertFalse(merged.accounts[0].isStale)
        assertEquals(old.accounts[1].books, merged.accounts[1].books)
        assertEquals(old.accounts[1].reservations, merged.accounts[1].reservations)
        assertEquals("renamed b", merged.accounts[1].label)
        assertEquals(SplibErrorKind.TIMEOUT, merged.accounts[1].error)
        assertEquals(at, merged.accounts[1].lastSuccessfulAt)
        assertTrue(merged.hasStaleResults)
    }

    @Test
    fun repeatedOutagesDoNotEraseOrRedateLastSuccess() {
        val old = Snapshot(at, listOf(success("a")))
        val first = Snapshot(at.plusHours(1), listOf(failed("a", SplibErrorKind.NETWORK))).withCachedFallback(old)
        val second = Snapshot(at.plusHours(2), listOf(failed("a", SplibErrorKind.TIMEOUT))).withCachedFallback(first)
        assertEquals(old.accounts[0].books, second.accounts[0].books)
        assertEquals(at, second.accounts[0].lastSuccessfulAt)
        assertTrue(second.accounts[0].isStale)
    }

    @Test
    fun removedAccountsAreDroppedAndNewFailedAccountsStayErrors() {
        val old = Snapshot(at, listOf(success("removed"), success("kept")))
        val fresh = Snapshot(at.plusHours(1), listOf(failed("kept", SplibErrorKind.NETWORK), failed("new", SplibErrorKind.NETWORK)))
        val merged = fresh.withCachedFallback(old)
        assertEquals(listOf("kept", "new"), merged.accounts.map { it.userId })
        assertTrue(merged.accounts[0].isStale)
        assertFalse(merged.accounts[1].isStale)
        assertNull(merged.accounts[1].lastSuccessfulAt)
        assertTrue(merged.accounts[1].books.isEmpty())
    }

    @Test
    fun permanentErrorsNeverMasqueradeAsSuccessfulCachedResults() {
        val old = Snapshot(at, listOf(success("a")))
        for (error in listOf(SplibErrorKind.LOGIN_FAILED, SplibErrorKind.SESSION_EXPIRED, SplibErrorKind.SITE_CHANGED)) {
            val fresh = Snapshot(at.plusHours(1), listOf(failed("a", error)))
            assertEquals(fresh, fresh.withCachedFallback(old))
            val later = Snapshot(at.plusHours(2), listOf(failed("a", SplibErrorKind.NETWORK)))
            assertEquals(later, later.withCachedFallback(fresh))
        }
    }

    @Test
    fun newestSuccessIsChosenPerAccountAcrossMemoryAndDisk() {
        val memory = Snapshot(at.plusHours(3), listOf(
            success("a", "new a").copy(lastSuccessfulAt = at.plusHours(3)),
            success("b", "old b").copy(error = SplibErrorKind.NETWORK, lastSuccessfulAt = at),
        ))
        val disk = Snapshot(at.plusHours(2), listOf(success("a", "old a"), success("b", "new b")))
        val merged = Snapshot(at.plusHours(4), listOf(failed("a", SplibErrorKind.NETWORK), failed("b", SplibErrorKind.TIMEOUT)))
            .withCachedFallback(memory, disk)
        assertEquals(listOf("new a", "new b"), merged.accounts.map { it.books.single().title })
        assertEquals(listOf(at.plusHours(3), at.plusHours(2)), merged.accounts.map { it.lastSuccessfulAt })
    }

    @Test
    fun successfulEmptyResultReplacesOldBooksAndCanBeRetainedLater() {
        val old = Snapshot(at, listOf(success("a")))
        val empty = Snapshot(at.plusHours(1), listOf(success("a").copy(books = emptyList(), reservations = emptyList())))
            .withCachedFallback(old)
        assertTrue(empty.accounts.single().books.isEmpty())
        assertFalse(empty.hasStaleResults)
        val outage = Snapshot(at.plusHours(2), listOf(failed("a", SplibErrorKind.TIMEOUT))).withCachedFallback(empty)
        assertTrue(outage.accounts.single().books.isEmpty())
        assertTrue(outage.hasStaleResults)
        assertEquals(at.plusHours(1), outage.accounts.single().lastSuccessfulAt)
    }

    @Test
    fun recoveryClearsStaleStatusAndAdvancesSuccessTime() {
        val old = Snapshot(at, listOf(success("a").copy(error = SplibErrorKind.NETWORK, lastSuccessfulAt = at)))
        val merged = Snapshot(at.plusHours(1), listOf(success("a", "recovered"))).withCachedFallback(old)
        assertFalse(merged.hasStaleResults)
        assertNull(merged.accounts.single().error)
        assertEquals(at.plusHours(1), merged.accounts.single().lastSuccessfulAt)
    }
}
