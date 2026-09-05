package com.labelguard.app.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correcting the optics against an object of known size.
 *
 * A calibration is permanent in a way a single scan is not: it multiplies
 * every later measurement on this phone. So the tests care less about the
 * happy path than about the two ways it could quietly poison everything —
 * accepting a correction that came from a bad drag, and applying a correction
 * at a distance it was never measured at.
 */
class CalibrationTest {

    private val cardLongEdge = 85.60

    // ------------------------------------------------------- the arithmetic

    @Test
    fun `a device reading exactly right needs no correction`() {
        // 85.6 mm across 1000 px is 0.0856 mm per px, which is precisely what
        // the optics claimed.
        val correction = Calibration.compute(cardLongEdge, 1000, 0.0856)
        assertEquals(1.0, correction!!, 0.0001)
    }

    @Test
    fun `a device under-reporting size is corrected upward`() {
        // Optics said 0.0800 mm per px; the card proves it is 0.0856.
        val correction = Calibration.compute(cardLongEdge, 1000, 0.0800)!!
        assertEquals(1.07, correction, 0.001)
        assertTrue(correction > 1.0)
    }

    @Test
    fun `a device over-reporting size is corrected downward`() {
        val correction = Calibration.compute(cardLongEdge, 1000, 0.0900)!!
        assertTrue(correction < 1.0)
    }

    @Test
    fun `the correction cancels the error it was measured from`() {
        // The property that matters: after correcting, measuring the very
        // same reference must give back its true size.
        val uncorrected = 0.0800
        val correction = Calibration.compute(cardLongEdge, 1000, uncorrected)!!

        assertEquals(cardLongEdge, 1000 * uncorrected * correction, 0.01)
    }

    // --------------------------------------------------------- refusing

    @Test
    fun `an implausible correction is refused`() {
        // Off by 5x means the wrong edge was dragged, or the card was on its
        // side. Accepting it would bake that mistake into every later scan.
        assertNull(Calibration.compute(cardLongEdge, 1000, 0.0856 / 5))
        assertNull(Calibration.compute(cardLongEdge, 1000, 0.0856 * 5))
    }

    @Test
    fun `a correction at the edge of plausibility is still refused`() {
        assertNull(
            "exactly 3x must not slip through",
            Calibration.compute(cardLongEdge, 1000, 0.0856 / Calibration.MAX_PLAUSIBLE_CORRECTION)
        )
    }

    @Test
    fun `nonsense inputs yield no correction`() {
        assertNull(Calibration.compute(0.0, 1000, 0.0856))
        assertNull(Calibration.compute(cardLongEdge, 0, 0.0856))
        assertNull(Calibration.compute(cardLongEdge, 1000, 0.0))
        assertNull(Calibration.compute(-85.6, 1000, 0.0856))
    }

    // ------------------------------------------------- the distance window

    private fun at(diopters: Double) = Calibration(
        correction = 1.07,
        referenceName = "Bank card, long edge",
        referenceMm = cardLongEdge,
        measuredPx = 1000,
        diopters = diopters
    )

    @Test
    fun `a calibration applies at the distance it was taken`() {
        assertTrue(at(5.0).appliesAt(5.0))
    }

    @Test
    fun `a calibration applies near the distance it was taken`() {
        // 5 diopters is 200 mm; 7 is about 143 mm. Same kind of shot.
        assertTrue(at(5.0).appliesAt(7.0))
        assertTrue(at(5.0).appliesAt(3.0))
    }

    @Test
    fun `a calibration does not apply far from where it was taken`() {
        // Measured at 200 mm, used at 25 mm. The assumption that the error
        // scales with distance has stopped being safe, and the honest wide
        // band is better than a narrow extrapolated one.
        assertFalse(at(5.0).appliesAt(40.0))
        assertFalse(at(5.0).appliesAt(1.0))
    }

    @Test
    fun `a calibration does not apply at an unknown distance`() {
        assertFalse(at(5.0).appliesAt(0.0))
        assertFalse(at(0.0).appliesAt(5.0))
    }

    // ---------------------------------------------------- narrowing effect

    @Test
    fun `calibrating narrows the band that made checks defer`() {
        val sensorMm = 5.55
        val imagePx = 3000
        val focalMm = 5.4
        val diopters = 5.0

        val uncalibrated = Scale.from(
            sensorMm, imagePx, focalMm, diopters, Scale.CALIBRATION_APPROXIMATE
        )!!
        val calibrated = Scale.from(
            sensorMm, imagePx, focalMm, diopters, Scale.CALIBRATION_APPROXIMATE,
            userCorrection = 1.07
        )!!

        val before = uncalibrated.toMm(45)
        val after = calibrated.toMm(45)

        assertTrue(
            "a calibrated band must be tighter: " + before.describe() +
                " vs " + after.describe(),
            (after.maxMm - after.minMm) < (before.maxMm - before.minMm) / 2
        )
    }

    @Test
    fun `a calibrated measurement can settle what an uncalibrated one cannot`() {
        // The whole reason for the flow. 3 mm characters against a 2.5 mm
        // minimum: the wide band straddles it, the narrow band clears it.
        val uncalibrated = Scale.from(
            5.55, 3000, 5.4, 5.0, Scale.CALIBRATION_APPROXIMATE
        )!!.toMm(45)
        val calibrated = Scale.from(
            5.55, 3000, 5.4, 5.0, Scale.CALIBRATION_APPROXIMATE, userCorrection = 1.001
        )!!.toMm(45)

        assertFalse(
            "the uncalibrated band should straddle 2.5 mm: " + uncalibrated.describe(),
            uncalibrated.certainlyAtLeast(2.5)
        )
        assertTrue(
            "the calibrated band should clear it: " + calibrated.describe(),
            calibrated.certainlyAtLeast(2.5)
        )
    }

    // ------------------------------------------------------------ reporting

    @Test
    fun `a calibration describes itself for the report`() {
        val described = at(5.0).describe()

        assertTrue(described, described.contains("Bank card"))
        assertTrue(described, described.contains("85.6"))
        assertTrue("the device error must be visible: " + described, described.contains("+7%"))
    }

    @Test
    fun `only references with dependable dimensions are offered`() {
        // Indian coin diameters have changed between minting series, so a
        // coin preset would sometimes be wrong, which is worse than none.
        assertNotNull(Calibration.REFERENCES.firstOrNull { it.name.contains("card") })
        assertTrue(
            "no coin presets: " + Calibration.REFERENCES.map { it.name },
            Calibration.REFERENCES.none { it.name.contains("coin", ignoreCase = true) }
        )
        assertTrue(Calibration.REFERENCES.all { it.mm > 0 })
    }
}
