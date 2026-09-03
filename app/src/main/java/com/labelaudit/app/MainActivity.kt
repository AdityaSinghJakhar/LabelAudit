package com.labelaudit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                onImageCaptured = viewModel::scan,
                modifier = Modifier.padding(innerPadding)
            )

            ScanStatus(
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
private fun ScanStatus(state: ScanState, modifier: Modifier = Modifier) {
    val message = when (state) {
        ScanState.Idle -> null
        ScanState.Reading -> "Reading label…"
        is ScanState.Read -> with(state.result) {
            if (lines.isEmpty()) {
                "No text found on the label"
            } else {
                "$fullText\n\n${lines.size} lines · ${elapsedMs} ms · ${script.name.lowercase()}"
            }
        }
        is ScanState.Failed -> state.message
    } ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                // A dense label can produce a lot of text; cap the panel and
                // let it scroll rather than covering the viewfinder.
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
