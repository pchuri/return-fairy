package com.pchuri.returnfairy.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiBookReaderLoggingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val modelDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "llm")
    private val testModel = File(modelDirectory, "diagnostic-test.litertlm")
    private val testImage = File(context.cacheDir, "ai-reader-diagnostic.jpg")
    private var testModelCreated = false

    @Before
    fun installInvalidModel() {
        assumeTrue(
            "An AI model is already installed; preserving it and skipping",
            AiModelStore.modelFile(context) == null,
        )

        modelDirectory.mkdirs()
        testModel.writeBytes("BAD!".toByteArray())
        testModelCreated = true
    }

    @After
    fun removeInvalidModel() {
        if (testModelCreated) {
            testModel.delete()
        }
        testImage.delete()
    }

    @Test
    fun logsPreparedImageAndBackendFailures() = runBlocking {
        createRotatedImage(testImage)

        val result = AiBookReader(context).read(Uri.fromFile(testImage))

        assertTrue(result is AiReadResult.Failed)
    }

    private fun createRotatedImage(file: File) {
        val bitmap = Bitmap.createBitmap(3000, 1800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(23, 67, 101))
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
    }
}
