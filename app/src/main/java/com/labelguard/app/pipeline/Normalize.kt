package com.labelguard.app.pipeline

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical forms for comparing extracted values.
 *
 * Ported from the Python `labelguard.normalize` so the on-device pipeline and
 * the offline eval harness agree on what "the same value" means. If the two
 * diverged, agreement metrics would measure the normaliser rather than the
 * pipeline.
 *
 *   "Rs. 45", "₹45", "45.00", "INR 45/-"  ->  money 45.0
 *   "500 g", "500g", "0.5 kg"             ->  quantity (500.0, "g")
 */
object Normalize {

    private val CURRENCY_TOKENS = listOf("₹", "rs.", "rs", "inr", "mrp", "/-")

    private val MASS_TO_G = mapOf(
        "g" to 1.0, "gm" to 1.0, "gms" to 1.0, "gram" to 1.0, "grams" to 1.0,
        "kg" to 1000.0, "kgs" to 1000.0, "kilogram" to 1000.0, "kilograms" to 1000.0,
        "mg" to 0.001
    )

    private val VOLUME_TO_ML = mapOf(
        "ml" to 1.0, "millilitre" to 1.0, "millilitres" to 1.0, "milliliter" to 1.0,
        "l" to 1000.0, "ltr" to 1000.0, "litre" to 1000.0, "litres" to 1000.0,
        "liter" to 1000.0
    )

    private val QUANTITY_RE = Regex("""(\d+(?:[.,]\d+)?)\s*([a-zA-Z]+)""")
    private val NUMBER_RE = Regex("""\d+(?:\.\d+)?""")

    /** Casefold, strip accents/punctuation noise, collapse whitespace. */
    fun text(value: String?): String {
        if (value == null) return ""
        var s = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("​", "")
            .trim()
            .lowercase(Locale.ROOT)
        // Keep letters, digits, whitespace and the few symbols that carry
        // meaning in an address or email.
        //
        // \p{M} is essential, not incidental: Devanagari vowel signs and the
        // virama (ि ा ्) are combining marks, not letters. Omitting \p{M}
        // silently strips them and turns "निर्माता" into "नरमत", which would
        // break every Hindi anchor match.
        s = s.replace(Regex("""[^\p{L}\p{N}\p{M}\s.@+_-]"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Extract a rupee amount. Returns null when no number is present — callers
     * must treat that as "not comparable", never as zero.
     */
    fun money(value: Any?): Double? {
        if (value == null) return null
        if (value is Number) return round2(value.toDouble())

        var s = Normalizer.normalize(value.toString(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        for (token in CURRENCY_TOKENS) s = s.replace(token, " ")
        s = s.replace(",", "")

        val match = NUMBER_RE.find(s) ?: return null
        return round2(match.value.toDouble())
    }

    /**
     * Return (magnitude, canonicalUnit) with mass in g and volume in ml, so
     * "0.5 kg" and "500 g" compare equal.
     */
    fun quantity(value: String?): Pair<Double, String>? {
        if (value == null) return null

        val s = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(",", "")

        val match = QUANTITY_RE.find(s) ?: return null
        val magnitude = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]

        MASS_TO_G[unit]?.let { return Pair(round4(magnitude * it), "g") }
        VOLUME_TO_ML[unit]?.let { return Pair(round4(magnitude * it), "ml") }
        return null
    }

    /** Canonical form of [value] for the named field. */
    fun field(field: String, value: Any?): Any? = when (field) {
        "mrp" -> money(value)
        "net_quantity" -> quantity(value?.toString())
        else -> text(value?.toString())
    }

    /**
     * Compare two values for a field. Two values that both normalise to null
     * are NOT equal — an unparseable value must not silently match another.
     */
    fun valuesEqual(field: String, left: Any?, right: Any?): Boolean {
        val a = field(field, left) ?: return false
        val b = field(field, right) ?: return false
        return a == b
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round4(v: Double) = Math.round(v * 10000.0) / 10000.0
}
