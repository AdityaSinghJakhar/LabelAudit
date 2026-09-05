package com.labelguard.app.measure

/**
 * A correction measured once against an object of known size.
 *
 * The device reports its focus distance as APPROXIMATE, which is honest of it
 * and leaves a +/-30% band around every millimetre figure. That band is wide
 * enough that most height checks can only defer. Photographing something whose
 * real size is known collapses the unknown to a single multiplier and takes
 * the band to +/-5%.
 *
 * The multiplier assumes the device's error scales with distance rather than
 * sitting as a fixed offset. That holds well enough near the distance it was
 * measured at and progressively less well away from it, so a calibration
 * records the focus distance it was taken at and declines to apply itself
 * outside a window around it. Silently extrapolating would trade a wide honest
 * band for a narrow wrong one.
 */
data class Calibration(
    /** Multiplies the uncorrected millimetres-per-pixel. */
    val correction: Double,
    /** What was photographed, for the report and for re-checking later. */
    val referenceName: String,
    val referenceMm: Double,
    val measuredPx: Int,
    /** LENS_FOCUS_DISTANCE, in diopters, when the reference was measured. */
    val diopters: Double,
    val at: Long = System.currentTimeMillis()
) {
    /**
     * Whether this calibration may be applied at a given focus distance.
     *
     * A reference measured at arm's length says little about a macro shot.
     * The window is generous because the alternative is the uncorrected
     * +/-30%, not nothing — but it is finite.
     */
    fun appliesAt(scanDiopters: Double): Boolean {
        if (scanDiopters <= 0 || diopters <= 0) return false
        val ratio = scanDiopters / diopters
        return ratio in (1 / DISTANCE_WINDOW)..DISTANCE_WINDOW
    }

    /** How far the device's own reading was out, as a percentage. */
    val errorPercent: Double get() = (correction - 1.0) * 100

    fun describe(): String =
        "%s, %.1f mm measured across %d px (device out by %+.0f%%)"
            .format(referenceName, referenceMm, measuredPx, errorPercent)

    companion object {

        /**
         * How far the scan's focus distance may differ from the calibration's,
         * as a ratio either way.
         */
        const val DISTANCE_WINDOW = 2.0

        /**
         * A correction so large it means the measurement was wrong, not the
         * device. Usually the reference was tilted, or the wrong edge was
         * dragged across.
         */
        const val MAX_PLAUSIBLE_CORRECTION = 3.0

        /**
         * Work out the correction, or null when the inputs cannot yield one.
         *
         * @param referenceMm         the reference's true size along the edge
         *                            that was measured.
         * @param measuredPx          how many pixels that edge spanned.
         * @param uncorrectedMmPerPx  what the optics alone made of that pixel,
         *                            before any correction.
         */
        fun compute(
            referenceMm: Double,
            measuredPx: Int,
            uncorrectedMmPerPx: Double
        ): Double? {
            if (referenceMm <= 0 || measuredPx <= 0 || uncorrectedMmPerPx <= 0) return null

            val trueMmPerPx = referenceMm / measuredPx
            val correction = trueMmPerPx / uncorrectedMmPerPx

            // Refuse a correction that implies the optics were out by more
            // than any plausible focus-distance error. Accepting it would bake
            // a bad drag or a tilted card into every later measurement.
            if (correction <= 1 / MAX_PLAUSIBLE_CORRECTION ||
                correction >= MAX_PLAUSIBLE_CORRECTION
            ) {
                return null
            }

            return correction
        }

        /**
         * References whose real dimensions are dependable.
         *
         * Indian coins are deliberately absent: their diameters have changed
         * between minting series, so "a five rupee coin" is not one size, and
         * a reference that is sometimes wrong is worse than none. A payment
         * card is fixed by ISO/IEC 7810 ID-1 and is in everyone's pocket.
         */
        val REFERENCES = listOf(
            Reference("Bank card, long edge", 85.60),
            Reference("Bank card, short edge", 53.98)
        )

        data class Reference(val name: String, val mm: Double)
    }
}
