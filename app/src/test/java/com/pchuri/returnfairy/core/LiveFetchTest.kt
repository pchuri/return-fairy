package com.pchuri.returnfairy.core

import com.pchuri.returnfairy.data.Account
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real lookup against splib.or.kr. Runs only with RETURNFAIRY_TEST_USERID / RETURNFAIRY_TEST_PASSWORD
 * set, from a network the site accepts (it blocks cloud IPs, so CI skips this).
 */
class LiveFetchTest {
    @Test
    fun fetchesOneAccount() {
        val userId = System.getenv("RETURNFAIRY_TEST_USERID")
        val password = System.getenv("RETURNFAIRY_TEST_PASSWORD")
        assumeTrue("RETURNFAIRY_TEST_USERID/PASSWORD not set — skipping live test", userId != null && password != null)

        val snapshot = runBlocking { SplibClient().fetchAll(listOf(Account(userId!!, password!!))) }
        val account = snapshot.accounts.single()
        assertNull("lookup failed: ${account.error}", account.error)
        println("books=${account.books.size} interlibrary=${account.interlibraryCount} reservations=${account.reservations.size}")
    }
}
