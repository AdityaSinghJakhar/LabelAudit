package com.labelguard.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit

/**
 * Establishes, on real hardware, which scripts each bundled recogniser can
 * actually read. The answer decides whether the app needs one recogniser or
 * two, so it is measured rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class OcrScriptCoverageTest {

    private fun render(vararg lines: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 160 * lines.size + 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 84f
            isAntiAlias = true
        }

        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 40f, 120f + index * 160f, paint)
        }
        return bitmap
    }

    /** Reads the bitmap, then releases it — these are megabytes apiece. */
    private fun read(recognizer: TextRecognizer, bitmap: Bitmap): String {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result: Text = Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS)
            return result.text
        } finally {
            bitmap.recycle()
        }
    }

    // Built once per test class. A `get()` property would construct a new
    // recogniser on every access and leak them across the suite.
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val devanagari: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    @Test
    fun latinRecognizerReadsLatin() {
        val text = read(latin, render("MRP Rs 45.00", "Net Qty 500 g"))
        assertTrue("latin recogniser returned: $text", text.contains("45"))
    }

    @Test
    fun devanagariRecognizerAlsoReadsLatin() {
        // If true, one recogniser covers both scripts and the app does not
        // need to run two passes per frame.
        val text = read(devanagari, render("MRP Rs 45.00", "Net Qty 500 g"))
        assertTrue("devanagari recogniser returned: $text", text.contains("45"))
    }

    @Test
    fun devanagariRecognizerReadsDevanagari() {
        val text = read(devanagari, render("निर्माता", "शुद्ध वजन"))
        assertTrue("devanagari recogniser returned: $text", text.isNotBlank())
    }

    @Test
    fun latinRecognizerOnBilingualLabelLosesDevanagari() {
        // Documents the failure mode that justifies the choice of recogniser.
        // Two identical renders because read() releases the bitmap it is given.
        val latinText = read(latin, render("MRP Rs 45.00", "निर्माता एक्मे"))
        val devanagariText = read(devanagari, render("MRP Rs 45.00", "निर्माता एक्मे"))

        println("LATIN_ON_BILINGUAL=[$latinText]")
        println("DEVANAGARI_ON_BILINGUAL=[$devanagariText]")

        assertTrue("devanagari recogniser returned: $devanagariText",
            devanagariText.contains("45"))
    }
}
