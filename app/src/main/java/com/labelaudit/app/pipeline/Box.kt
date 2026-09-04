package com.labelaudit.app.pipeline

/**
 * Pixel bounds in the source frame.
 *
 * Deliberately not android.graphics.Rect: keeping the pipeline free of
 * Android types lets the whole of it — normalisation, consensus, extraction —
 * run under plain JVM unit tests with no device or Robolectric. Conversion
 * from Rect happens once, at the OCR boundary.
 */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        val EMPTY = Box(0, 0, 0, 0)
    }
}
