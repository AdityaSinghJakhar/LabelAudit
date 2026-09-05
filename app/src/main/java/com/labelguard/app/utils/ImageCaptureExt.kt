package com.labelguard.app.utils

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a photo into the app's cache directory and returns the file.
 * Wraps CameraX's callback API so callers can just suspend.
 */
/**
 * Capture a burst of frames of the same package.
 *
 * Consensus needs several independent readings; a short gap between shots
 * lets autofocus and hand tremor vary slightly, so a misread in one frame is
 * unlikely to repeat identically in the others.
 */
suspend fun ImageCapture.captureBurst(
    context: Context,
    count: Int,
    gapMillis: Long = 250,
    onProgress: (Int) -> Unit = {}
): List<File> {
    val frames = mutableListOf<File>()
    repeat(count) { index ->
        onProgress(index + 1)
        frames += captureToCache(context)
        if (index < count - 1) kotlinx.coroutines.delay(gapMillis)
    }
    return frames
}

suspend fun ImageCapture.captureToCache(context: Context): File =
    suspendCancellableCoroutine { continuation ->
        val file = File.createTempFile("scan_", ".jpg", context.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    continuation.resume(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    continuation.resumeWithException(exception)
                }
            }
        )
    }
