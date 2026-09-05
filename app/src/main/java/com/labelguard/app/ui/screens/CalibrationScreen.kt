package com.labelguard.app.ui.screens

import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labelguard.app.measure.Calibration
import com.labelguard.app.measure.CameraOptics
import com.labelguard.app.measure.ImageSize
import com.labelguard.app.measure.Scale
import com.labelguard.app.ui.components.CameraPreview
import com.labelguard.app.utils.captureToCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Measuring the camera against an object of known size.
 *
 * The photo is taken first and the measuring line dragged on the still,
 * rather than over a live preview. That is not only easier for the person
 * holding the phone — a preview is scaled and centre-cropped by CameraX, so a
 * point on it maps to the captured frame through a transform that varies by
 * device and aspect ratio. Getting that transform slightly wrong would put a
 * silent systematic error into the very number meant to remove one. On a
 * still, the mapping is one ratio between the displayed bitmap and the file.
 */
@Composable
fun CalibrationScreen(
    optics: CameraOptics,
    existing: Calibration?,
    onSave: (Calibration) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var shot by remember { mutableStateOf<Shot?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf(Calibration.REFERENCES.first()) }
    var customMm by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember(optics) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .also(optics::attachTo)
            .build()
    }

    val referenceMm = customMm.toDoubleOrNull() ?: reference.mm
    val referenceName = if (customMm.toDoubleOrNull() != null) {
        "Custom, %.1f mm".format(referenceMm)
    } else {
        reference.name
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Calibrate the camera", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("Close") }
        }

        existing?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Already calibrated", fontWeight = FontWeight.Medium)
                    Text(
                        it.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onClear) { Text("Remove calibration") }
                }
            }
        }

        Text(
            "This phone reports its focus distance only approximately, which " +
                "leaves character heights uncertain by about a third — wide " +
                "enough that most size checks can only defer. Photographing " +
                "something whose real size is known fixes that.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ------------------------------------------------------- reference
        Text("What are you photographing?", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Calibration.REFERENCES.forEach { candidate ->
                FilterChip(
                    selected = customMm.toDoubleOrNull() == null && reference == candidate,
                    onClick = { reference = candidate; customMm = "" },
                    label = { Text(candidate.name.removePrefix("Bank card, ")) }
                )
            }
        }
        OutlinedTextField(
            value = customMm,
            onValueChange = { customMm = it },
            label = { Text("or a known size in mm") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val current = shot
        if (current == null) {
            Text(
                "Lay it flat on the pack, fill as much of the frame as you " +
                    "can, and hold the phone square to it. A tilted card reads " +
                    "shorter than it is and the correction inherits the error.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                CameraPreview(
                    imageCapture = imageCapture,
                    modifier = Modifier.fillMaxWidth(),
                    onCameraBound = optics::readCharacteristics
                )
            }

            Button(
                onClick = {
                    capturing = true
                    problem = null
                    scope.launch {
                        val taken = runCatching { capture(context, imageCapture, optics) }
                        capturing = false
                        taken.getOrNull()
                            ?.let { shot = it }
                            ?: run {
                                problem = taken.exceptionOrNull()?.message
                                    ?: "The camera did not report the optics needed"
                            }
                    }
                },
                enabled = !capturing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (capturing) "Capturing…" else "Take the photo")
            }
        } else {
            MeasureOnStill(
                shot = current,
                referenceMm = referenceMm,
                referenceName = referenceName,
                onRetake = { current.file.delete(); shot = null },
                onSave = {
                    onSave(it)
                    current.file.delete()
                    onBack()
                },
                onProblem = { problem = it }
            )
        }

        problem?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A captured frame plus the optics that produced it. */
private data class Shot(
    val file: File,
    val fullWidthPx: Int,
    val fullHeightPx: Int,
    val uncorrectedMmPerPx: Double,
    val diopters: Double
)

private suspend fun capture(
    context: android.content.Context,
    imageCapture: ImageCapture,
    optics: CameraOptics
): Shot {
    val file = imageCapture.captureToCache(context)

    val size = ImageSize.of(file) ?: error("The photo could not be read back")
    // Deliberately uncorrected: applying the existing calibration here would
    // fold it into the new one, and each recalibration would compound the
    // last instead of replacing it.
    val scale: Scale = optics.scaleFor(size.width, size.height, applyCalibration = false)
        ?: error("This device did not report the optics needed to calibrate")
    val diopters = optics.currentDiopters ?: error("No focus distance was reported")

    return Shot(
        file = file,
        fullWidthPx = size.width,
        fullHeightPx = size.height,
        uncorrectedMmPerPx = (scale.mmPerPixelMin + scale.mmPerPixelMax) / 2,
        diopters = diopters
    )
}

@Composable
private fun MeasureOnStill(
    shot: Shot,
    referenceMm: Double,
    referenceName: String,
    onRetake: () -> Unit,
    onSave: (Calibration) -> Unit,
    onProblem: (String) -> Unit
) {
    val bitmap = remember(shot.file) {
        BitmapFactory.decodeFile(
            shot.file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = 4 }
        )
    } ?: return

    var shown by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var start by remember { mutableStateOf(Offset.Unspecified) }
    var end by remember { mutableStateOf(Offset.Unspecified) }
    var dragging by remember { mutableStateOf(0) }

    Text(
        "Drag the two ends of the line onto the edge you named.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                shown = size
                if (start == Offset.Unspecified) {
                    start = Offset(size.width * 0.2f, size.height * 0.5f)
                    end = Offset(size.width * 0.8f, size.height * 0.5f)
                }
            }
            .pointerInput(shot.file) {
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = if ((at - start).getDistance() <= (at - end).getDistance()) 1 else 2
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        if (dragging == 1) start += delta else end += delta
                    }
                )
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Calibration photo",
            modifier = Modifier.fillMaxWidth()
        )

        if (start != Offset.Unspecified) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLine(Color(0xFFFF5252), start, end, strokeWidth = 6f)
                drawCircle(Color(0xFFFF5252), radius = 26f, center = start)
                drawCircle(Color(0xFFFF5252), radius = 26f, center = end)
            }
        }
    }

    // The displayed image is a scaled copy of the file; one ratio maps between
    // them, taken on width because the Image fills the width.
    val shownPx = if (start == Offset.Unspecified) 0.0
    else hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())
    val ratio = if (shown.width > 0) shot.fullWidthPx.toDouble() / shown.width else 0.0
    val measuredPx = (shownPx * ratio).roundToInt()

    val correction = Calibration.compute(referenceMm, measuredPx, shot.uncorrectedMmPerPx)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("%d px across %.1f mm".format(measuredPx, referenceMm))
            if (correction == null) {
                Text(
                    if (measuredPx <= 0) {
                        "Drag the line onto the edge to measure it."
                    } else {
                        "That implies the camera is out by more than any focus " +
                            "error explains. Check the line is on the edge you " +
                            "named, and that the card is lying flat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "This camera reads sizes %+.0f%% out. Correcting it narrows "
                        .format((correction - 1) * 100) +
                        "every height measurement from about a third to a twentieth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                correction?.let {
                    onSave(
                        Calibration(
                            correction = it,
                            referenceName = referenceName,
                            referenceMm = referenceMm,
                            measuredPx = measuredPx,
                            diopters = shot.diopters
                        )
                    )
                } ?: onProblem("Measure the reference before saving")
            },
            enabled = correction != null
        ) {
            Text("Save calibration")
        }
        OutlinedButton(onClick = onRetake) { Text("Retake") }
    }
}
