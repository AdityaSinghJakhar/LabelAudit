package com.labelaudit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.labelaudit.app.ui.theme.LabelGuardTheme
import com.labelaudit.app.viewmodel.ConnectionState
import com.labelaudit.app.viewmodel.ConnectionViewModel

/**
 * Phase 0 screen: proves the app can reach the backend before any
 * camera or scanning work is layered on top.
 */
@Composable
fun ConnectionScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectionViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ConnectionContent(
        state = state,
        onCheck = viewModel::checkConnection,
        onContinue = onContinue,
        modifier = modifier
    )
}

@Composable
private fun ConnectionContent(
    state: ConnectionState,
    onCheck: () -> Unit,
    onContinue: () -> Unit,
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
            text = "Label Guard",
            style = MaterialTheme.typography.headlineMedium
        )

        Column(
            modifier = Modifier.padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                ConnectionState.Idle -> Text(
                    text = "Not connected yet.",
                    style = MaterialTheme.typography.bodyLarge
                )

                ConnectionState.Checking -> CircularProgressIndicator()

                is ConnectionState.Connected -> {
                    Text(
                        text = "Backend reachable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "API version ${state.version}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = state.serverTime,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is ConnectionState.Failed -> {
                    Text(
                        text = "Backend unreachable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Button(
            onClick = onCheck,
            enabled = state !is ConnectionState.Checking
        ) {
            Text("Check connection")
        }

        if (state is ConnectionState.Connected) {
            Button(
                onClick = onContinue,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Start scanning")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenIdlePreview() {
    LabelGuardTheme {
        ConnectionContent(state = ConnectionState.Idle, onCheck = {}, onContinue = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenConnectedPreview() {
    LabelGuardTheme {
        ConnectionContent(
            state = ConnectionState.Connected("0.1.0", "2026-08-31T10:45:00Z"),
            onCheck = {},
            onContinue = {}
        )
    }
}
