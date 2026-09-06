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

/**
 * Corner brackets marking the area to fill with the pack.
 *
 * Brackets rather than a full rectangle: a closed frame draws a line across
 * the label itself, and the operator ends up framing to the line instead of
 * to the text. The corners say "fill this" while hiding almost nothing.
 */
@Composable
fun CaptureReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val colour = Color.White.copy(alpha = 0.85f)
        val stroke = 2.dp.toPx()
        // Inset so the brackets sit inside the frame the preview shows, and
        // an arm long enough to read as a corner rather than a stray tick.
        val inset = size.minDimension * 0.08f
        val arm = size.minDimension * 0.10f

        val left = inset
        val right = size.width - inset
        val top = size.height * 0.16f
        val bottom = size.height * 0.84f

        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(colour, Offset(x, y), Offset(x + dx, y), stroke)
            drawLine(colour, Offset(x, y), Offset(x, y + dy), stroke)
        }

        corner(left, top, arm, arm)
        corner(right, top, -arm, arm)
        corner(left, bottom, arm, -arm)
        corner(right, bottom, -arm, -arm)
    }
}
