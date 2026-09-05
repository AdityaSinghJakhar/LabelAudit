package com.labelguard.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.labelguard.app.pipeline.Box
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A word-level box within a line.
 *
 * Cap height must be measured on the numerals alone: a crop of the whole line
 * "MRP Rs. 45.00" spans lowercase letters too, and measuring that reports a
 * height the statute does not ask about. Element boxes let the measurement be
 * taken on just the price.
 */
data class OcrElement(
    val text: String,
    val box: Box
)

data class OcrLine(
    val text: String,
    val box: Box,
    val elements: List<OcrElement> = emptyList(),
    /**
     * ML Kit does not expose a per-line confidence, so this is not a model
     * score. It is filled in downstream from how many captured frames agreed
     * on the same value. Until then it stays null rather than being faked.
     */
    val confidence: Float? = null
) {
    /** The tightest box around [text] within this line, if one can be found. */
    fun boxFor(text: String): Box? =
        elements.firstOrNull { it.text.contains(text, ignoreCase = true) }?.box
}

data class OcrOutput(
    val lines: List<OcrLine>,
    val fullText: String,
    val elapsedMs: Long
)

/**
 * On-device text recognition.
 *
 * Runs entirely on the phone — no server, no network. The model is bundled
 * in the APK, so the first scan works offline with nothing to download.
 *
 * Only the Devanagari recogniser is used, because it reads Devanagari AND
 * Latin in a single pass. That is measured, not assumed — see
 * OcrScriptCoverageTest, which shows the Latin recogniser returning only
 * "MRP Rs 45.00" from a bilingual image while the Devanagari one reads both.
 * Indian labels are routinely bilingual, so the Latin-only model would drop
 * Hindi declarations silently; running both would just do the same work twice.
 */
object OcrEngine {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /** Recognise text in a captured photo. */
    suspend fun recognize(context: Context, photo: File): OcrOutput =
        // This overload applies the EXIF orientation, so a portrait capture is
        // not fed to the recogniser sideways.
        recognize(InputImage.fromFilePath(context, Uri.fromFile(photo)))

    /**
     * Recognise text in a bitmap. Lets the pipeline be exercised on-device
     * against a known image without needing the camera aimed at a real label.
     */
    suspend fun recognize(bitmap: Bitmap): OcrOutput =
        recognize(InputImage.fromBitmap(bitmap, 0))

    private suspend fun recognize(image: InputImage): OcrOutput =
        withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val text = recognizer.await(image)

            OcrOutput(
                lines = text.toLines(),
                fullText = text.text,
                elapsedMs = (System.nanoTime() - started) / 1_000_000
            )
        }
}

private fun Text.toLines(): List<OcrLine> = textBlocks
    .flatMap { it.lines }
    .mapNotNull { line ->
        val rect = line.boundingBox ?: return@mapNotNull null
        OcrLine(
            text = line.text,
            box = Box(rect.left, rect.top, rect.right, rect.bottom),
            elements = line.elements.mapNotNull { element ->
                val bounds = element.boundingBox ?: return@mapNotNull null
                OcrElement(
                    text = element.text,
                    box = Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
                )
            }
        )
    }

private suspend fun TextRecognizer.await(image: InputImage): Text =
    suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
