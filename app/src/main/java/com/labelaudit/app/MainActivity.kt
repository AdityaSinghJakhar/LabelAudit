package com.labelaudit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.labelaudit.app.ui.screens.CameraScreen
import com.labelaudit.app.ui.theme.LabelAuditTheme
import kotlinx.coroutines.launch

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
private fun LabelAuditApp() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        CameraScreen(
            onImageCaptured = { file ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Captured ${file.name} (${file.length() / 1024} KB)"
                    )
                }
            },
            modifier = Modifier.padding(innerPadding)
        )
    }
}
