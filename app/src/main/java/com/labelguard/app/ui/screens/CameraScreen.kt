package com.labelguard.app.ui.screens

import android.Manifest
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.labelguard.app.ui.components.CameraPreview
import com.labelguard.app.ui.components.FramingGrid
import com.labelguard.app.utils.captureBurst
import com.labelguard.app.measure.CameraOptics
import com.labelguard.app.viewmodel.SideCapture
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Navigation over the viewfinder, collapsed behind one control.
 *
 * A plain glyph rather than a Material icon: this is a layout fix, and adding
 * an icon dependency to place one button would be a heavier change than the
 * problem warrants.
 */
@Composable
private fun ViewfinderMenu(
    roleLabel: String,
    historyCount: Int,
    onOpenRole: () -> Unit,
    onOpenHistory: () -> Unit,
    onUpload: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text("Menu") }
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Upload images") },
                onClick = { open = false; onUpload() }
            )
            DropdownMenuItem(
                text = {
                    Text(if (historyCount == 0) "History" else "History ($historyCount)")
                },
                onClick = { open = false; onOpenHistory() }
            )
            DropdownMenuItem(
                text = { Text("Using as: " + roleLabel) },
                onClick = { open = false; onOpenRole() }
            )
        }
    }
}

/** Frames captured per side. Consensus needs at least 3 to corroborate. */
private const val FRAMES_PER_SIDE = 3

/**
 * The faces of a pack an inspector normally needs.
 *
 * Declarations are split across a pack — the brand on the front, MRP and
 * manufacturer usually on the back — so a single face often cannot show
 * everything the rules ask for.
 */
private val SIDES = listOf("Front", "Back", "Side")

/**
 * Live camera with framing grid, torch and multi-side capture.
 *
 * A scan takes several frames per side rather than one photo, because a
 * single OCR reading is not evidence enough to assert a compliance violation.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onSidesCaptured: (List<SideCapture>) -> Unit,
    optics: CameraOptics,
    roleLabel: String = "",
    historyCount: Int = 0,
    onOpenRole: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onUpload: () -> Unit = {},
    statusMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    if (cameraPermission.status.isGranted) {
        CameraContent(
            onSidesCaptured = onSidesCaptured,
            optics = optics,
            roleLabel = roleLabel,
            historyCount = historyCount,
            onOpenRole = onOpenRole,
            onOpenHistory = onOpenHistory,
            onUpload = onUpload,
            statusMessage = statusMessage,
            modifier = modifier
        )
    } else {
        PermissionRationale(
            onRequest = cameraPermission::launchPermissionRequest,
            modifier = modifier
        )
    }
}

@Composable
private fun CameraContent(
    onSidesCaptured: (List<SideCapture>) -> Unit,
    optics: CameraOptics,
    roleLabel: String,
    historyCount: Int,
    onOpenRole: () -> Unit,
    onOpenHistory: () -> Unit,
    onUpload: () -> Unit,
    statusMessage: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember(optics) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Records each frame's focal length and focus distance, which is
            // what makes a millimetre measurement possible at all.
            .also(optics::attachTo)
            .build()
    }

    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }

    // Sides captured so far for the product in hand.
    val captured = remember { mutableStateListOf<SideCapture>() }
    val nextSide = SIDES.getOrNull(captured.size) ?: "Extra ${captured.size + 1}"

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            imageCapture = imageCapture,
            modifier = Modifier.fillMaxSize(),
            torchOn = torchOn,
            onTorchAvailable = { torchAvailable = it },
            onCameraBound = optics::readCharacteristics
        )

        if (showGrid) {
            FramingGrid(modifier = Modifier.fillMaxSize())
        }

        // --- one top bar
        //
        // Everything that sits over the viewfinder shares this layout. It used
        // to be three separate overlays each anchored to a corner, which had
        // no way of knowing about each other and printed the upload and
        // history controls on top of one another.
        //
        // Shooting controls stay visible on the left because they are used
        // while aiming. Navigation goes behind one menu on the right: it is
        // not needed mid-shot, and a growing row of buttons was what crowded
        // the corner in the first place.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (torchAvailable) {
                    FilterChip(
                        selected = torchOn,
                        onClick = { torchOn = !torchOn },
                        label = { Text(if (torchOn) "Torch on" else "Torch") }
                    )
                }
                FilterChip(
                    selected = showGrid,
                    onClick = { showGrid = !showGrid },
                    label = { Text("Grid") }
                )

                Box(modifier = Modifier.weight(1f))

                ViewfinderMenu(
                    roleLabel = roleLabel,
                    historyCount = historyCount,
                    onOpenRole = onOpenRole,
                    onOpenHistory = onOpenHistory,
                    onUpload = onUpload
                )
            }

            // Directly under the bar rather than layered over it, so a long
            // message pushes nothing off screen and covers no control.
            statusMessage?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    // Capped and scrollable: the NOT_ASSESSABLE explanation
                    // runs to several lines and would otherwise push the
                    // viewfinder off the screen.
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // --- capture controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
                .padding(horizontal = 20.dp)
                .padding(top = 32.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (captured.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "Captured: " + captured.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Button(
                onClick = {
                    if (capturing) return@Button
                    scope.launch {
                        capturing = true
                        try {
                            val frames = imageCapture.captureBurst(
                                context = context,
                                count = FRAMES_PER_SIDE,
                                onProgress = { progress = it }
                            )
                            captured += SideCapture(nextSide, frames)
                        } catch (_: Exception) {
                            // Leave already-captured sides intact so the
                            // operator can retry this side alone.
                        } finally {
                            capturing = false
                            progress = 0
                        }
                    }
                },
                enabled = !capturing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        capturing -> "Hold still — $progress of $FRAMES_PER_SIDE"
                        captured.isEmpty() -> "Capture front"
                        else -> "Add $nextSide"
                    }
                )
            }

            if (captured.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            captured.forEach { side -> side.frames.forEach { it.delete() } }
                            captured.clear()
                        },
                        enabled = !capturing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Discard")
                    }
                    Button(
                        onClick = {
                            val sides = captured.toList()
                            captured.clear()
                            onSidesCaptured(sides)
                        },
                        enabled = !capturing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (captured.size == 1) "Analyse 1 side" else "Analyse ${captured.size} sides")
                    }
                }
            }
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
