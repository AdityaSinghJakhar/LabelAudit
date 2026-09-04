package com.labelaudit.app.pipeline

import com.labelaudit.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FieldExtractorTest {

    private val box = Box(0, 0, 100, 30)
    private fun lines(vararg text: String) = text.map { OcrLine(it, box) }

    // ---------------------------------------------------------------- MRP

    @Test
    fun `mrp is read when anchored to a price marker`() {
        assertEquals("45.00", FieldExtractor.findMrp(lines("MRP Rs. 45.00"))!!.value)
        assertEquals("45", FieldExtractor.findMrp(lines("₹45"))!!.value)
        assertEquals("45.00", FieldExtractor.findMrp(lines("M.R.P 45.00"))!!.value)
    }

    @Test
    fun `a bare number is not treated as a price`() {
        // Inventing a price from an unanchored number would fabricate a
        // declaration the label never made.
        assertNull(FieldExtractor.findMrp(lines("500", "Batch 2026")))
    }

    // ----------------------------------------------------------- quantity

    @Test
    fun `net quantity needs both a value and a unit`() {
        assertEquals("500 g", FieldExtractor.findNetQuantity(lines("Net Qty: 500 g"))!!.value)
        assertEquals("250ml", FieldExtractor.findNetQuantity(lines("250ml"))!!.value)
        assertNull(FieldExtractor.findNetQuantity(lines("Net Qty: 500")))
    }

    @Test
    fun `an anchored quantity line is preferred over a stray one`() {
        val found = FieldExtractor.findNetQuantity(
            lines("Contains 2 kg of packaging", "Net Weight 500 g")
        )
        assertEquals("500 g", found!!.value)
    }

    // ---------------------------------------------------------- addresses

    @Test
    fun `english anchors assign roles`() {
        assertEquals(
            FieldExtractor.AddressRole.MANUFACTURER,
            FieldExtractor.tagAddress("Manufactured by: Acme Foods, Jaipur", box).role
        )
        assertEquals(
            FieldExtractor.AddressRole.PACKER,
            FieldExtractor.tagAddress("Packed by Bright Packers", box).role
        )
        assertEquals(
            FieldExtractor.AddressRole.IMPORTER,
            FieldExtractor.tagAddress("Imported by Global Traders", box).role
        )
        assertEquals(
            FieldExtractor.AddressRole.MARKETER,
            FieldExtractor.tagAddress("Marketed by Sunrise Retail", box).role
        )
    }

    @Test
    fun `devanagari anchors assign roles`() {
        assertEquals(
            FieldExtractor.AddressRole.MANUFACTURER,
            FieldExtractor.tagAddress("निर्माता: एक्मे फूड्स", box).role
        )
        assertEquals(
            FieldExtractor.AddressRole.IMPORTER,
            FieldExtractor.tagAddress("आयातकर्ता: ग्लोबल", box).role
        )
    }

    @Test
    fun `an unmatched address is unknown and never manufacturer`() {
        val tagged = FieldExtractor.tagAddress("Acme Foods, Sitapura, Jaipur 302022", box)

        assertEquals(FieldExtractor.AddressRole.UNKNOWN, tagged.role)
        assertNotEquals(FieldExtractor.AddressRole.MANUFACTURER, tagged.role)
        assertNull(tagged.anchor)
    }

    @Test
    fun `the longest anchor wins`() {
        val tagged = FieldExtractor.tagAddress("Manufactured and packed by Acme", box)
        assertEquals(FieldExtractor.AddressRole.MANUFACTURER, tagged.role)
    }

    @Test
    fun `an untagged address is not promoted into the extracted fields`() {
        val fields = FieldExtractor.extract(lines("Acme Foods, Jaipur", "MRP Rs. 45"))
        assertNull(fields["manufacturer_address"])
    }

    // ----------------------------------------------------- consumer care

    @Test
    fun `consumer care requires an anchor`() {
        val care = FieldExtractor.findConsumerCare(
            lines("Consumer care: care@acme.in 1800-123-4567")
        )
        assertNotNull(care)
        assertEquals("care@acme.in", care!!.email)
        assertNotNull(care.phone)
    }

    @Test
    fun `a stray phone number is not consumer care`() {
        assertNull(FieldExtractor.findConsumerCare(lines("Factory line 1800-999-0000")))
    }

    // ------------------------------------------------------------ end to end

    @Test
    fun `a realistic label yields the expected fields`() {
        val fields = FieldExtractor.extract(
            lines(
                "TASTY OATS",
                "MRP Rs. 45.00 (incl. of all taxes)",
                "Net Qty: 500 g",
                "Manufactured by Acme Foods, Jaipur",
                "Consumer care: care@acme.in"
            )
        )

        assertEquals("45.00", fields["mrp"]!!.value)
        assertEquals("500 g", fields["net_quantity"]!!.value)
        assertNotNull(fields["manufacturer_address"])
        assertNotNull(fields["consumer_care"])
    }
}
