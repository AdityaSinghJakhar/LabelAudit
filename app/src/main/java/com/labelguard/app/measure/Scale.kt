package com.labelguard.app.measure

/**
 * Millimetres per pixel, derived from the camera's own optics.
 *
 * Rule 9 asks how tall the printed characters are in millimetres, and a
 * photograph only knows pixels. Something has to supply the scale. A printed
 * marker in every shot does it accurately and is unusable in a shop, so this
 * takes the scale from the lens instead: sensor size, focal length and how far
 * away the phone focused.
 *
 *   object height = pixels x (sensor mm / image px) x (u - f) / f
 *
 * from the thin-lens magnification M = f / (u - f), where u is the focus
 * distance and f the focal length.
 *
 * The catch is u. Android reports it in diopters and warns, through
 * LENS_INFO_FOCUS_DISTANCE_CALIBRATION, that on many phones the number is not
 * in real units at all. So a measurement is never a single figure here — it is
 * a range, widened by however much the device's focus distance can be trusted,
 * and a check may only assert a violation when the whole range clears the
 * threshold. Same discipline as [com.labelguard.app.pipeline.LabelDate]: where
 * the evidence is a range, the verdict has to respect it.
 */
data class Scale(
    /** Millimetres per image pixel, lower bound. */
    val mmPerPixelMin: Double,
    /** Millimetres per image pixel, upper bound. */
    val mmPerPixelMax: Double,
    val source: Source
) {
    enum class Source {
        /**
         * Focus distance the device reports as CALIBRATED, corrected by a
         * measurement the user took against an object of known size.
         */
        CALIBRATED_DEVICE,

        /** Device-reported focus distance, no user calibration. */
        REPORTED_OPTICS,
    }

    /** Convert a pixel measurement into a millimetre range. */
    fun toMm(pixels: Int): Measurement = Measurement(
        minMm = pixels * mmPerPixelMin,
        maxMm = pixels * mmPerPixelMax,
        source = source
    )

    /**
     * A physical measurement that knows how uncertain it is.
     *
     * [certainlyAtLeast] and [certainlyBelow] are the only two questions a
     * compliance check may ask of it. Anything the range straddles is a
     * question the photograph did not answer.
     */
    data class Measurement(
        val minMm: Double,
        val maxMm: Double,
        val source: Source
    ) {
        val midpointMm: Double get() = (minMm + maxMm) / 2

        fun certainlyAtLeast(threshold: Double): Boolean = minMm >= threshold

        fun certainlyBelow(threshold: Double): Boolean = maxMm < threshold

        fun describe(): String = "%.2f-%.2f mm".format(minMm, maxMm)
    }

    companion object {

        /**
         * Focus distance calibration levels, mirroring
         * CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION so this
         * file stays free of Android imports and unit-testable.
         */
        const val CALIBRATION_UNCALIBRATED = 0
        const val CALIBRATION_APPROXIMATE = 1
        const val CALIBRATION_CALIBRATED = 2

        /**
         * How far the reported focus distance may be off, as a fraction.
         *
         * ASSUMED, NOT MEASURED. These two numbers decide whether a pack is
         * reported as violating Rule 9, and nobody has yet checked them
         * against real distances on real handsets. Until that happens the
         * ruleset keeps `needs_calibration: true` and the height check is
         * capped at NEEDS_REVIEW, so an unvalidated tolerance can never
         * produce an accusation.
         */
        const val TOLERANCE_APPROXIMATE = 0.30
        const val TOLERANCE_CALIBRATED = 0.10

        /** After the user has measured a known object, trust it much further. */
        const val TOLERANCE_USER_CALIBRATED = 0.05

        /**
         * The sensor dimension, in millimetres, that the captured image
         * actually spans vertically.
         *
         * A 16:9 photograph off a 4:3 sensor does not use the full sensor
         * height — the sensor is cropped to make the wider frame. Using the
         * full height there would overstate millimetres per pixel by a third,
         * which is more error than the focus-distance tolerance this whole
         * calculation is built to respect.
         */
        fun sensorHeightCovering(
            sensorWidthMm: Double,
            sensorHeightMm: Double,
            imageWidthPx: Int,
            imageHeightPx: Int
        ): Double {
            if (imageHeightPx <= 0 || sensorHeightMm <= 0) return 0.0
            val sensorAspect = sensorWidthMm / sensorHeightMm
            val imageAspect = imageWidthPx.toDouble() / imageHeightPx
            return if (imageAspect > sensorAspect) {
                // Wider than the sensor: height is cropped.
                sensorWidthMm / imageAspect
            } else {
                sensorHeightMm
            }
        }

        /**
         * Build a scale from one capture's optics, or null when the device
         * cannot supply the numbers.
         *
         * Returning null is the point of this function. A phone reporting
         * UNCALIBRATED focus distance is telling us its diopter values are not
         * real units; inventing a measurement from them would produce a
         * confident millimetre figure with nothing behind it.
         *
         * @param sensorMm       the sensor dimension, in millimetres, spanning
         *                       the captured image along the axis being
         *                       measured. Not simply SENSOR_INFO_PHYSICAL_SIZE:
         *                       a 16:9 capture from a 4:3 sensor does not use
         *                       the full height, and the caller must pass the
         *                       part that the image actually covers.
         * @param imagePx        the captured image's size in pixels along that
         *                       same axis.
         * @param focalLengthMm  LENS_FOCAL_LENGTH for the frame.
         * @param focusDiopters  LENS_FOCUS_DISTANCE, in 1/metres. Zero means
         *                       focused at infinity, which yields no scale.
         * @param calibration    LENS_INFO_FOCUS_DISTANCE_CALIBRATION.
         * @param userCorrection a factor measured once on this device against
         *                       an object of known size; 1.0 when uncalibrated.
         */
        fun from(
            sensorMm: Double,
            imagePx: Int,
            focalLengthMm: Double,
            focusDiopters: Double,
            calibration: Int,
            userCorrection: Double = 1.0
        ): Scale? {
            // A device that says its focus distances are not real units is to
            // be believed.
            if (calibration == CALIBRATION_UNCALIBRATED) return null

            if (sensorMm <= 0 || imagePx <= 0 || focalLengthMm <= 0) return null
            if (focusDiopters <= 0) return null           // focused at infinity
            if (userCorrection <= 0) return null

            val distanceMm = 1000.0 / focusDiopters

            // Focused closer than the focal length is not a physical state;
            // it means the metadata is wrong, not that the pack is tiny.
            if (distanceMm <= focalLengthMm) return null

            val calibrated = userCorrection != 1.0
            val tolerance = when {
                calibrated -> TOLERANCE_USER_CALIBRATED
                calibration == CALIBRATION_CALIBRATED -> TOLERANCE_CALIBRATED
                else -> TOLERANCE_APPROXIMATE
            }

            val pixelPitchMm = sensorMm / imagePx

            fun mmPerPixelAt(distance: Double): Double =
                pixelPitchMm * (distance - focalLengthMm) / focalLengthMm * userCorrection

            val near = distanceMm * (1 - tolerance)
            val far = distanceMm * (1 + tolerance)

            // The near bound can fall below the focal length on a very wide
            // tolerance; clamp rather than return a negative millimetre.
            val lowDistance = maxOf(near, focalLengthMm * 1.0001)

            return Scale(
                mmPerPixelMin = mmPerPixelAt(lowDistance),
                mmPerPixelMax = mmPerPixelAt(far),
                source = if (calibrated) Source.CALIBRATED_DEVICE else Source.REPORTED_OPTICS
            )
        }
    }
}
