package com.labelaudit.app.utils

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
