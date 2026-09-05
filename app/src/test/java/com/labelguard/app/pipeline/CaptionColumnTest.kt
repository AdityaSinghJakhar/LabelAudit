package com.labelguard.app.pipeline

import com.labelguard.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Captions, and the two ways they were misread.
 *
 * The lines here are transcribed from a real scan of a Gokul namkeen pack —
 * the one whose report passed a batch check on a pack with no batch number.
 * OCR read "BATCH NO." as "BATCH N0.", a digit zero for the letter O, and set
 * the label's two columns as separate blocks so every value arrived several
 * lines away from its caption.
 *
 * Both defects point the same way: a declaration reported that the pack never
 * made, or a blank reported where the pack declared something.
 */
class CaptionColumnTest {

    /** Caption column on the left, values on the right, as the pack prints them. */
    private fun captionLine(text: String, row: Int) =
        OcrLine(text, Box(60, row * 100, 300, row * 100 + 40))

    private fun valueLine(text: String, row: Int) =
        OcrLine(text, Box(340, row * 100, 560, row * 100 + 40))

    // ------------------------------------------- the pack as it was scanned

    /**
     * Exactly what the scanner returned, in the order it returned it: the
     * caption column first, then a single stray value line for net weight.
     */
    private val gokulAsScanned = listOf(
        captionLine("NET WEIGHT", 1),
        OcrLine("MAX RETAIL PRICE ₹ : 140/-", Box(60, 200, 560, 240)),
        OcrLine("(INCL. OF ALL TAXES)", Box(60, 300, 560, 340)),
        OcrLine("UNIT SALE PRICE: 0.28 Per g.", Box(60, 400, 560, 440)),
        captionLine("BATCH N0.", 5),
        captionLine("MFG. DATE", 6),
        captionLine("USE BY", 7),
        valueLine(": 500g.", 1),
        OcrLine("BEST BEFORE 2 MONTHS FROM", Box(60, 900, 560, 940)),
        OcrLine("THE DATE OF PACKING", Box(60, 1000, 560, 1040))
    )

    @Test
    fun `a blank batch caption is not read as its own value`() {
        // The bug: "batch no" failed to match "batch n0.", the bare "batch"
        // anchor took over, and "N0." was left behind to pass as a batch code
        // on a pack that declares none.
        val batch = FieldExtractor.extract(gokulAsScanned)["batch_number"]

        assertTrue("the caption is printed, so it must be reported", batch != null)
        assertTrue(
            "expected a blank caption, got \"${batch!!.value}\"",
            batch.anchorOnly
        )
        assertEquals("", batch.value)
    }

    @Test
    fun `a blank date caption stays blank`() {
        val mfg = FieldExtractor.extract(gokulAsScanned)["mfg_date"]

        assertTrue(mfg != null)
        assertTrue("MFG. DATE is empty on this pack", mfg!!.anchorOnly)
    }

    @Test
    fun `a value in the next column is not stolen by an unrelated caption`() {
        // ": 500g." belongs to NET WEIGHT, three rows above BATCH N0. Row
        // alignment is what stops the batch check claiming it.
        val batch = FieldExtractor.extract(gokulAsScanned)["batch_number"]

        assertFalse("500g is the net weight, not a batch code", batch!!.value.contains("500"))
    }

    // --------------------------------------------- the two-column layout

    @Test
    fun `a value beside its caption is found`() {
        // The same pack with the fields filled in, which is how the printer
        // ships them when the line is running. Reading only the caption's own
        // line reported these blank and failed a compliant pack.
        val filled = listOf(
            captionLine("BATCH N0.", 5),
            captionLine("MFG. DATE", 6),
            valueLine(": B2411", 5),
            valueLine(": 03/2026", 6)
        )

        val fields = FieldExtractor.extract(filled)

        assertEquals("B2411", fields["batch_number"]?.value)
        assertFalse(fields["batch_number"]!!.anchorOnly)
        assertEquals("03/2026", fields["mfg_date"]?.value)
        assertFalse(fields["mfg_date"]!!.anchorOnly)
    }

