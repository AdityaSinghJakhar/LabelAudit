package com.labelguard.app.ui.components

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX preview surface. The [imageCapture] use case is owned by the caller
 * so it can trigger captures while this composable manages the binding.
 */
@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier,
    torchOn: Boolean = false,
    onTorchAvailable: (Boolean) -> Unit = {},
    /**
     * Fired once the camera binds. Sensor size and focus-distance calibration
     * are only readable from a bound CameraInfo, and without them character
     * height cannot be measured at all.
     */
    onCameraBound: (androidx.camera.core.CameraInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Torch state is applied whenever it changes, and re-applied after a
    // rebind, so toggling before the camera is ready is not lost.
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(imageCapture, lifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await(context)

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture
        )
        // Some devices have no flash unit; the control must be hidden rather
        // than shown as a button that silently does nothing.
        onTorchAvailable(camera?.cameraInfo?.hasFlashUnit() == true)
        camera?.cameraInfo?.let(onCameraBound)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Bridges CameraX's ListenableFuture API into a suspending call. */
private suspend fun <T> ListenableFuture<T>.await(context: android.content.Context): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
