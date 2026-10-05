package com.pchuri.returnfairy.data

import android.app.DownloadManager
import android.content.Context
import java.io.File

/**
 * Removes what 3.x left behind once 4.0 dropped manual entry, photo scanning and the AI model:
 * the Room database of typed-in books, the ~2.4 GB on-device model (and a download still running),
 * the last scanned receipt photo, and the settings only those features used. Runs once.
 */
fun cleanUpLegacyData(context: Context, settings: SettingsStore) {
    if (settings.legacyCleaned) return
    context.deleteDatabase("returnfairy.db")
    val downloadId = settings.removeLegacyKeys()
    if (downloadId != -1L) {
        runCatching { context.getSystemService(DownloadManager::class.java).remove(downloadId) }
    }
    listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { File(it, "llm").deleteRecursively() }
    File(context.cacheDir, "scans").deleteRecursively()
    settings.legacyCleaned = true
}
