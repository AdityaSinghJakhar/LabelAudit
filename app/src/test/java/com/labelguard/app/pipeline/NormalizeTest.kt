package com.labelguard.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeTest {

    @Test
    fun `money accepts the renderings a label actually uses`() {
        assertEquals(45.0, Normalize.money("Rs. 45")!!, 0.001)
        assertEquals(45.0, Normalize.money("₹45")!!, 0.001)
        assertEquals(45.0, Normalize.money("45.00")!!, 0.001)
        assertEquals(45.0, Normalize.money("INR 45/-")!!, 0.001)
        assertEquals(45.0, Normalize.money("MRP ₹45.00")!!, 0.001)
    }

    @Test
    fun `money strips thousands separators`() {
        assertEquals(1250.0, Normalize.money("Rs. 1,250")!!, 0.001)
    }

    @Test
    fun `money returns null when there is no number`() {
        assertNull(Normalize.money("MRP"))
        assertNull(Normalize.money("|||"))
        assertNull(Normalize.money(null))
    }

    @Test
    fun `quantity converts mass to grams`() {
        assertEquals(Pair(500.0, "g"), Normalize.quantity("500 g"))
        assertEquals(Pair(500.0, "g"), Normalize.quantity("500g"))
        assertEquals(Pair(500.0, "g"), Normalize.quantity("0.5 kg"))
        assertEquals(Pair(500.0, "g"), Normalize.quantity("500 gm"))
    }

    @Test
    fun `quantity converts volume to millilitres`() {
        assertEquals(Pair(1000.0, "ml"), Normalize.quantity("1 l"))
        assertEquals(Pair(250.0, "ml"), Normalize.quantity("250 ml"))
    }

    @Test
    fun `quantity rejects an unknown unit`() {
        assertNull(Normalize.quantity("500 widgets"))
        assertNull(Normalize.quantity("500"))
    }

    @Test
    fun `mass and volume never compare equal`() {
        assertFalse(Normalize.valuesEqual("net_quantity", "500 g", "500 ml"))
    }

    @Test
    fun `two unparseable values are not equal`() {
        // The critical case: garbage must not silently match other garbage.
        assertFalse(Normalize.valuesEqual("mrp", "|||", "|||"))
        assertFalse(Normalize.valuesEqual("net_quantity", "abc", "abc"))
    }

    @Test
    fun `equal money renderings compare equal`() {
        assertTrue(Normalize.valuesEqual("mrp", "Rs. 45", "₹45"))
        assertTrue(Normalize.valuesEqual("mrp", "45.00", "MRP Rs 45"))
    }

    @Test
    fun `text normalisation collapses case and punctuation`() {
        assertEquals("acme foods jaipur", Normalize.text("ACME  Foods, Jaipur!"))
    }

    @Test
    fun `text normalisation keeps email punctuation`() {
        assertEquals("care@acme.in", Normalize.text("Care@Acme.in"))
    }

    @Test
    fun `text normalisation preserves devanagari`() {
        assertTrue(Normalize.text("निर्माता एक्मे").contains("निर्माता"))
    }
}
