package com.labelaudit.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusTest {

    private val box = Box(10, 20, 60, 40)

    private fun obs(value: String) = Consensus.Observation(value, box)
    private fun frame(vararg pairs: Pair<String, String>) =
        pairs.associate { (k, v) -> k to obs(v) }

    @Test
    fun `majority of five reaches consensus despite differing renderings`() {
        val result = Consensus.build(
            listOf(
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "₹45"),
                frame("mrp" to "45.00"),
                frame("mrp" to "Rs. 49"),
                frame("mrp" to "Rs. 61")
            )
        )

        val agreed = result.fields["mrp"]
        assertNotNull(agreed)
        assertEquals(3, agreed!!.agreement)
        assertTrue(result.failures["mrp"] == null)
    }

    @Test
    fun `two of five is not consensus`() {
        val result = Consensus.build(
            listOf(
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 49"),
                frame("mrp" to "Rs. 52"),
                frame("mrp" to "Rs. 61")
            )
        )

        assertNull(result.fields["mrp"])
        assertEquals("ocr_no_consensus", result.failures["mrp"]!!.reason)
        assertEquals(4, result.failures["mrp"]!!.candidates.size)
    }

    @Test
    fun `three frames require unanimity`() {
        val unanimous = Consensus.build(
            listOf(frame("mrp" to "Rs. 45"), frame("mrp" to "₹45"), frame("mrp" to "45.00"))
        )
        assertNotNull(unanimous.fields["mrp"])

        val split = Consensus.build(
            listOf(frame("mrp" to "Rs. 45"), frame("mrp" to "Rs. 45"), frame("mrp" to "Rs. 49"))
        )
        assertNull(split.fields["mrp"])
    }

    @Test
    fun `confidence reflects how many frames agreed`() {
        val all5 = Consensus.build(List(5) { frame("mrp" to "Rs. 45") })
        assertEquals(1.0f, all5.fields["mrp"]!!.confidence, 0.001f)

        val threeOf5 = Consensus.build(
            listOf(
                frame("mrp" to "Rs. 45"), frame("mrp" to "Rs. 45"), frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 49"), frame("mrp" to "Rs. 61")
            )
        )
        assertEquals(0.6f, threeOf5.fields["mrp"]!!.confidence, 0.001f)
    }

    @Test
    fun `quantity units are normalised before voting`() {
        val result = Consensus.build(
            listOf(
                frame("net_quantity" to "500 g"),
                frame("net_quantity" to "500g"),
                frame("net_quantity" to "0.5 kg")
            )
        )
        assertNotNull(result.fields["net_quantity"])
    }

    @Test
    fun `unparseable values never win however many frames agree`() {
        val result = Consensus.build(
            listOf(frame("mrp" to "|||"), frame("mrp" to "|||"), frame("mrp" to "|||"))
        )

        assertNull(result.fields["mrp"])
        assertEquals("ocr_no_consensus", result.failures["mrp"]!!.reason)
    }

    @Test
    fun `a field missing from some frames still counts the votes it has`() {
        val result = Consensus.build(
            listOf(
                frame("mrp" to "Rs. 45"),
                emptyMap(),
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 45"),
                emptyMap()
            )
        )
        assertEquals(3, result.fields["mrp"]!!.agreement)
    }

    @Test
    fun `frame count outside one to five is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Consensus.build(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Consensus.build(List(6) { frame("mrp" to "Rs. 45") })
        }
    }

    @Test
    fun `a single frame is accepted for bulk upload but agrees only with itself`() {
        // Bulk upload has one image per product, so the threshold drops to
        // what is available. The agreement ratio is what tells a reviewer the
        // evidence is thinner than a five-frame camera scan.
        val result = Consensus.build(listOf(frame("mrp" to "Rs. 45")))
        val mrp = result.fields["mrp"]

        assertNotNull(mrp)
        assertEquals(1, mrp!!.agreement)
        assertEquals(1, mrp.frames)
    }

    @Test
    fun `two frames that disagree still reach no consensus`() {
        val result = Consensus.build(
            listOf(frame("mrp" to "Rs. 45"), frame("mrp" to "Rs. 61"))
        )
        assertNull(result.fields["mrp"])
        assertEquals("ocr_no_consensus", result.failures["mrp"]!!.reason)
    }

    @Test
    fun `a blank caption reaches consensus as a blank, not as a value`() {
        val blank = Consensus.Observation("", box, anchorOnly = true)
        val result = Consensus.build(List(3) { mapOf("mfg_date" to blank) })
        val agreed = result.fields["mfg_date"]

        assertNotNull(agreed)
        assertTrue("a blank caption must stay flagged", agreed!!.anchorOnly)
    }

    // ------------------------------------------------------ multiple sides

    @Test
    fun `sides contribute complementary fields`() {
        // The brand is on the front and the price on the back; neither absence
        // is a disagreement.
        val front = Consensus.build(List(3) { frame("net_quantity" to "500 g") })
        val back = Consensus.build(List(3) { frame("mrp" to "Rs. 140") })

        val merged = Consensus.merge(listOf(front, back))

        assertNotNull(merged.fields["net_quantity"])
        assertNotNull(merged.fields["mrp"])
        assertTrue(merged.failures.isEmpty())
    }

    @Test
    fun `sides that read the same field differently are reported as a conflict`() {
        val front = Consensus.build(List(3) { frame("mrp" to "Rs. 140") })
        val back = Consensus.build(List(3) { frame("mrp" to "Rs. 145") })

        val merged = Consensus.merge(listOf(front, back))

        assertNull(merged.fields["mrp"])
        assertEquals("sides_disagree", merged.failures["mrp"]!!.reason)
        assertEquals(2, merged.failures["mrp"]!!.candidates.size)
    }

    @Test
    fun `sides reading the same value agree`() {
        val front = Consensus.build(List(3) { frame("mrp" to "Rs. 140") })
        val back = Consensus.build(List(3) { frame("mrp" to "₹140") })

        val merged = Consensus.merge(listOf(front, back))

        // Different renderings of the same price are not a conflict.
        assertNotNull(merged.fields["mrp"])
        assertTrue(merged.failures.isEmpty())
    }

    @Test
    fun `a blank caption on one side loses to a real value on another`() {
        // The FSSAI caption may be on the front with the number printed on the
        // back. That is one declaration split across faces, not two sides
        // disagreeing, and treating it as a conflict discarded a value the
        // pack plainly carries.
        val blank = Consensus.Observation("", box, anchorOnly = true)
        val front = Consensus.build(List(3) { mapOf("fssai_licence" to blank) })
        val back = Consensus.build(List(3) { frame("fssai_licence" to "10015022001234") })

        val merged = Consensus.merge(listOf(front, back))
        val agreed = merged.fields["fssai_licence"]

        assertNotNull("the printed number should survive the merge", agreed)
        assertEquals("10015022001234", agreed!!.value)
        assertTrue(merged.failures.isEmpty())
    }

    @Test
    fun `blank captions on every side stay blank`() {
        val blank = Consensus.Observation("", box, anchorOnly = true)
        val front = Consensus.build(List(3) { mapOf("mfg_date" to blank) })
        val back = Consensus.build(List(3) { mapOf("mfg_date" to blank) })

        val agreed = Consensus.merge(listOf(front, back)).fields["mfg_date"]

        assertNotNull(agreed)
        assertTrue("a genuinely blank caption must stay flagged", agreed!!.anchorOnly)
    }

    @Test
    fun `merging a single side changes nothing`() {
        val only = Consensus.build(List(3) { frame("mrp" to "Rs. 140") })
        assertEquals(only, Consensus.merge(listOf(only)))
    }

    @Test
    fun `disagreeing candidates are reported for review`() {
        val result = Consensus.build(
            listOf(
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 45"),
                frame("mrp" to "Rs. 49")
            )
        )

        val candidates = result.failures["mrp"]!!.candidates
        assertEquals(2, candidates.size)
        assertEquals(2, candidates.first().votes)
    }
}
