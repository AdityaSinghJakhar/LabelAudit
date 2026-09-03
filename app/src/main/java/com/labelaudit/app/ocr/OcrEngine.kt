package com.labelaudit.app.ocr

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val box: Rect,
    /**
     * ML Kit does not expose a per-line confidence, so this is not a model
     * score. It is filled in downstream from how many captured frames agreed
     * on the same value. Until then it stays null rather than being faked.
     */
    val confidence: Float? = null
)

data class OcrOutput(
    val lines: List<OcrLine>,
    val fullText: String,
    val elapsedMs: Long,
    val script: Script
) {
    enum class Script { LATIN, DEVANAGARI }
}

/**
 * On-device text recognition.
 *
 * Runs entirely on the phone — no server, no network. Both recognisers use
 * bundled models, so the first scan works offline with nothing to download.
 */
object OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val devanagari: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognise text in a captured photo.
     *
     * Indian labels are routinely bilingual, so both recognisers run and the
     * one returning more text wins. Running only the Latin model would drop
     * Hindi declarations silently, which for a compliance tool is worse than
     * the extra processing time.
     */
    suspend fun recognize(context: Context, photo: File): OcrOutput =
        withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            // This overload applies the EXIF orientation, so a portrait
            // capture is not fed to the recogniser sideways.
            val image = InputImage.fromFilePath(context, Uri.fromFile(photo))

            val latinText = latin.await(image)
            val devanagariText = devanagari.await(image)

            val useDevanagari = devanagariText.text.length > latinText.text.length
            val chosen = if (useDevanagari) devanagariText else latinText

            OcrOutput(
                lines = chosen.toLines(),
                fullText = chosen.text,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                script = if (useDevanagari) {
                    OcrOutput.Script.DEVANAGARI
                } else {
                    OcrOutput.Script.LATIN
                }
            )
        }
}

private fun Text.toLines(): List<OcrLine> = textBlocks
    .flatMap { it.lines }
    .mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        OcrLine(text = line.text, box = box)
    }

private suspend fun TextRecognizer.await(image: InputImage): Text =
    suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
