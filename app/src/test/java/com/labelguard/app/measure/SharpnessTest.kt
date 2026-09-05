package com.labelguard.app.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Measuring focus.
 *
 * Two things are worth pinning. The arithmetic has to actually respond to
 * blur — a measure that moved with something else would silently discard good
 * frames. And the comparison has to stay *relative*, because the raw number
 * depends as much on how much detail a pack prints as on how well it was
 * photographed, and a constant threshold would reject sparse labels and pass
 * blurred busy ones.
 */
class SharpnessTest {

    private val width = 64
    private val height = 64

    /** Hard vertical stripes: the sharpest thing a grid of pixels can hold. */
    private fun stripes(period: Int = 4): IntArray = IntArray(width * height) { i ->
        val x = i % width
        if ((x / period) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    /** A flat field: no edges at all, in perfect focus. */
    private fun blank(): IntArray = IntArray(width * height) { 0xFF808080.toInt() }

    /** Average each pixel with its neighbours, which is what blur does. */
    private fun blurred(source: IntArray, passes: Int): IntArray {
        var current = source
        repeat(passes) {
            val next = IntArray(current.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val p = current[ny * width + nx]
                            r += (p shr 16) and 0xff
                            g += (p shr 8) and 0xff
                            b += p and 0xff
                            n++
                        }
                    }
                    next[y * width + x] =
                        (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            current = next
        }
        return current
    }

    private fun measure(pixels: IntArray) = Sharpness.of(pixels, width, height)

    // ------------------------------------------------------- the arithmetic

    @Test
    fun `blurring an image lowers its score`() {
        val sharp = measure(stripes())
        val soft = measure(blurred(stripes(), 2))

        assertTrue("blur must reduce the measure: $sharp then $soft", soft < sharp)
    }

    @Test
    fun `more blur lowers it further`() {
        val once = measure(blurred(stripes(), 1))
        val thrice = measure(blurred(stripes(), 3))

        assertTrue("$thrice should be under $once", thrice < once)
    }

    @Test
    fun `a flat field has no detail to measure`() {
        assertEquals(0.0, measure(blank()), 0.001)
    }

    @Test
    fun `blurring a flat field changes nothing`() {
        // There is nothing to lose. This is the content dependency in its
        // purest form, and the reason no absolute threshold can work.
        assertEquals(measure(blank()), measure(blurred(blank(), 3)), 0.001)
    }

    @Test
    fun `noise scores high without being sharp`() {
        // Worth stating outright: this measure cannot tell fine print from
        // sensor noise. It is a focus aid, not an image-quality judgement.
        val random = Random(1)
        val noise = IntArray(width * height) {
            val v = random.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertTrue(measure(noise) > measure(stripes()) / 10)
    }

    @Test
    fun `an image too small to have an interior scores zero`() {
        assertEquals(0.0, Sharpness.of(IntArray(4), 2, 2), 0.001)
        assertEquals(0.0, Sharpness.of(IntArray(0), 0, 0), 0.001)
    }

    @Test
    fun `a short pixel array is refused rather than read past its end`() {
        assertEquals(0.0, Sharpness.of(IntArray(10), width, height), 0.001)
    }

    // ------------------------------------------------- comparing a burst

    @Test
    fun `the sharpest frame sets the standard`() {
        val comparison = Sharpness.compare(listOf(100.0, 50.0, 200.0))

        assertEquals(200.0, comparison.sharpest, 0.001)
        assertEquals(1.0, comparison.frames[2].ratio, 0.001)
        assertEquals(0.5, comparison.frames[0].ratio, 0.001)
    }

    @Test
    fun `a frame far below its burst is dropped`() {
        val comparison = Sharpness.compare(listOf(1000.0, 1000.0, 50.0))

        assertEquals(2, comparison.usable.size)
        assertEquals(1, comparison.rejected.size)
        assertEquals(2, comparison.rejected.first().index)
    }

    @Test
    fun `frames of similar sharpness are all kept`() {
        val comparison = Sharpness.compare(listOf(900.0, 1000.0, 950.0))

        assertTrue(comparison.rejected.isEmpty())
    }

    @Test
    fun `a lone frame is never rejected`() {
        // A bulk upload is one image per product. With nothing to compare
        // against there is no evidence it is soft, and rejecting it would
        // refuse the whole feature on a suspicion.
        val comparison = Sharpness.compare(listOf(3.0))

        assertTrue(comparison.rejected.isEmpty())
        assertTrue(comparison.frames.single().usable)
    }

    @Test
    fun `a burst of blank frames is not rejected wholesale`() {
        // Every frame scores zero, so none is worse than the others. Dropping
        // them all would leave the pipeline nothing to read and report it as
        // a focus problem, which it is not.
        val comparison = Sharpness.compare(listOf(0.0, 0.0, 0.0))

        assertTrue(comparison.rejected.isEmpty())
    }

    @Test
    fun `no frames compares to nothing`() {
        val comparison = Sharpness.compare(emptyList())

        assertTrue(comparison.frames.isEmpty())
        assertTrue(comparison.rejected.isEmpty())
    }

    // ------------------------------------------------------- what it says

    @Test
    fun `a rejection names the frames and the ratios`() {
        // "2 frames were unusable" tells an operator nothing they can act on.
        val reason = Sharpness.compare(listOf(1000.0, 100.0)).reason()!!

        assertTrue(reason, reason.contains("frame 2"))
        assertTrue(reason, reason.contains("10%"))
        assertTrue("the threshold must be visible: $reason", reason.contains("40%"))
    }

    @Test
    fun `nothing rejected means nothing to report`() {
        assertEquals(null, Sharpness.compare(listOf(1000.0, 900.0)).reason())
    }

    @Test
    fun `the focus hint moves with the measurement`() {
        assertEquals("No image", Sharpness.focusHint(0.0))
        assertTrue(Sharpness.focusHint(4.0).contains("Very soft"))
        assertEquals("Sharp", Sharpness.focusHint(1000.0))
    }

    @Test
    fun `a real blur sequence ranks in order`() {
        // End to end over the arithmetic: three progressively blurred copies
        // of one subject must rank exactly as they were blurred.
        val variances = listOf(
            measure(stripes()),
            measure(blurred(stripes(), 1)),
            measure(blurred(stripes(), 3))
        )

        val comparison = Sharpness.compare(variances)
        assertEquals(0, comparison.frames.maxByOrNull { it.ratio }!!.index)
        assertTrue(
            "ratios must decrease: " + comparison.frames.map { (it.ratio * 100).roundToInt() },
            comparison.frames[0].ratio > comparison.frames[1].ratio &&
                comparison.frames[1].ratio > comparison.frames[2].ratio
        )
        assertFalse("the heavily blurred frame should be dropped",
            comparison.frames[2].usable)
    }
}
