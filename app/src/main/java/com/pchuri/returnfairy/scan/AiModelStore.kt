package com.pchuri.returnfairy.scan

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages the model file for the experimental on-device AI photo recognition.
 *
 * The default path is a one-tap download of a vision-capable Gemma bundle from
 * a public, unauthenticated Hugging Face URL — the same source the Google AI
 * Edge Gallery app uses — via the system [DownloadManager], so it survives the
 * app being closed. A manual file import stays available as a fallback. Either
 * way the app ships no API keys and no multi-gigabyte payload; only people who
 * opt in pay the storage cost.
 */
object AiModelStore {

    /**
     * Gemma 4 E2B (vision, int4, ~2.4 GB) from the ungated litert-community
     * repo — verified to require no login, unlike the google/gemma-3n repos.
     */
    const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"
    const val MODEL_SIZE_BYTES = 2_588_147_712L

    private const val DIR_NAME = "llm"
    private val SUPPORTED_EXTENSIONS = setOf("task", "litertlm")

    fun modelFile(context: Context): File? = dir(context)
        .listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS }
        ?.maxByOrNull(File::length)

    fun delete(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    // ---- one-tap download -------------------------------------------------

    sealed interface DownloadState {
        data class Running(val progress: Float) : DownloadState
        data object Succeeded : DownloadState
        data class Failed(val reason: Int) : DownloadState
        /** Unknown id — cancelled by the user or cleared by the system. */
        data object Gone : DownloadState
    }

    /** Enqueues the model download; returns the DownloadManager id. */
    fun startDownload(context: Context): Long {
        val free = dir(context).usableSpace
        if (MODEL_SIZE_BYTES > free) {
            throw IOException("Not enough storage: need ${MODEL_SIZE_BYTES / (1 shl 20)} MB")
        }
        // Downloaded as .part so a half-written file is never mistaken for an
        // installed model; renamed into place once DownloadManager says done.
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle(MODEL_FILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "$DIR_NAME/$MODEL_FILE.part")
        File(dir(context), "$MODEL_FILE.part").delete()
        return downloadManager(context).enqueue(request)
    }

    fun downloadState(context: Context, id: Long): DownloadState =
        downloadManager(context).query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return DownloadState.Gone
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Succeeded
                DownloadManager.STATUS_FAILED -> DownloadState.Failed(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                )
                else -> {
                    val done = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ).takeIf { it > 0 } ?: MODEL_SIZE_BYTES
                    DownloadState.Running(done.toFloat() / total)
                }
            }
        } ?: DownloadState.Gone

    /** Moves a completed .part download into place. True if a model is now installed. */
    fun finalizeDownload(context: Context): Boolean {
        val part = File(dir(context), "$MODEL_FILE.part")
        if (!part.isFile) return modelFile(context) != null
        dir(context).listFiles()?.filter { it != part }?.forEach { it.delete() }
        return part.renameTo(File(dir(context), MODEL_FILE))
    }

    fun cancelDownload(context: Context, id: Long) {
        downloadManager(context).remove(id)
        File(dir(context), "$MODEL_FILE.part").delete()
    }

    private fun downloadManager(context: Context): DownloadManager =
        context.getSystemService(DownloadManager::class.java)

    // ---- manual import (fallback) -----------------------------------------

    /**
     * Copies a picked document into app storage — the engine needs a plain
     * file path, which SAF URIs don't provide. [onProgress] gets 0..1, or -1
     * when the total size is unknown. A successful import replaces whatever
     * model was there.
     */
    suspend fun import(context: Context, uri: Uri, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val name = displayName(context, uri) ?: MODEL_FILE
            if (File(name).extension.lowercase() !in SUPPORTED_EXTENSIONS) {
                throw IOException("Not a .task/.litertlm model file: $name")
            }
            val total = size(context, uri)
            val free = dir(context).usableSpace
            if (total > 0 && total > free) {
                throw IOException("Not enough storage: need ${total / (1 shl 20)} MB")
            }
            val tmp = File(dir(context), "$name.import")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IOException("Cannot open the selected file")
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(if (total > 0) copied.toFloat() / total else -1f)
                        }
                    }
                }
                dir(context).listFiles()?.filter { it != tmp }?.forEach { it.delete() }
                val target = File(dir(context), name)
                if (!tmp.renameTo(target)) throw IOException("Could not move the model into place")
                target
            } finally {
                tmp.delete()
            }
        }

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun size(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
}
