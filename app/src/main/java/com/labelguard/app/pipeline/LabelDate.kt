package com.labelguard.app.pipeline

import java.time.LocalDate
import java.time.Period
import java.time.format.TextStyle
import java.util.Locale

/**
 * A date read off a label, as the *range of days it could mean*.
 *
 * Label dates are rarely a single day. "12/2025" names a month, not a date.
 * "06/07/2025" is 6 July under the Indian convention but 7 June under the
 * American one, and OCR gives no way to tell which press printed it. Treating
 * either as a point forces a guess, and a guess here is a wrong verdict on
 * somebody's stock.
 *
 * Holding the range instead makes every check conservative for free. A pack is
 * only "manufactured in the future" if its *earliest* possible day is still
 * ahead; only "expired" if its *latest* possible day has already gone. Where
 * the range straddles the answer, the honest outcome is NEEDS_REVIEW, and the
 * caller can see that from [certain].
 */
data class LabelDate(
    val earliest: LocalDate,
    val latest: LocalDate,
    val precision: Precision,
    /** The text this was read from, for the evidence line in the report. */
    val text: String
) {
    enum class Precision {
        /** A single day, unambiguously written. */
        DAY,

        /** A month and year; the day is not declared. */
        MONTH,

        /** Three numbers, either of which could be the day. */
        AMBIGUOUS_ORDER
    }

    /** True when the range is a single day, so a comparison cannot straddle. */
    val certain: Boolean get() = earliest == latest

    /** Certainly after [other] — the whole range is. */
    fun certainlyAfter(other: LabelDate): Boolean = earliest.isAfter(other.latest)

    /** Certainly before [day] — the whole range is. */
    fun certainlyBefore(day: LocalDate): Boolean = latest.isBefore(day)

    /** Could be after [day], without being certainly so. */
    fun possiblyAfter(day: LocalDate): Boolean = latest.isAfter(day)

    /** Shift the whole range by a period, for "best before 9 months from packing". */
    operator fun plus(period: Period): LabelDate = copy(
        earliest = earliest.plus(period),
        latest = latest.plus(period),
        text = "$text + $period"
    )

    fun describe(): String = when (precision) {
        Precision.DAY -> earliest.toString()
        Precision.MONTH -> "${earliest.month.getDisplayName(TextStyle.SHORT, Locale.UK)} ${earliest.year}"
        Precision.AMBIGUOUS_ORDER -> "$earliest or $latest"
    }

    companion object {

        private val MONTH_NAMES = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10,
            "nov" to 11, "dec" to 12
        )

        private val SEP = """[/.\-\s]"""

        // 06/12/2025 — three numbers.
        private val DMY = Regex("""\b(\d{1,2})$SEP{1,2}(\d{1,2})$SEP{1,2}(\d{2,4})\b""")

        // 12 DEC 2025 / DEC 2025 / 12-DEC-25
        private val WITH_NAME = Regex(
            """\b(?:(\d{1,2})$SEP{0,2})?([a-z]{3,9})$SEP{0,2}(\d{2,4})\b""",
            RegexOption.IGNORE_CASE
        )

        // 12/2025 — month and year only.
        private val MY = Regex("""\b(\d{1,2})$SEP{1,2}(\d{4})\b""")

        // 2025-12 — ISO year and month.
        private val ISO = Regex("""\b(\d{4})-(\d{1,2})\b""")

        // "9 months from packing", "best before 12 months"
        private val PERIOD_RE = Regex(
            """(\d{1,3})\s*(day|days|week|weeks|month|months|year|years)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Parse a label date, or null when nothing date-shaped is present.
         *
         * Returning null is a real answer: it makes the check NOT_ASSESSABLE
         * rather than inventing a date to compare against.
         */
        fun parse(raw: String?): LabelDate? {
            if (raw.isNullOrBlank()) return null
            val s = raw.trim()

            ISO.find(s)?.let { m ->
                val (y, mo) = m.destructured
                return month(y.toInt(), mo.toInt(), s)
            }

            DMY.find(s)?.let { m ->
                val (a, b, y) = m.destructured
                val year = fullYear(y.toInt())
                val first = a.toInt()
                val second = b.toInt()

                val asDayMonth = day(year, second, first, s)      // Indian: DD/MM
                val asMonthDay = day(year, first, second, s)      // American: MM/DD

                return when {
                    // Only one reading is a real calendar date.
                    asDayMonth != null && asMonthDay == null -> asDayMonth
                    asMonthDay != null && asDayMonth == null -> asMonthDay
                    asDayMonth == null -> null
                    // Both readings valid and identical (e.g. 05/05).
                    asDayMonth.earliest == asMonthDay!!.earliest -> asDayMonth
                    // Genuinely ambiguous: keep both, span the range.
                    else -> LabelDate(
                        earliest = minOf(asDayMonth.earliest, asMonthDay.earliest),
                        latest = maxOf(asDayMonth.earliest, asMonthDay.earliest),
                        precision = Precision.AMBIGUOUS_ORDER,
                        text = s
                    )
                }
            }

            WITH_NAME.find(s)?.let { m ->
                val (d, name, y) = m.destructured
                val mo = MONTH_NAMES[name.lowercase(Locale.ROOT).take(4)]
                    ?: MONTH_NAMES[name.lowercase(Locale.ROOT).take(3)]
                if (mo != null) {
                    val year = fullYear(y.toInt())
                    return if (d.isEmpty()) month(year, mo, s) else day(year, mo, d.toInt(), s)
                }
            }

            MY.find(s)?.let { m ->
                val (mo, y) = m.destructured
                return month(y.toInt(), mo.toInt(), s)
            }

            return null
        }

        /**
         * A shelf life stated as a duration — "best before 9 months from
         * packing" — which yields a date only once the date it counts from is
         * known.
         */
        fun parsePeriod(raw: String?): Period? {
            val m = PERIOD_RE.find(raw ?: return null) ?: return null
            val n = m.groupValues[1].toInt()
            return when (m.groupValues[2].lowercase(Locale.ROOT).trimEnd('s')) {
                "day" -> Period.ofDays(n)
                "week" -> Period.ofWeeks(n)
                "month" -> Period.ofMonths(n)
                "year" -> Period.ofYears(n)
                else -> null
            }
        }

        /** Two-digit years are this century; food labels never predate it. */
        private fun fullYear(y: Int) = if (y < 100) 2000 + y else y

        private fun month(year: Int, month: Int, text: String): LabelDate? {
            if (month !in 1..12 || year !in 1900..2200) return null
            val first = LocalDate.of(year, month, 1)
            return LabelDate(first, first.withDayOfMonth(first.lengthOfMonth()),
                Precision.MONTH, text)
        }

        private fun day(year: Int, month: Int, day: Int, text: String): LabelDate? {
            if (month !in 1..12 || year !in 1900..2200) return null
            if (day < 1 || day > LocalDate.of(year, month, 1).lengthOfMonth()) return null
            val d = LocalDate.of(year, month, day)
            return LabelDate(d, d, Precision.DAY, text)
        }
    }
}
