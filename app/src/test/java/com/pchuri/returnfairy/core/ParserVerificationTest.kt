package com.pchuri.returnfairy.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the Kotlin parsers against fixtures captured from the real library
 * site by the original Python implementation (desktop core/splib.py).
 *
 * Fixtures contain personal data, so they are NOT committed to the repo.
 * Set RETURNFAIRY_FIXTURE_DIR to a directory containing:
 *   index.html, loan1.html, loan2.html, doorae1.html, doorae2.html, expected.json
 * The test is skipped when the variable is not set.
 */
class ParserVerificationTest {

    @Test
    fun matchesPythonImplementation() {
        val dir = System.getenv("RETURNFAIRY_FIXTURE_DIR")
        assumeTrue("RETURNFAIRY_FIXTURE_DIR not set — skipping fixture verification", dir != null)
        val fixtures = File(dir!!)

        fun read(name: String) = File(fixtures, name).readText()

        val indexInfo = SplibParsers.parseIndexContent(read("index.html"))
        val loanBooks = SplibParsers.parseLoanStatus(read("loan1.html")) +
            SplibParsers.parseLoanStatus(read("loan2.html"))
        val (doorae1, returning1) = SplibParsers.parseDooraeStatus(read("doorae1.html"))
        val (doorae2, returning2) = SplibParsers.parseDooraeStatus(read("doorae2.html"))

        val actual = assembleUserInfo(indexInfo, loanBooks, doorae1 + doorae2, returning1 + returning2)

        val expected = JSONObject(read("expected.json"))
        assertEquals(expected.getString("name"), actual.name)
        assertEquals(expected.getInt("total_borrowed_books"), actual.totalBorrowedBooks)
        assertEquals(expected.getInt("active_interlibrary_loans"), actual.activeInterlibraryLoans)
        assertEquals(expected.getInt("pending_interlibrary_pickups"), actual.pendingInterlibraryPickups)

        val expectedBooks = expected.getJSONArray("books")
        assertEquals(expectedBooks.length(), actual.books.size)
        for (i in 0 until expectedBooks.length()) {
            val e = expectedBooks.getJSONObject(i)
            val a = actual.books[i]
            assertEquals("title[$i]", e.getString("title"), a.title)
            assertEquals("due_date[$i]", e.getString("due_date"), a.dueDate)
            assertEquals("is_interlibrary[$i]", e.getBoolean("is_interlibrary"), a.isInterlibrary)
            assertEquals("library[$i]", e.optString("library", ""), a.library)
        }
    }
}
