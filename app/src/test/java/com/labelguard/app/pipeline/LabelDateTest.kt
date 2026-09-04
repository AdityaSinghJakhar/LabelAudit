package com.labelguard.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Period

/**
 * Reading dates off labels.
 *
 * The property under test is not "does it parse" but "does it refuse to
 * over-claim". A month-only marking must stay a month, and a genuinely
 * ambiguous one must stay ambiguous, because every later verdict is computed
 * from this range.
 */
class LabelDateTest {

    private fun parse(s: String?) = LabelDate.parse(s)

    // ---------------------------------------------------------- precision

    @Test
    fun `a month and year covers the whole month`() {
        val d = parse("12/2025")!!
        assertEquals(LocalDate.of(2025, 12, 1), d.earliest)
        assertEquals(LocalDate.of(2025, 12, 31), d.latest)
        assertEquals(LabelDate.Precision.MONTH, d.precision)
    }

    @Test
    fun `February is not given thirty-one days`() {
        assertEquals(LocalDate.of(2025, 2, 28), parse("02/2025")!!.latest)
        assertEquals(LocalDate.of(2024, 2, 29), parse("02/2024")!!.latest)
    }

    @Test
    fun `a full date is a single day`() {
        val d = parse("25/12/2025")!!
        assertEquals(LocalDate.of(2025, 12, 25), d.earliest)
        assertTrue(d.certain)
    }

    @Test
    fun `a named month is read`() {
        val d = parse("BEST BEFORE DEC 2025")!!
        assertEquals(LocalDate.of(2025, 12, 1), d.earliest)
        assertEquals(LabelDate.Precision.MONTH, d.precision)
    }

    @Test
    fun `a named month with a day is a single day`() {
        assertEquals(LocalDate.of(2025, 9, 12), parse("12 SEP 2025")!!.earliest)
        assertEquals(LocalDate.of(2025, 9, 12), parse("12-SEPT-25")!!.earliest)
    }

    // --------------------------------------------------------- ambiguity

    @Test
    fun `an ambiguous day-month order keeps both readings`() {
        // 6 July under the Indian convention, 7 June under the American one.
        // OCR cannot tell which press printed it, so neither is discarded.
        val d = parse("06/07/2025")!!
        assertEquals(LocalDate.of(2025, 6, 7), d.earliest)
        assertEquals(LocalDate.of(2025, 7, 6), d.latest)
        assertEquals(LabelDate.Precision.AMBIGUOUS_ORDER, d.precision)
        assertFalse(d.certain)
    }

    @Test
    fun `a day past twelve settles the order`() {
        // 25 cannot be a month, so this is unambiguously 25 December.
        val d = parse("25/12/2025")!!
        assertTrue(d.certain)
        assertEquals(LocalDate.of(2025, 12, 25), d.earliest)
    }

    @Test
    fun `the same number twice is not ambiguous`() {
        val d = parse("05/05/2025")!!
        assertTrue(d.certain)
        assertEquals(LocalDate.of(2025, 5, 5), d.earliest)
    }

    // ------------------------------------------------------------ refusal

    @Test
    fun `text with no date yields nothing`() {
        assertNull(parse("BEST BEFORE"))
        assertNull(parse(""))
        assertNull(parse(null))
    }

    @Test
    fun `an impossible date is not invented`() {
        // 32/13 is not a date under either reading.
        assertNull(parse("32/13/2025"))
        assertNull(parse("00/00/2025"))
    }

    @Test
    fun `a two digit year is this century`() {
        assertEquals(2025, parse("12/03/25")!!.earliest.year)
    }

    // ------------------------------------------------------- comparisons

    @Test
    fun `a month-only marking is only expired once the month has passed`() {
        val dec = parse("12/2025")!!
        // Mid-month: the pack could still be within its marking.
        assertFalse(dec.certainlyBefore(LocalDate.of(2025, 12, 15)))
        assertTrue(dec.certainlyBefore(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `certainly after requires the whole range to clear`() {
        val mfg = parse("12/2025")!!
        val overlapping = parse("12/2025")!!
        assertFalse(
            "same month cannot be proven later",
            overlapping.certainlyAfter(mfg)
        )
        assertTrue(parse("01/2026")!!.certainlyAfter(mfg))
    }

    // ---------------------------------------------------------- periods

    @Test
    fun `a shelf life period is read`() {
        assertEquals(Period.ofMonths(9), LabelDate.parsePeriod("BEST BEFORE 9 MONTHS FROM PACKING"))
        assertEquals(Period.ofDays(45), LabelDate.parsePeriod("best before 45 days"))
        assertEquals(Period.ofYears(2), LabelDate.parsePeriod("Best before 2 years from mfg"))
    }

    @Test
    fun `a period without a number is not a period`() {
        assertNull(LabelDate.parsePeriod("BEST BEFORE END OF"))
    }

    @Test
    fun `adding a period shifts the whole range`() {
        val resolved = parse("03/2025")!! + Period.ofMonths(9)
        assertEquals(LocalDate.of(2025, 12, 1), resolved.earliest)
        assertEquals(LocalDate.of(2025, 12, 31), resolved.latest)
    }
}
