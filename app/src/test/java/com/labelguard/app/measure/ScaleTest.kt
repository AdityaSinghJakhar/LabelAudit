package com.labelguard.app.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning pixels into millimetres.
 *
 * Two families of test here. The first checks the optics against a case with
 * a known answer, because a systematic error in this arithmetic would put a
 * plausible millimetre figure on every report in the app. The second checks
 * that the function refuses — a device that cannot supply trustworthy numbers
 * must produce no measurement at all rather than a confident wrong one.
 */
class ScaleTest {

    // A typical phone main camera: 1/1.7" sensor, 26mm-equivalent lens.
    // Exactly 4:3, so the crop tests below have an unambiguous expectation.
    private val sensorHeightMm = 5.55
    private val sensorWidthMm = 7.4
    private val focalMm = 5.4
    private val imageHeightPx = 3000
    private val imageWidthPx = 4000

    private fun scale(
        distanceMm: Double,
        calibration: Int = Scale.CALIBRATION_CALIBRATED,
        correction: Double = 1.0
    ) = Scale.from(
        sensorMm = sensorHeightMm,
        imagePx = imageHeightPx,
        focalLengthMm = focalMm,
        focusDiopters = 1000.0 / distanceMm,
        calibration = calibration,
        userCorrection = correction
    )

    // ------------------------------------------------------------ optics

    @Test
    fun `a known geometry gives the expected millimetres per pixel`() {
        // At 200 mm the frame spans (sensor / f) x (u - f)
        //   = (5.55 / 5.4) x 194.6 = 200.0 mm across 3000 px
        //   = 0.0667 mm per pixel.
        val s = scale(200.0)!!
        assertEquals(0.0667, s.midpoint(), 0.0005)
    }

    @Test
    fun `holding the phone closer makes each pixel smaller`() {
        val near = scale(100.0)!!.midpoint()
        val far = scale(300.0)!!.midpoint()
        assertTrue("closer must resolve finer detail: $near vs $far", near < far)
    }

    @Test
    fun `a 3mm character at 200mm measures about 3mm`() {
        val s = scale(200.0)!!
        // 3 mm / 0.0667 mm per px = 45 px
        val measured = s.toMm(45)
        assertEquals(3.0, measured.midpointMm, 0.15)
    }

    @Test
    fun `distance scales the measurement proportionally`() {
        // Twice as far away, the same character covers half as many pixels,
        // so the same pixel count must read as roughly twice the size.
        val near = scale(150.0)!!.toMm(100).midpointMm
        val far = scale(300.0)!!.toMm(100).midpointMm
        assertEquals(2.0, far / near, 0.05)
    }

    // ------------------------------------------------------- uncertainty

    @Test
    fun `a measurement is a range, never a point`() {
        val m = scale(200.0)!!.toMm(45)
        assertTrue("range must be non-empty", m.maxMm > m.minMm)
    }

    @Test
    fun `an approximate device gives a wider range than a calibrated one`() {
        val approximate = scale(200.0, Scale.CALIBRATION_APPROXIMATE)!!.toMm(45)
        val calibrated = scale(200.0, Scale.CALIBRATION_CALIBRATED)!!.toMm(45)

        assertTrue(
            "a less trustworthy device must claim less precision",
            (approximate.maxMm - approximate.minMm) > (calibrated.maxMm - calibrated.minMm)
        )
    }

    @Test
    fun `user calibration narrows the range furthest`() {
        val uncalibrated = scale(200.0, Scale.CALIBRATION_APPROXIMATE)!!.toMm(45)
        val corrected = scale(200.0, Scale.CALIBRATION_APPROXIMATE, correction = 1.08)!!.toMm(45)

        assertTrue(
            (corrected.maxMm - corrected.minMm) < (uncalibrated.maxMm - uncalibrated.minMm)
        )
        assertEquals(Scale.Source.CALIBRATED_DEVICE, corrected.source)
    }

    @Test
    fun `a threshold inside the range is not settled either way`() {
        val m = scale(200.0, Scale.CALIBRATION_APPROXIMATE)!!.toMm(45)
        // The range straddles 3 mm, so neither question may be answered yes.
        assertFalse(m.certainlyAtLeast(3.0))
        assertFalse(m.certainlyBelow(3.0))
    }

    @Test
    fun `a threshold below the whole range is certainly met`() {
        val m = scale(200.0, Scale.CALIBRATION_APPROXIMATE)!!.toMm(45)
        assertTrue(m.certainlyAtLeast(1.0))
        assertFalse(m.certainlyBelow(1.0))
    }

    @Test
    fun `a threshold above the whole range is certainly missed`() {
        val m = scale(200.0, Scale.CALIBRATION_APPROXIMATE)!!.toMm(10)
        assertTrue("$m should be entirely under 1mm", m.certainlyBelow(1.0))
        assertFalse(m.certainlyAtLeast(1.0))
    }

    // ---------------------------------------------------------- refusal

    @Test
    fun `an uncalibrated device yields no scale at all`() {
        // The phone is saying its diopter values are not real units. Taking
        // them anyway would put a millimetre figure on the report with
        // nothing behind it.
        assertNull(scale(200.0, Scale.CALIBRATION_UNCALIBRATED))
    }

    @Test
    fun `focus at infinity yields no scale`() {
        assertNull(
            Scale.from(sensorHeightMm, imageHeightPx, focalMm, 0.0, Scale.CALIBRATION_CALIBRATED)
        )
    }

    @Test
    fun `focus nearer than the focal length yields no scale`() {
        // Not a physical state; it means the metadata is wrong.
        assertNull(scale(focalMm / 2))
    }

    @Test
    fun `missing optics yield no scale`() {
        assertNull(Scale.from(0.0, imageHeightPx, focalMm, 5.0, Scale.CALIBRATION_CALIBRATED))
        assertNull(Scale.from(sensorHeightMm, 0, focalMm, 5.0, Scale.CALIBRATION_CALIBRATED))
        assertNull(Scale.from(sensorHeightMm, imageHeightPx, 0.0, 5.0, Scale.CALIBRATION_CALIBRATED))
    }

    @Test
    fun `a measurement is never negative`() {
        // A very wide tolerance could push the near bound below the focal
        // length; the clamp must keep the result physical.
        val s = scale(6.0, Scale.CALIBRATION_APPROXIMATE)
        assertNotNull(s)
        assertTrue("min must stay positive: $s", s!!.mmPerPixelMin > 0)
    }

    // ------------------------------------------------------ sensor crop

    @Test
    fun `a four-three image uses the whole sensor height`() {
        assertEquals(
            sensorHeightMm,
            Scale.sensorHeightCovering(sensorWidthMm, sensorHeightMm, 4000, 3000),
            0.001
        )
    }

    @Test
    fun `a sixteen-nine image uses less than the sensor height`() {
        // Cropping the sensor to make a wider frame. Using the full height
        // here would overstate every millimetre figure by a third.
        val covered = Scale.sensorHeightCovering(sensorWidthMm, sensorHeightMm, 4000, 2250)
        assertTrue("$covered should be under $sensorHeightMm", covered < sensorHeightMm)
        assertEquals(sensorWidthMm / (4000.0 / 2250.0), covered, 0.001)
    }

    @Test
    fun `a taller than sensor image is not stretched`() {
        assertEquals(
            sensorHeightMm,
            Scale.sensorHeightCovering(sensorWidthMm, sensorHeightMm, 3000, 4000),
            0.001
        )
    }

    private fun Scale.midpoint() = (mmPerPixelMin + mmPerPixelMax) / 2
}
