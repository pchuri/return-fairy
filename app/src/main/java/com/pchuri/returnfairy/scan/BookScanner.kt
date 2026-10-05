package com.pchuri.returnfairy.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ScanMode { RECEIPT, COVER }

sealed interface ScanOutcome {
    /** [candidateLines] lets the user refine a guessed title by tapping text. */
    data class Success(
        val books: List<ScannedBook>,
        val candidateLines: List<String> = emptyList(),
    ) : ScanOutcome
    /** Text was read but not confidently understood — let the user pick. */
    data class NeedsPicking(val lines: List<String>, val suggestedDate: java.time.LocalDate?) : ScanOutcome
    data object NothingFound : ScanOutcome
    /** Play Services is still fetching the recognizer model (first scan only). */
    data object ModelDownloading : ScanOutcome
    data class Failed(val cause: Throwable) : ScanOutcome
}

private const val MODEL_WAIT_ATTEMPTS = 6
private const val MODEL_WAIT_DELAY_MS = 2000L

/**
 * On-device OCR via ML Kit. Uses the unbundled (Play Services) recognizer, so
 * the model is downloaded by Google Play Services the first time a user scans
 * — the app itself carries no model and users who never scan download nothing.
 * Nothing is sent off the device.
 */
class BookScanner(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** Most of the world writes the day before the month; the US does not. */
    private fun dayFirst(): Boolean =
        java.util.Locale.getDefault().country != "US"

    /**
     * Ask Play Services to start fetching the recognizer model without waiting
     * for a result. Called when the user opens the add menu so the download
     * overlaps with taking the photo — by the time they shoot, it is usually
     * ready. Safe to call repeatedly; a no-op once the model is installed.
     */
    fun prewarm() {
        runCatching {
            val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            recognizer.process(InputImage.fromBitmap(blank, 0))
        }
    }

    suspend fun scan(uri: Uri, mode: ScanMode): ScanOutcome {
        return try {
            val image = InputImage.fromFilePath(context, uri)
            val result = recognizeWaitingForModel(image)
                ?: return ScanOutcome.ModelDownloading

            val reading = when (mode) {
                ScanMode.RECEIPT -> ScanParser.readReceipt(
                    result.textBlocks.flatMap { block -> block.lines.map { it.text } },
                    dayFirst = dayFirst(),
                )
                ScanMode.COVER -> ScanParser.readCover(
                    result.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            ScanLine(
                                text = line.text,
                                height = line.boundingBox?.height() ?: 0,
                                top = line.boundingBox?.top ?: 0,
                            )
                        }
                    }
                )
            }

            when {
                reading.books.isNotEmpty() ->
                    ScanOutcome.Success(reading.books, reading.candidateLines)
                reading.candidateLines.isNotEmpty() ->
                    ScanOutcome.NeedsPicking(reading.candidateLines, reading.suggestedDate)
                else -> ScanOutcome.NothingFound
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ScanOutcome.Failed(e)
        }
    }

    /**
     * The recognizer model is fetched by Play Services on first use, so the very
     * first scan can fail with "waiting for the text optional module". Retry for
     * a short while instead of surfacing that as an error; returns null if the
     * download is still not finished.
     */
    private suspend fun recognizeWaitingForModel(image: InputImage): Text? {
        repeat(MODEL_WAIT_ATTEMPTS) { attempt ->
            try {
                return suspendCancellableCoroutine { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            } catch (e: MlKitException) {
                val downloading = e.message?.contains("module to be downloaded", ignoreCase = true) == true ||
                    e.errorCode == MlKitException.UNAVAILABLE
                if (!downloading) throw e
                if (attempt < MODEL_WAIT_ATTEMPTS - 1) delay(MODEL_WAIT_DELAY_MS)
            }
        }
        return null
    }
}
