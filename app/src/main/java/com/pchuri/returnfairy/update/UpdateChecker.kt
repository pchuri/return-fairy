package com.pchuri.returnfairy.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Checks GitHub Releases for a newer APK. The app is distributed outside the Play Store,
 * so this is the only way users learn about updates. Only the public release metadata is
 * requested; nothing about the user or their books is sent.
 */
object UpdateChecker {
    const val RELEASES_PAGE = "https://github.com/pchuri/return-fairy/releases/latest"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/pchuri/return-fairy/releases/latest"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    fun parseLatestVersion(json: String): String? =
        TAG_NAME.find(json)?.groupValues?.get(1)?.removePrefix("v")

    /** True when [remote] is a higher dotted version than [local]. Unparsable input is never newer. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Offer [latest] if it beats the installed version and the one the user postponed. */
    fun shouldOffer(latest: String, installed: String, dismissed: String): Boolean =
        isNewer(latest, installed) && (dismissed.isEmpty() || isNewer(latest, dismissed))

    private fun parts(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('-')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    suspend fun fetchLatestVersion(client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else parseLatestVersion(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            null
        }
    }
}
