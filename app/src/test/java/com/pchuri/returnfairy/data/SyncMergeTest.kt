package com.pchuri.returnfairy.data

import com.pchuri.returnfairy.core.Book
import com.pchuri.returnfairy.core.DooraeStatus
import com.pchuri.returnfairy.core.SplibClient
import com.pchuri.returnfairy.core.UserInfo
import com.pchuri.returnfairy.data.db.BookSource
import com.pchuri.returnfairy.data.db.PickupStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val TODAY = LocalDate.now()

/** Stubbed client returning a fixed site state, no network. */
private class StubClient(var books: List<Book>) : SplibClient() {
    override suspend fun getInfos(credentials: List<Pair<String, String>>) = listOf(
        UserInfo(
            name = "홍길동",
            books = books,
            totalBorrowedBooks = books.count { it.loanDate.isNotEmpty() },
            activeInterlibraryLoans = 0,
            pendingInterlibraryPickups = 0,
        )
    )
}

private fun loan(title: String, dueIn: Long, loanedDaysAgo: Long = 0) = Book(
    title = title,
    dueDate = TODAY.plusDays(dueIn).format(FMT),
    isInterlibrary = false,
    library = "송파",
    loanDate = TODAY.minusDays(loanedDaysAgo).format(FMT),
)

private fun pickup(title: String, status: String) = Book(
    title = title,
    dueDate = status,
    isInterlibrary = true,
    library = "엘스도서관",
    loanDate = "",
)

class SyncMergeTest {

    private val creds = listOf("id" to "pw")

    @Test
    fun bookReturnedEarlyInAppIsNotReimportedWhileStillOnSite() = runBlocking {
        val dao = FakeBookDao()
        val client = StubClient(listOf(loan("어린 왕자", dueIn = 5)))
        val repo = BookRepository(dao, client)

        repo.syncFromSplib(creds)
        assertEquals(1, dao.books.size)

        // User returns it in the app before the library processes the return
        repo.markReturned(dao.books.single())
        assertNotNull(dao.books.single().returnedAt)

        // Site still lists the book — must not create a duplicate
        val result = repo.syncFromSplib(creds)
        assertEquals("no duplicate should be imported", 0, result.imported)
        assertEquals(1, dao.books.size)
        assertNotNull("entry stays returned", dao.books.single().returnedAt)
    }

    @Test
    fun reborrowingSameTitleCreatesNewEntry() = runBlocking {
        val dao = FakeBookDao()
        val client = StubClient(listOf(loan("어린 왕자", dueIn = 2, loanedDaysAgo = 12)))
        val repo = BookRepository(dao, client)

        repo.syncFromSplib(creds)
        repo.markReturned(dao.books.single())

        // Borrowed again today → new loan date
        client.books = listOf(loan("어린 왕자", dueIn = 14, loanedDaysAgo = 0))
        val result = repo.syncFromSplib(creds)

        assertEquals("re-borrow is a new entry", 1, result.imported)
        assertEquals(2, dao.books.size)
        assertEquals(1, dao.books.count { it.returnedAt == null })
    }

    @Test
    fun interlibraryPickupBecomesLoanWithoutDuplicating() = runBlocking {
        val dao = FakeBookDao()
        // Site reports 입수; "도착" is only the user-facing label
        val client = StubClient(listOf(pickup("모모", DooraeStatus.OBTAINED)))
        val repo = BookRepository(dao, client)

        repo.syncFromSplib(creds)
        val pending = dao.books.single()
        assertNull("pickup item has no due date", pending.dueDate)
        assertEquals(PickupStatus.OBTAINED, pending.pickupStatus)

        // Picked up → now a real loan; the pending entry should disappear
        client.books = listOf(loan("모모", dueIn = 14))
        repo.syncFromSplib(creds)

        val active = dao.books.filter { it.returnedAt == null }
        assertEquals("no leftover pending duplicate", 1, active.size)
        assertNotNull("now a real loan", active.single().dueDate)
    }

    @Test
    fun manualEntriesAreNeverTouchedBySync() = runBlocking {
        val dao = FakeBookDao()
        val client = StubClient(emptyList())
        val repo = BookRepository(dao, client)

        val manual = com.pchuri.returnfairy.data.db.BookEntry(
            title = "직접 등록한 책",
            loanDate = TODAY,
            dueDate = TODAY.plusDays(10),
            source = BookSource.MANUAL,
        )
        repo.add(manual)

        val result = repo.syncFromSplib(creds)

        assertEquals(0, result.markedReturned)
        assertEquals(1, dao.books.size)
        assertNull("manual entry untouched", dao.books.single().returnedAt)
    }

    @Test
    fun vanishedLoanIsMarkedReturned() = runBlocking {
        val dao = FakeBookDao()
        val client = StubClient(listOf(loan("사라질 책", dueIn = 3)))
        val repo = BookRepository(dao, client)

        repo.syncFromSplib(creds)
        client.books = emptyList()
        val result = repo.syncFromSplib(creds)

        assertEquals(1, result.markedReturned)
        assertNotNull(dao.books.single().returnedAt)
    }
}
