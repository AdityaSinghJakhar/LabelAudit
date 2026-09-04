package com.labelguard.app.measure

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * The pixel dimensions of a captured frame, as the OCR sees them.
 *
 * Not simply the JPEG's stored width and height. ML Kit's file-based
 * InputImage applies the EXIF orientation before recognising, so a portrait
 * photo stored as landscape-plus-a-rotation-tag reaches the recogniser
 * rotated, and every box it returns is in that rotated frame. Measuring
 * against the stored dimensions would divide by the wrong number and put a
 * millimetre figure out by the sensor's aspect ratio on every portrait shot.
 *
 * Bounds-only decoding, so the pixels are never allocated.
 */
object ImageSize {

    data class Size(val width: Int, val height: Int)

    fun of(file: File): Size? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        val quarterTurn = runCatching {
            when (
                ExifInterface(file.absolutePath)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        }.getOrDefault(false)

        return if (quarterTurn) {
            Size(options.outHeight, options.outWidth)
        } else {
            Size(options.outWidth, options.outHeight)
        }
    }
}
