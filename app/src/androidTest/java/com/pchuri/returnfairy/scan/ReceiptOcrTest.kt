package com.pchuri.returnfairy.scan

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs real ML Kit OCR on a sample Korean library receipt and checks that the
 * parser turns it into the expected books. Requires a device/emulator with
 * Google Play Services; the recognizer model is downloaded on first use, so the
 * test waits for it the same way the app does.
 */
class ReceiptOcrTest {

    @Test
    fun readsTitlesAndDueDatesFromReceiptImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("receipt_sample.png").use {
            BitmapFactory.decodeStream(it)
        }
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

        var text: com.google.mlkit.vision.text.Text? = null
        repeat(30) {
            try {
                text = suspendCancellableCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { r -> cont.resume(r) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
                return@repeat
            } catch (e: MlKitException) {
                delay(2000) // model still downloading
            }
        }
        // Emulators often cannot fetch the Korean OCR module; skip rather than
        // fail so this stays a real-device check.
        org.junit.Assume.assumeTrue(
            "Korean recognizer model unavailable on this device — skipping",
            text != null,
        )
        val recognized = text!!

        val lines = recognized.textBlocks.flatMap { block -> block.lines.map { it.text } }
        android.util.Log.i("ReceiptOcrTest", "OCR lines: $lines")

        val books = ScanParser.parseReceipt(lines)
        android.util.Log.i("ReceiptOcrTest", "parsed: $books")

        val due = LocalDate.of(2026, 8, 15)
        assertEquals("should find exactly the three borrowed books", 3, books.size)
        assertTrue("all books get the printed due date", books.all { it.dueDate == due })

        val titles = books.map { it.title }
        listOf("어린 왕자", "영원한 제국", "모모").forEach { expected ->
            assertTrue("missing '$expected' in $titles", titles.any { it.contains(expected) })
        }
    }
}
