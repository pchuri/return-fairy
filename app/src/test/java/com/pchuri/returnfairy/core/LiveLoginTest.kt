package com.pchuri.returnfairy.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end test against the real library site (login → fetch → parse).
 * Requires a network path that splib.or.kr accepts (residential IP; datacenter
 * IPs are blocked). Set RETURNFAIRY_TEST_USERID / RETURNFAIRY_TEST_PASSWORD to run;
 * skipped otherwise (e.g. on CI).
 */
class LiveLoginTest {

    @Test
    fun loginAndFetchWithRealAccount() {
        val userId = System.getenv("RETURNFAIRY_TEST_USERID")
        val password = System.getenv("RETURNFAIRY_TEST_PASSWORD")
        assumeTrue("RETURNFAIRY_TEST_USERID/PASSWORD not set — skipping live test", userId != null && password != null)

        val infos = runBlocking { SplibClient().getInfos(listOf(userId!! to password!!)) }

        val info = infos.single()
        assertNull("fetch should succeed but got error: ${info.error}", info.error)
        assertTrue("user name should be parsed", info.name.isNotBlank())
        assertTrue("book count should be consistent", info.books.size >= info.totalBorrowedBooks)
    }
}