    @Test
    fun `a value on the row below is not claimed`() {
        // Drift of a whole row means the pairing is a guess. A declaration the
        // pack never made is worse than reporting the field blank.
        val misaligned = listOf(
            captionLine("BATCH N0.", 5),
            valueLine(": B2411", 7)
        )

        assertTrue(FieldExtractor.extract(misaligned)["batch_number"]!!.anchorOnly)
    }

    @Test
    fun `a value to the left of its caption is not claimed`() {
        val wrongSide = listOf(
            OcrLine("BATCH N0.", Box(340, 500, 560, 540)),
            OcrLine(": B2411", Box(60, 500, 300, 540))
        )

        assertTrue(FieldExtractor.extract(wrongSide)["batch_number"]!!.anchorOnly)
    }

    @Test
    fun `another field's caption is never taken as this one's value`() {
        // MFG. DATE sits on the same row here. Its value belongs to it.
        val adjacent = listOf(
            captionLine("BATCH N0.", 5),
            OcrLine("MFG. DATE : 03/2026", Box(340, 500, 700, 540))
        )

        assertTrue(
            "the batch check must not claim the manufacture date",
            FieldExtractor.extract(adjacent)["batch_number"]!!.anchorOnly
        )
    }

    @Test
    fun `a second pack in the photograph cannot supply the value`() {
        // Shelves get photographed with two packets in frame. The neighbour's
        // nutrition table shares rows with this pack's captions and is full of
        // digits; claiming one would put a batch number on the report that the
        // scanned pack never printed.
        val twoPacks = listOf(
            captionLine("BATCH N0.", 5),
            OcrLine("ENERGY 615.16 Kcal", Box(1400, 500, 1900, 540))
        )

        assertTrue(
            "a value a whole label away is not this caption's",
            FieldExtractor.extract(twoPacks)["batch_number"]!!.anchorOnly
        )
    }

    // --------------------------------------------------- caption residue

    @Test
    fun `an inline batch value still reads normally`() {
        val inline = listOf(OcrLine("BATCH NO. : B2411", Box(60, 500, 560, 540)))

        val batch = FieldExtractor.extract(inline)["batch_number"]!!
        assertEquals("B2411", batch.value)
        assertFalse(batch.anchorOnly)
    }

    @Test
    fun `a batch code that begins with the letters NO survives`() {
        // The residue strip must not eat a genuine code. "NO7" has no word
        // boundary after "NO", so it is a code, not a stranded caption word.
        val inline = listOf(OcrLine("BATCH : NO7231", Box(60, 500, 560, 540)))

        assertEquals("NO7231", FieldExtractor.extract(inline)["batch_number"]?.value)
    }

    @Test
    fun `a batch code with no digits is not a batch code`() {
        // What the pattern's comment always claimed and it never enforced.
        val wordy = listOf(OcrLine("BATCH NO. : PENDING", Box(60, 500, 560, 540)))

        assertTrue(FieldExtractor.extract(wordy)["batch_number"]!!.anchorOnly)
    }

    @Test
    fun `an absent caption reports nothing at all`() {
        // Distinct from a blank caption: the pack makes no such declaration
        // here, and inventing an empty one would be a different claim.
        assertNull(FieldExtractor.extract(listOf(OcrLine("NET WEIGHT : 500g", Box(0, 0, 10, 10))))["batch_number"])
    }

    @Test
    fun `the relative date marking is still read`() {
        // "BEST BEFORE 2 MONTHS FROM" must keep working: it is what EXPIRY-01
        // and EXPIRY-03 resolve against the packing date.
        val expiry = FieldExtractor.extract(gokulAsScanned)["expiry"]

        assertTrue("expected a relative marking, got $expiry", expiry?.relative == true)
        assertTrue(expiry!!.value.contains("2 MONTHS", ignoreCase = true))
    }
}
