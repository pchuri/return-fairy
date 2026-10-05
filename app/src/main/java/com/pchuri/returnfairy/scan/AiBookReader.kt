package com.pchuri.returnfairy.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface AiReadResult {
    data class Success(val books: List<ScannedBook>) : AiReadResult
    data object NoModel : AiReadResult
    data object NothingFound : AiReadResult
    data class Failed(val cause: Throwable) : AiReadResult
}

/**
 * Experimental: reads book titles (and a printed due date, if any) from a
 * photo with an on-device multimodal LLM. Handles what the OCR parsers cannot
 * — several books in one shot, cluttered backgrounds, stylized covers.
 *
 * Uses LiteRT-LM, the same engine and Gemma model the Google AI Edge Gallery
 * app runs. Everything happens locally; the photo never leaves the device.
 * Needs a high-end phone — Google targets Pixel 8 / Galaxy S23 or newer.
 *
 * The engine is created per call and closed right after: loading costs a few
 * seconds, but holding a ~2.4 GB model resident between scans would risk the
 * whole app being killed for memory.
 */
class AiBookReader(private val context: Context) {

    suspend fun read(uri: Uri): AiReadResult = withContext(Dispatchers.IO) {
        val model = AiModelStore.modelFile(context) ?: return@withContext AiReadResult.NoModel
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "AI recognition started: model=${model.name}, modelBytes=${model.length()}")
        try {
            val bitmap = loadUprightBitmap(uri)
                ?: throw IllegalStateException("could not decode the photo")
            val png = ByteArrayOutputStream().let { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            Log.i(
                TAG,
                "AI image prepared: width=${bitmap.width}, height=${bitmap.height}, pngBytes=${png.size}",
            )
            // GPU first (what Gallery defaults to for vision models); some
            // devices lack the GPU delegate, so retry once on CPU.
            val text = try {
                measureInferenceAttempt("GPU") {
                    askModel(model.absolutePath, png, gpu = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "GPU inference failed; retrying with CPU backend", e)
                measureInferenceAttempt("CPU") {
                    askModel(model.absolutePath, png, gpu = false)
                }
            }
            val books = AiResponseParser.parse(text)
            Log.i(
                TAG,
                "AI recognition completed in ${SystemClock.elapsedRealtime() - recognitionStartedAt} ms: " +
                    "resultCount=${books.size}",
            )
            if (books.isEmpty()) AiReadResult.NothingFound else AiReadResult.Success(books)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Native engine + ~2.4 GB model: OutOfMemoryError and link errors
            // are as likely as ordinary exceptions, so catch Throwable.
            Log.e(
                TAG,
                "AI recognition failed after ${SystemClock.elapsedRealtime() - recognitionStartedAt} ms",
                e,
            )
            AiReadResult.Failed(e)
        }
    }

    private inline fun <T> measureInferenceAttempt(backend: String, inference: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return inference()
        } finally {
            Log.i(
                TAG,
                "$backend inference attempt finished in ${SystemClock.elapsedRealtime() - startedAt} ms",
            )
        }
    }

    private fun askModel(modelPath: String, png: ByteArray, gpu: Boolean): String {
        var engine: Engine? = null
        try {
            engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = if (gpu) Backend.GPU() else Backend.CPU(),
                    // The CPU retry exists for devices whose GPU delegate is
                    // broken, so it must not keep vision on the GPU — that
                    // made the fallback fail identically (seen on S24 Ultra
                    // with LiteRT-LM 0.11.0's missing OpenCL path).
                    visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
                    maxNumTokens = MAX_TOKENS,
                )
            )
            engine.initialize()
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.1),
                )
            ).use { conversation ->
                val reply = conversation.sendMessage(
                    Contents.of(Content.ImageBytes(png), Content.Text(PROMPT))
                )
                return reply.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                    .ifBlank { reply.toString() }
            }
        } finally {
            runCatching { engine?.close() }
        }
    }

    /** Decodes the photo downscaled and rotated upright per its EXIF tag. */
    private fun loadUprightBitmap(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val hasValidBounds = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } ?: false
        if (!hasValidBounds) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val TAG = "AiBookReader"
        const val MAX_TOKENS = 2048
        const val TARGET_SIDE = 1280

        val PROMPT = """
            Look at this photo. It may show one or more books (covers, spines, a stack
            of books) or a library checkout receipt.
            List every distinct book you can actually see or read in the photo.
            Answer with ONLY a JSON array and nothing else. One element per book:
            {"title": "<the book's title exactly as printed, in its original language>",
             "due": "<return due date as YYYY-MM-DD if printed in the photo, otherwise null>"}
            Do not invent books that are not visible. If there is no book, answer [].
        """.trimIndent()
    }
}
