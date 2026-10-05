package com.pchuri.returnfairy.core

import com.pchuri.returnfairy.data.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private val LOGIN_FAILURE_PATTERNS = listOf(
    "비밀번호가 일치하지 않습니다",
    "회원번호가 일치하지 않습니다",
    "존재하지 않는 회원",
    "로그인에 실패",
    "회원정보가 없습니다",
    // What the site actually returns as of 2026-08. A failed login still answers HTTP 200 with a
    // session cookie, so this text is the only way to tell.
    "로그인 정보가 올바르지 않거나",
)

private const val ACCOUNT_TIMEOUT_MS = 30_000L
private const val FETCH_MAX_RETRIES = 5

open class SplibClient {

    /**
     * Per-account cookie jar. The login response sets the session cookie on a 302; without a jar
     * OkHttp follows the redirect without it and the session is never authenticated.
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
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }

        @Synchronized
        fun isEmpty(): Boolean = store.isEmpty()
    }

    private fun newSession(): Pair<OkHttpClient, MemoryCookieJar> {
        val jar = MemoryCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return client to jar
    }

    private fun login(client: OkHttpClient, jar: MemoryCookieJar, userId: String, password: String) {
        if (password.isEmpty()) throw AuthException(SplibErrorKind.LOGIN_FAILED, "empty password")
        val body = FormBody.Builder().add("userId", userId).add("password", password).build()
        val request = Request.Builder().url(SplibConfig.LOGIN_URL).post(body).build()
        client.newCall(request).execute().use { response ->
            if (response.code >= 400) throw AuthException(SplibErrorKind.LOGIN_FAILED, "login HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            if (LOGIN_FAILURE_PATTERNS.any { it in text }) throw AuthException(SplibErrorKind.LOGIN_FAILED, "login rejected")
            if (jar.isEmpty()) throw AuthException(SplibErrorKind.LOGIN_FAILED, "no session cookie")
        }
    }

    /** GET with retries; a page without the footer marker was cut off and is retried too. */
    private suspend fun fetch(client: OkHttpClient, url: String): String {
        var lastError: Exception? = null
        repeat(FETCH_MAX_RETRIES) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code != 200) throw FetchException("HTTP ${response.code} from $url")
                    val text = response.body?.string().orEmpty()
                    if ("<!-- footer -->" in text) return text
                    lastError = FetchException("Incomplete response from $url: missing footer")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < FETCH_MAX_RETRIES - 1) delay(1000)
        }
        throw FetchException("Failed to fetch $url after $FETCH_MAX_RETRIES attempts", lastError)
    }

    private suspend fun fetchAccount(account: Account): AccountStatus = withContext(Dispatchers.IO) {
        val (client, jar) = newSession()
        login(client, jar, account.userId, account.password)
        val index = SplibParsers.parseIndexContent(fetch(client, SplibConfig.INDEX_URL))

        val loans = SplibParsers.parseLoanStatus(fetch(client, SplibConfig.LOAN_URL)) +
            SplibParsers.parseLoanStatus(fetch(client, "${SplibConfig.LOAN_URL}?currentPageNo=2"))
        val doorae = SplibParsers.parseDooraeStatus(fetch(client, SplibConfig.INTERLIBRARY_LOAN_URL)).first +
            SplibParsers.parseDooraeStatus(fetch(client, "${SplibConfig.INTERLIBRARY_LOAN_URL}?currentPageNo=2")).first

        val firstReservations = fetch(client, SplibConfig.RESERVATION_URL)
        val reservations = SplibParsers.parseReservationStatus(firstReservations).toMutableList()
        for (page in 2..SplibParsers.parseMaxPage(firstReservations)) {
            reservations += SplibParsers.parseReservationStatus(fetch(client, "${SplibConfig.RESERVATION_URL}?currentPageNo=$page"))
        }

        assembleAccount(account.label.ifBlank { index.name }, loans, doorae, reservations)
    }

    /** Fetches every account in parallel. A failing account becomes an error entry; the rest still show. */
    open suspend fun fetchAll(accounts: List<Account>): Snapshot = coroutineScope {
        val results = accounts.map { account ->
            async {
                try {
                    withTimeout(ACCOUNT_TIMEOUT_MS) { fetchAccount(account) }
                } catch (e: TimeoutCancellationException) {
                    failed(account, SplibErrorKind.TIMEOUT)
                } catch (e: AuthException) {
                    failed(account, e.kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(account, SplibErrorKind.NETWORK)
                }
            }
        }.awaitAll()
        Snapshot(LocalDateTime.now(), results)
    }

    private fun failed(account: Account, kind: SplibErrorKind) =
        AccountStatus(label = account.label.ifBlank { account.userId }, books = emptyList(), reservations = emptyList(), error = kind)
}
