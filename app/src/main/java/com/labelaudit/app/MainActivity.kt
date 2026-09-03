package com.labelaudit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelaudit.app.ui.screens.CameraScreen
import com.labelaudit.app.ui.theme.LabelAuditTheme
import com.labelaudit.app.viewmodel.ScanState
import com.labelaudit.app.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabelAuditTheme {
                LabelAuditApp()
            }
        }
    }
}

@Composable
private fun LabelAuditApp(viewModel: ScanViewModel = viewModel()) {
    val scanState by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            CameraScreen(
                onImageCaptured = viewModel::upload,
                modifier = Modifier.padding(innerPadding)
            )

            UploadStatus(
                state = scanState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(innerPadding)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun UploadStatus(state: ScanState, modifier: Modifier = Modifier) {
    val message = when (state) {
        ScanState.Idle -> null
        ScanState.Uploading -> "Reading label…"
        is ScanState.Uploaded -> with(state.result.ocr) {
            if (tokens.isEmpty()) {
                "No text found on the label"
            } else {
                "$fullText\n\n${tokens.size} lines · $processingTimeMs ms"
            }
        }
        is ScanState.Failed -> state.message
    } ?: return

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
