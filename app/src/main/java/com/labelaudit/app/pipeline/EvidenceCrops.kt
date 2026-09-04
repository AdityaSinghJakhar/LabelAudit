package com.labelaudit.app.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Cuts the region each field was read from out of its source frame.
 *
 * The report shows these crops instead of drawing highlights over the whole
 * label: a reviewer checking "does the label really say ₹45?" wants to see
 * those pixels, not hunt for a coloured box on a shrunken photo.
 *
 * Crops are kept small and in memory; the frames themselves are deleted once
 * the scan completes.
 */
object EvidenceCrops {

    /** Context pixels kept around the detected box, as a fraction of its size. */
    private const val PADDING_RATIO = 0.25f

    /** Crops wider than this are downscaled, to bound report and PDF size. */
    private const val MAX_WIDTH = 900

    fun extract(
        frame: File,
        boxesByField: Map<String, Box>
    ): Map<String, Bitmap> {
        if (boxesByField.isEmpty()) return emptyMap()

        val source = BitmapFactory.decodeFile(frame.absolutePath) ?: return emptyMap()

        return try {
            boxesByField.mapNotNull { (field, box) ->
                cropOrNull(source, box)?.let { field to it }
            }.toMap()
        } finally {
            source.recycle()
        }
    }

    private fun cropOrNull(source: Bitmap, box: Box): Bitmap? {
        if (box.width <= 0 || box.height <= 0) return null

        val padX = (box.width * PADDING_RATIO).toInt()
        val padY = (box.height * PADDING_RATIO).toInt()

        // Clamp to the frame; a box near an edge must not run off it.
        val left = (box.left - padX).coerceIn(0, source.width - 1)
        val top = (box.top - padY).coerceIn(0, source.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        val cropped = Bitmap.createBitmap(source, left, top, width, height)

        if (cropped.width <= MAX_WIDTH) return cropped

        val scale = MAX_WIDTH.toFloat() / cropped.width
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            MAX_WIDTH,
            (cropped.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
