package com.labelguard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.labelguard.app.auth.Role

/**
 * Choosing how the app is being used.
 *
 * Written to be honest about what the passcode is. It keeps a shopper from
 * wandering into enforcement features on their own phone; it is not a
 * security boundary, and the screen says so rather than implying an authority
 * that a locally stored digest cannot provide.
 */
@Composable
fun RoleScreen(
    role: Role,
    hasPasscode: Boolean,
    message: String?,
    onClaimInspector: (String) -> Unit,
    onRelease: () -> Unit,
    /** Null for a shopper: a bad calibration silently skews every scan. */
    onCalibrate: (() -> Unit)? = null,
    calibrationSummary: String? = null,
    /**
     * Keeping scans for evaluation. Null for a shopper: it is a measuring
     * instrument that grows without bound, not a feature.
     */
    keepCorpus: Boolean = false,
    onKeepCorpus: ((Boolean) -> Unit)? = null,
    corpusSummary: String = "",
    onClearCorpus: (() -> Unit)? = null,
    onShareCorpus: (() -> Unit)? = null,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var passcode by remember { mutableStateOf("") }
    var entering by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Who is using this app", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Close") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Currently: ${role.label}", fontWeight = FontWeight.Medium)
                Text(
                    role.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        when (role) {
            Role.INSPECTOR -> {
                onCalibrate?.let { calibrate ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Camera calibration", fontWeight = FontWeight.Medium)
                            Text(
                                calibrationSummary
                                    ?: "Not calibrated. Character heights carry the " +
                                    "device's own uncertainty, which is wide enough " +
                                    "that most size checks can only defer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            TextButton(onClick = calibrate) {
                                Text(
                                    if (calibrationSummary == null) "Calibrate the camera"
                                    else "Calibrate again"
                                )
                            }
                        }
                    }
                }

                onKeepCorpus?.let { setKeep ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Keep scans for evaluation", fontWeight = FontWeight.Medium)
                                Switch(checked = keepCorpus, onCheckedChange = setKeep)
                            }
                            Text(
                                "Scans are normally discarded the moment they " +
                                    "finish. Keeping the photographs alongside " +
                                    "what the app read is what makes accuracy " +
                                    "measurable: someone can write down what the " +
                                    "pack really says, and an improved reader can " +
                                    "be re-run over the same images to show it " +
                                    "actually improved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                corpusSummary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                onShareCorpus?.let {
                                    TextButton(onClick = it) { Text("Share corpus") }
                                }
                                onClearCorpus?.let {
                                    TextButton(onClick = it) { Text("Delete kept scans") }
                                }
                            }
                        }
                    }
                }

                OutlinedButton(onClick = onRelease, modifier = Modifier.fillMaxWidth()) {
                    Text("Switch back to shopper")
                }
            }

            Role.CONSUMER -> {
                if (!entering) {
                    OutlinedButton(
                        onClick = { entering = true; onDismissMessage() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (hasPasscode) "Sign in as inspector"
                            else "Set up inspector access"
                        )
                    }
                } else {
                    if (!hasPasscode) {
                        Text(
                            "No passcode has been set on this phone yet, so the " +
                                "first one entered becomes it. There is no server " +
                                "to issue credentials against — which is exactly " +
                                "why an inspector's registered reference still " +
                                "cannot fail another pack on its own.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = passcode,
                        onValueChange = { passcode = it },
                        label = { Text("Inspector passcode") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onClaimInspector(passcode); passcode = "" },
                            enabled = passcode.isNotBlank()
                        ) {
                            Text("Continue")
                        }
                        TextButton(onClick = { entering = false; passcode = "" }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Text("What each role can do", style = MaterialTheme.typography.titleMedium)
        Role.entries.forEach { candidate ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(candidate.label, fontWeight = FontWeight.Medium)
                    candidate.capabilities.sortedBy { it.name }.forEach {
                        Text(
                            "· " + it.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            "This passcode is checked on this phone against a digest stored on " +
                "this phone. It keeps roles separate; it does not stop anyone " +
                "holding the device. Nothing here decides whether a pack is " +
                "compliant — a reference registered by an inspector still only " +
                "raises a question, never a violation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
