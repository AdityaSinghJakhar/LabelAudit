package com.labelguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Rule-of-thirds framing guide.
 *
 * OCR accuracy on a label depends mostly on how squarely and how fully the
 * text fills the frame, and a grid is the cheapest way to help an operator
 * hold the pack parallel to the sensor. Deliberately faint: it is an aid to
 * framing, not something to read the label through.
 */
@Composable
fun FramingGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val line = Color.White.copy(alpha = 0.35f)
        val stroke = 1.dp.toPx()

        for (i in 1..2) {
            val x = size.width * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)

            val y = size.height * i / 3f
            drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}
