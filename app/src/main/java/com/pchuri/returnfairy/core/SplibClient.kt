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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.Dispatcher
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
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

    /** Shared pool; each account gets its own cookie jar on top of it. */
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // Whole request, like aiohttp total=10 in songpa_core.
        .callTimeout(10, TimeUnit.SECONDS)
        // All accounts at once, like asyncio.gather; OkHttp allows only 5 per host by default.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .build()

    private fun newSession(): Pair<OkHttpClient, MemoryCookieJar> {
        val jar = MemoryCookieJar()
        return baseClient.newBuilder().cookieJar(jar).build() to jar
    }

    private suspend fun login(client: OkHttpClient, jar: MemoryCookieJar, userId: String, password: String) {
        if (password.isEmpty()) throw AuthException(SplibErrorKind.LOGIN_FAILED, "empty password")
        val body = FormBody.Builder().add("userId", userId).add("password", password).build()
        val request = Request.Builder().url(SplibConfig.LOGIN_URL).post(body).build()
        client.newCall(request).await().use { response ->
            // Maintenance and server errors are not a wrong password.
            if (response.code >= 500) throw FetchException("login HTTP ${response.code}")
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
                client.newCall(Request.Builder().url(url).build()).await().use { response ->
                    if (response.code != 200) {
                        // 4xx: the page is gone or moved. 5xx: the server is having trouble.
                        throw FetchException("HTTP ${response.code} from $url", siteChanged = response.code in 400..499)
                    }
                    val text = response.body?.string().orEmpty()
                    if ("<!-- footer -->" in text) return text
                    // Usually a cut-off page that a retry fixes; if every try lacks it, the layout changed.
                    lastError = FetchException("Incomplete response from $url: missing footer", siteChanged = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < FETCH_MAX_RETRIES - 1) delay(1000)
        }
        throw FetchException(
            "Failed to fetch $url after $FETCH_MAX_RETRIES attempts", lastError,
            siteChanged = (lastError as? FetchException)?.siteChanged == true,
        )
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

        assembleAccount(displayLabel(account, index.name), account.userId, loans, doorae, reservations)
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
                } catch (e: IOException) {
                    failed(account, SplibErrorKind.NETWORK)
                } catch (e: FetchException) {
                    failed(account, if (e.siteChanged) SplibErrorKind.SITE_CHANGED else SplibErrorKind.NETWORK)
                } catch (e: Exception) {
                    // Pages arrived but could not be parsed. Shown as an error, never as "offline".
                    failed(account, SplibErrorKind.SITE_CHANGED)
                }
            }
        }.awaitAll()
        Snapshot(LocalDateTime.now(), results)
    }

    private fun failed(account: Account, kind: SplibErrorKind) = AccountStatus(
        label = displayLabel(account, ""), userId = account.userId,
        books = emptyList(), reservations = emptyList(), error = kind,
    )
}

/** The name typed in settings, else the name on the site, else the login ID. */
internal fun displayLabel(account: Account, siteName: String): String =
    account.label.ifBlank { siteName.ifBlank { account.userId } }

/** Runs the call asynchronously so cancelling the coroutine (timeout, restart) cancels the request. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
