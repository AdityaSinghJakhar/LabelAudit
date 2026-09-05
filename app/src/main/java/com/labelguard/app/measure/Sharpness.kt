package com.labelguard.app.measure

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * How sharply a frame is focused, measured as the variance of its Laplacian.
 *
 * The Laplacian responds to edges. A crisp photograph of printed text is
 * almost entirely edges, so its second derivative swings hard and its variance
 * is large; blur those edges and the swings flatten out. It is a few dozen
 * lines of arithmetic and it needs no model.
 *
 * WHAT IT CANNOT DO
 * -----------------
 * The number is **content-dependent**. A sharp photograph of a blank corner of
 * a wrapper scores lower than a slightly soft photograph of a dense
 * ingredients list, because one has few edges to lose and the other has many.
 * So there is no threshold that separates "sharp" from "blurred" across
 * different labels, and any absolute figure quoted as one would be wrong on
 * some packs and right on others for no discoverable reason.
 *
 * What it can do is compare **frames of the same label**. A burst photographs
 * one subject several times: same print, same content, same distance. The
 * differences between those frames are focus and hand tremor, which is exactly
 * the question. So [compare] ranks a burst against its own best frame and
 * never against a constant.
 *
 * All measurements must be taken at the same working size — see [of] — because
 * downscaling blurs, and a frame measured small would look softer than the
 * same frame measured large.
 */
object Sharpness {

    /**
     * Width every frame is scaled to before measuring.
     *
     * Fixed so two measurements are comparable, and small so the pass is
     * cheap: a full-resolution Laplacian over a 12-megapixel capture costs
     * more than the OCR that follows it.
     */
    const val WORKING_WIDTH = 640

    /**
     * How far below its burst's sharpest frame a frame may fall before it is
     * treated as too soft to contribute.
     *
     * ASSUMED, NOT MEASURED. It decides which photographs reach the
     * recogniser, so it is stated here rather than buried, and [compare]
     * reports the ratio it used so a reader can see how close the call was.
     * Validating it needs a corpus of bursts with known-good and known-blurred
     * frames — see the corpus tooling.
     */
    const val RELATIVE_FLOOR = 0.40

    /**
     * Variance of the Laplacian over the luminance of an image.
     *
     * Pure arithmetic over a pixel array so it can be tested without a device,
     * which matters: this decides which frames the pipeline is allowed to
     * read, and a mistake here throws away good evidence silently.
     */
    fun of(pixels: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3 || pixels.size < width * height) return 0.0

        // Rec. 601 luma, in integer form. Colour is irrelevant to focus, and
        // three channels would triple the work for no gain.
        val luma = IntArray(width * height)
        for (i in 0 until width * height) {
            val p = pixels[i]
            luma[i] = (77 * ((p shr 16) and 0xff) +
                150 * ((p shr 8) and 0xff) +
                29 * (p and 0xff)) shr 8
        }

        var sum = 0.0
        var sumSquares = 0.0
        var count = 0

        // The four-neighbour Laplacian. Borders are skipped rather than
        // padded: a padded edge invents a discontinuity and reads as detail.
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val value = (
                    luma[i - 1] + luma[i + 1] +
                        luma[i - width] + luma[i + width] -
                        4 * luma[i]
                    ).toDouble()

                sum += value
                sumSquares += value * value
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSquares / count) - (mean * mean)
    }

    /** Measure a bitmap, scaled to [WORKING_WIDTH] so results are comparable. */
    fun of(bitmap: Bitmap): Double {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 0.0

        val scaled = if (bitmap.width > WORKING_WIDTH) {
            val height = (bitmap.height.toLong() * WORKING_WIDTH / bitmap.width)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, WORKING_WIDTH, height, true)
        } else {
            bitmap
        }

        return try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            of(pixels, scaled.width, scaled.height)
        } finally {
            // createScaledBitmap can return the original when no scaling was
            // needed; recycling that would destroy the caller's bitmap.
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * One frame's standing within its own burst.
     *
     * [ratio] is against the sharpest frame of the same label, so 1.0 is the
     * best available rather than any absolute ideal.
     */
    data class Frame(
        val index: Int,
        val variance: Double,
        val ratio: Double
    ) {
        val usable: Boolean get() = ratio >= RELATIVE_FLOOR

        fun describe(): String = "frame %d: %.0f (%.0f%% of the sharpest)"
            .format(index + 1, variance, ratio * 100)
    }

    data class Comparison(
        val frames: List<Frame>,
        val sharpest: Double
    ) {
        val usable: List<Frame> get() = frames.filter { it.usable }
        val rejected: List<Frame> get() = frames.filterNot { it.usable }

        /**
         * Why frames were dropped, for the report.
         *
         * Named rather than counted: an operator told "2 frames were unusable"
         * learns nothing they can act on, whereas the ratios say whether the
         * problem was one bad shot or a shaking hand throughout.
         */
        fun reason(): String? {
            if (rejected.isEmpty()) return null
            return "Dropped " + rejected.size + " soft frame(s): " +
                rejected.joinToString("; ") { it.describe() } +
                ". Threshold is " + "%.0f%%".format(RELATIVE_FLOOR * 100) +
                " of the sharpest frame in the burst."
        }
    }

    /**
     * Rank a burst against its own sharpest frame.
     *
     * A single frame is always usable: with nothing to compare against there
     * is no evidence it is soft, and refusing it would reject every bulk
     * upload on a suspicion the measurement cannot support.
     */
    fun compare(variances: List<Double>): Comparison {
        if (variances.isEmpty()) return Comparison(emptyList(), 0.0)

        val sharpest = variances.max()
        if (sharpest <= 0.0 || variances.size == 1) {
            return Comparison(
                variances.mapIndexed { i, v -> Frame(i, v, 1.0) },
                sharpest
            )
        }

        return Comparison(
            variances.mapIndexed { i, v -> Frame(i, v, v / sharpest) },
            sharpest
        )
    }

    /**
     * A rough "how blurred, in pixels" reading for the capture screen.
     *
     * Presented as guidance while aiming, never as evidence: it inherits every
     * content dependency described above, and its only job is to move when the
     * phone is steadier.
     */
    fun focusHint(variance: Double): String = when {
        variance <= 0.0 -> "No image"
        sqrt(variance) < 4 -> "Very soft — move closer or steady the phone"
        sqrt(variance) < 8 -> "Soft"
        sqrt(variance) < 16 -> "Acceptable"
        else -> "Sharp"
    }
}
