package com.pchuri.returnfairy.data

import com.pchuri.returnfairy.data.db.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end test of the Songpa sync merge (loans + interlibrary items)
 * against the real site with an in-memory DAO. Requires a network path that
 * splib.or.kr accepts. Set RETURNFAIRY_TEST_USERID / RETURNFAIRY_TEST_PASSWORD
 * to run; skipped otherwise (e.g. on CI).
 */
class LiveSyncTest {

    @Test
    fun syncImportsLoansAndIsIdempotent() {
        val userId = System.getenv("RETURNFAIRY_TEST_USERID")
        val password = System.getenv("RETURNFAIRY_TEST_PASSWORD")
        assumeTrue("RETURNFAIRY_TEST_USERID/PASSWORD not set — skipping live test", userId != null && password != null)

        val dao = FakeBookDao()
        val repository = BookRepository(dao)

        val first = runBlocking { repository.syncFromSplib(listOf(userId!! to password!!)) }
        assertTrue("sync should succeed but got errors: ${first.errors}", first.errors.isEmpty())
        assertEquals("all imported entries should be in the store", first.imported, dao.books.size)
        dao.books.forEach {
            println("LIVE_SYNC_ENTRY: title=${it.title} due=${it.dueDate} pickup=${it.pickupStatus} inter=${it.isInterlibrary} lib=${it.library}")
        }
        dao.books.forEach { book ->
            assertTrue(
                "entry must have either a due date or a pickup status: $book",
                (book.dueDate != null) xor (book.pickupStatus != null),
            )
            assertEquals(BookSource.SPLIB, book.source)
        }

        // Second sync right away must change nothing
        val second = runBlocking { repository.syncFromSplib(listOf(userId!! to password!!)) }
        assertTrue(second.errors.isEmpty())
        assertEquals("second sync should import nothing", 0, second.imported)
        assertEquals("second sync should update nothing", 0, second.updated)
        assertEquals("second sync should return nothing", 0, second.markedReturned)
    }
}
