package com.labelaudit.app.ui.screens

import android.Manifest
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.labelaudit.app.ui.components.CameraPreview
import com.labelaudit.app.utils.captureToCache
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live camera preview with a capture button. Handing the captured file off to
 * the backend comes next; for now the caller just receives the file.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onImageCaptured: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    if (cameraPermission.status.isGranted) {
        CameraContent(onImageCaptured = onImageCaptured, modifier = modifier)
    } else {
        PermissionRationale(
            onRequest = cameraPermission::launchPermissionRequest,
            modifier = modifier
        )
    }
}

@Composable
private fun CameraContent(
    onImageCaptured: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            imageCapture = imageCapture,
            modifier = Modifier.fillMaxSize()
        )

        Button(
            onClick = {
                scope.launch {
                    runCatching { imageCapture.captureToCache(context) }
                        .onSuccess(onImageCaptured)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            Text("Scan label")
        }
    }
}

@Composable
private fun PermissionRationale(
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Camera access is needed to photograph product labels.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Grant camera access")
        }
    }
}
