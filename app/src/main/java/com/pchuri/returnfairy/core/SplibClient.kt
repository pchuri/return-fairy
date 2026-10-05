package com.pchuri.returnfairy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val LOGIN_FAILURE_PATTERNS = listOf(
    "비밀번호가 일치하지 않습니다",
    "회원번호가 일치하지 않습니다",
    "존재하지 않는 회원",
    "로그인에 실패",
    "회원정보가 없습니다",
)

private const val USER_FETCH_TIMEOUT_MS = 30_000L
private const val FETCH_MAX_RETRIES = 5

open class SplibClient {

    /**
     * In-memory cookie jar. Required because the login endpoint returns the
     * session cookie on a 302 response; without a jar OkHttp follows the
     * redirect WITHOUT the cookie and the session is never authenticated.
     */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                store.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.filter { it.matches(url) }

        @Synchronized
        fun isEmpty(): Boolean = store.isEmpty()
    }

    private fun buildClient(): Pair<OkHttpClient, MemoryCookieJar> {
        val jar = MemoryCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return client to jar
    }

    private fun login(client: OkHttpClient, jar: MemoryCookieJar, userId: String, password: String) {
        val body = FormBody.Builder()
            .add("userId", userId)
            .add("password", password)
            .build()
        val request = Request.Builder().url(SplibConfig.LOGIN_URL).post(body).build()

        client.newCall(request).execute().use { response ->
            if (response.code >= 400) {
                throw AuthException(SplibErrorKind.LOGIN_FAILED, "login HTTP ${response.code}")
            }
            val text = response.body?.string() ?: ""
            if (LOGIN_FAILURE_PATTERNS.any { it in text }) {
                throw AuthException(SplibErrorKind.LOGIN_FAILED, "login rejected")
            }
            if (jar.isEmpty()) {
                throw AuthException(SplibErrorKind.LOGIN_FAILED, "no session cookie")
            }
        }
    }

    private suspend fun fetch(client: OkHttpClient, url: String): String {
        var lastError: Exception? = null
        repeat(FETCH_MAX_RETRIES) { attempt ->
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) {
                        throw FetchException("HTTP ${response.code} from $url")
                    }
                    val text = response.body?.string() ?: ""
                    if ("<!-- footer -->" in text) return text
                    lastError = FetchException("Incomplete response from $url: missing footer")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
            if (attempt < FETCH_MAX_RETRIES - 1) delay(1000)
        }
        throw FetchException("Failed to fetch $url after $FETCH_MAX_RETRIES attempts", lastError)
    }

    private suspend fun fetchUserInfo(userId: String, password: String): UserInfo = withContext(Dispatchers.IO) {
        val (client, jar) = buildClient()
        login(client, jar, userId, password)

        val indexInfo = SplibParsers.parseIndexContent(fetch(client, SplibConfig.INDEX_URL))

        val loanBooks = SplibParsers.parseLoanStatus(fetch(client, SplibConfig.LOAN_URL)) +
            SplibParsers.parseLoanStatus(fetch(client, "${SplibConfig.LOAN_URL}?currentPageNo=2"))

        val (doorae1, returning1) = SplibParsers.parseDooraeStatus(
            fetch(client, SplibConfig.INTERLIBRARY_LOAN_URL)
        )
        val (doorae2, returning2) = SplibParsers.parseDooraeStatus(
            fetch(client, "${SplibConfig.INTERLIBRARY_LOAN_URL}?currentPageNo=2")
        )
        val dooraeBooks = doorae1 + doorae2
        val interlibraryLoansToReturn = returning1 + returning2

        assembleUserInfo(indexInfo, loanBooks, dooraeBooks, interlibraryLoansToReturn)
    }

    open suspend fun getInfos(credentials: List<Pair<String, String>>): List<UserInfo> = coroutineScope {
        credentials.map { (userId, password) ->
            async {
                try {
                    withTimeout(USER_FETCH_TIMEOUT_MS) { fetchUserInfo(userId, password) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    errorInfo(userId, SplibErrorKind.TIMEOUT)
                } catch (e: AuthException) {
                    errorInfo(userId, e.kind)
                } catch (e: Exception) {
                    errorInfo(userId, SplibErrorKind.NETWORK)
                }
            }
        }.map { it.await() }
    }

    private fun errorInfo(userId: String, kind: SplibErrorKind) = UserInfo(
        name = userId,
        books = emptyList(),
        totalBorrowedBooks = 0,
        activeInterlibraryLoans = 0,
        pendingInterlibraryPickups = 0,
        error = kind,
    )
}

/** Mirrors the combination logic of desktop core/splib.py `_fetch_user_info`. */
internal fun assembleUserInfo(
    indexInfo: IndexInfo,
    loanBooks: List<LoanBook>,
    dooraeBooks: Map<String, DooraeBook>,
    interlibraryLoansToReturn: Int,
): UserInfo {
    val sorted = loanBooks.sortedBy { it.dueDate }
    val booksoleCount = sorted.count { it.isBooksole }
    val loanTitles = loanBooks.map { it.title }.toSet()

    val books = sorted.map { book ->
        val doorae = dooraeBooks[book.title]
        val isInterlibrary = book.isBooksole || doorae != null
        Book(
            title = book.title,
            dueDate = book.dueDate,
            isInterlibrary = isInterlibrary,
            library = if (isInterlibrary) (doorae?.receivingLibrary ?: "") else book.library,
            loanDate = book.loanDate,
        )
    }.toMutableList()

    // Interlibrary requests not yet in the loan list (요청중/발송/입수 상태)
    dooraeBooks.filterKeys { it !in loanTitles }.forEach { (title, doorae) ->
        books.add(
            Book(
                title = title,
                dueDate = doorae.status,
                isInterlibrary = true,
                library = doorae.receivingLibrary ?: "",
            )
        )
    }

    val activeInterlibraryLoans = indexInfo.interlibraryLoans - interlibraryLoansToReturn
    val pendingInterlibraryPickups = activeInterlibraryLoans - booksoleCount

    return UserInfo(
        name = indexInfo.name,
        books = books,
        totalBorrowedBooks = indexInfo.totalBorrowedBooks,
        activeInterlibraryLoans = activeInterlibraryLoans,
        pendingInterlibraryPickups = pendingInterlibraryPickups,
    )
}

