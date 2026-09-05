package com.labelguard.app.pipeline

import com.labelguard.app.ocr.OcrLine

/**
 * Turns OCR lines into candidate field values for one frame.
 *
 * Role-tagged address parsing is the part that matters most here: an address
 * is given a role only when an anchor phrase actually says so. An address
 * block with no recognisable anchor is tagged UNKNOWN — never defaulted to
 * manufacturer. Rule 6(1)(a) distinguishes manufacturer from packer from
 * importer, so promoting an unlabelled block would manufacture a compliance
 * fact the label does not state.
 */
object FieldExtractor {

    /**
     * Fields this extractor can actually produce.
     *
     * A rule naming a field outside this set is not evaluable: the pipeline
     * has no way to look for it, which is different from the label not
     * declaring it. The engine uses this to report NOT_ASSESSABLE instead of
     * asserting a violation the evidence cannot support.
     */
    val SUPPORTED_FIELDS = setOf(
        "mrp",
        "net_quantity",
        "manufacturer_address",
        "consumer_care",
        "mfg_date",
        "batch_number",
        "expiry",
        "fssai_licence",
        "tax_inclusive",
        "brand"
    )

    enum class AddressRole { MANUFACTURER, PACKER, IMPORTER, MARKETER, UNKNOWN }

    data class TaggedAddress(
        val role: AddressRole,
        val text: String,
        val box: Box,
        /** The literal anchor that assigned the role; null when UNKNOWN. */
        val anchor: String?,
        /**
         * The address itself, with the role caption removed.
         *
         * A pack printing "निर्माता:" and nothing else has named a role and
         * given no address. That is a declaration it failed to make, and it
         * has to read differently from one that carries a real address —
         * otherwise the caption alone passes the check.
         */
        val body: String = ""
    ) {
        val captionOnly: Boolean get() = anchor != null && body.isBlank()
    }

    data class ConsumerCare(
        val text: String,
        val box: Box,
        val phone: String?,
        val email: String?
    )

    // Anchor phrases, English and Devanagari. Longest match wins, so
    // "manufactured and packed by" is not mistaken for "packed by".
    private val ROLE_ANCHORS: Map<AddressRole, List<String>> = mapOf(
        AddressRole.MANUFACTURER to listOf(
            "manufactured by", "manufactured & packed by", "manufactured and packed by",
            "mfd by", "mfd. by", "mfg by", "mfg. by", "manufacturer",
            "निर्माता", "निर्मित द्वारा", "द्वारा निर्मित"
        ),
        AddressRole.PACKER to listOf(
            "packed by", "packaged by", "repacked by", "packer",
            "पैक किया गया", "पैकर", "द्वारा पैक"
        ),
        AddressRole.IMPORTER to listOf(
            "imported by", "importer",
            "आयातकर्ता", "द्वारा आयातित"
        ),
        AddressRole.MARKETER to listOf(
            "marketed by", "marketer", "sold by",
            "विपणक", "द्वारा विपणित"
        )
    )

    private val CONSUMER_CARE_ANCHORS = listOf(
        "consumer care", "customer care", "consumer complaints", "for complaints",
        "उपभोक्ता शिकायत", "ग्राहक सेवा"
    )

    private val PHONE_RE = Regex("""(?:\+91[\s-]?)?(?:\d[\s-]?){10,13}""")
    private val EMAIL_RE = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")

    // "MRP" / "Rs" / "₹" followed by a number, or a bare price-looking number
    // on a line that mentions MRP.
    private val MRP_LINE_RE = Regex(
        """(?:mrp|m\.r\.p|rs\.?|₹|inr|अधिकतम\s*खुदरा\s*मूल्य)""",
        RegexOption.IGNORE_CASE
    )
    private val PRICE_RE = Regex("""(?:₹|rs\.?|inr)?\s*(\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

    private val QTY_RE = Regex(
        """\d+(?:[.,]\d+)?\s*(?:g|gm|gms|gram|grams|kg|kgs|mg|ml|l|ltr|litre|litres|liter)\b""",
        RegexOption.IGNORE_CASE
    )
    private val QTY_CONTEXT_RE = Regex(
        """(?:net\s*(?:qty|quantity|wt|weight)|शुद्ध\s*वजन|मात्रा)""",
        RegexOption.IGNORE_CASE
    )

    // Captions for the dated declarations. Each is paired with a value pattern
    // so a printed caption with nothing after it can be told apart from an
    // absent caption — the defect this ruleset most needs to catch.
    private val MFG_DATE_ANCHORS = listOf(
        "mfg. date", "mfg date", "mfg.date", "manufacture date",
        "date of manufacture", "date of packing", "packed on", "pkd on", "pkd",
        "निर्माण तिथि", "पैकिंग तिथि", "पैक करने की तिथि"
    )
    private val BATCH_ANCHORS = listOf(
        "batch no", "batch number", "batch", "lot no", "lot number",
        "b. no", "b.no", "code no",
        "बैच", "बैच सं"
    )
    private val EXPIRY_ANCHORS = listOf(
        "use by", "used by", "best before", "expiry", "exp. date", "exp date",
        "उपयोग की तिथि", "सर्वोत्तम"
    )

    /**
     * Every caption this extractor knows, so a value hunted in the next column
     * is never another field's caption.
     */
    private val ALL_CAPTION_ANCHORS: List<String> =
        CONSUMER_CARE_ANCHORS + MFG_DATE_ANCHORS + BATCH_ANCHORS + EXPIRY_ANCHORS

    /**
     * Words that only ever belong to a caption, left stranded when OCR
     * corrupts one.
     *
     * "BATCH NO." came back as "BATCH N0." — a digit zero for the letter O.
     * The anchor "batch no" then failed to match and the bare "batch" anchor
     * took over, leaving "N0." behind to be read as the batch code. The pack
     * had no batch number at all, and the check passed on the caption's own
     * second word.
     *
     * The word boundary matters: it keeps a genuine code like "N0123" or
     * "NO7" intact while stripping a stranded "N0." or "NO :".
     */
    private val CAPTION_RESIDUE_RE = Regex(
        """^(?:no|n0|nos|number|num|lot|code|sr|s\.?no)\b\.?""",
        RegexOption.IGNORE_CASE
    )

    private const val SEPARATORS = " :-.\u2013\u2014"

    /**
     * Drop separators and any stranded caption words from the text following
     * an anchor.
     */
    private fun stripCaptionResidue(raw: String): String {
        var text = raw.trim { it in SEPARATORS }
        while (true) {
            val residue = CAPTION_RESIDUE_RE.find(text) ?: break
            text = text.substring(residue.value.length).trim { it in SEPARATORS }
        }
        return text
    }
    private val FSSAI_ANCHOR_RE = Regex(
        """fssai|\blic\b\.?|licence\s*no|license\s*no""",
        RegexOption.IGNORE_CASE
    )

    /** A date in any of the forms Indian packs commonly print. */
    private val DATE_VALUE_RE = Regex(
        """\d{1,2}\s*[/.\-]\s*\d{1,2}\s*[/.\-]\s*\d{2,4}""" +
            """|\d{1,2}\s*[/.\-]\s*\d{2,4}""" +
            """|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s*[.\-/ ]?\s*\d{2,4}""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Batch codes are alphanumeric and contain at least one digit.
     *
     * The digit is what the comment here always claimed and the pattern never
     * enforced: without it, "NO" left over from a corrupted "BATCH NO."
     * qualified as a batch code, and so would any other stray word.
     */
    private val BATCH_VALUE_RE = Regex(
        """\b(?=[A-Z0-9\-/]*\d)[A-Z0-9][A-Z0-9\-/]{1,19}\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A run of digits, possibly spaced or hyphenated by OCR.
     *
     * FSSAI licence numbers are exactly 14 digits, which is distinctive on a
     * food label: consumer-care numbers are 10, batch codes shorter still.
     * Matching on that shape finds the licence wherever it is printed, which
     * matters because it sits under the logo rather than after a caption.
     */
    private val DIGIT_RUN_RE = Regex("""\d[\d\s\-]{8,26}\d""")

    private const val FSSAI_DIGITS = 14

    // Deliberately loose. This wording is printed small and low-contrast on
    // most packs, and OCR punctuates it inconsistently — "INCL. OF ALL
    // TAXES", "incl of all tax", "(inclusive of all taxes)". Requiring an
    // exact phrase made a compliant pack read as a violation.
    private val TAX_INCLUSIVE_RE = Regex(
        """incl.{0,20}?tax|all\s*tax|सभी\s*करों\s*सहित""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extract candidate field values from one frame's OCR lines.
     *
     * Only fields actually found are returned. A field that is absent here
     * contributes no vote to consensus, which is different from a field whose
     * frames disagreed.
     */
    fun extract(lines: List<OcrLine>): Map<String, Consensus.Observation> {
        val fields = mutableMapOf<String, Consensus.Observation>()

        findMrp(lines)?.let { fields["mrp"] = it }
        findNetQuantity(lines)?.let { fields["net_quantity"] = it }

        val addresses = tagAddresses(lines)
        addresses.firstOrNull { it.role == AddressRole.MANUFACTURER }?.let {
            // A role named with no address under it is the same defect as
            // "BATCH NO. :" with nothing after it, and is reported the same way.
            fields["manufacturer_address"] = Consensus.Observation(
                value = if (it.captionOnly) "" else it.text,
                box = it.box,
                anchorOnly = it.captionOnly
            )
        }

        findConsumerCare(lines)?.let {
            fields["consumer_care"] = Consensus.Observation(it.text, it.box)
        }

        findCaptioned(lines, MFG_DATE_ANCHORS, DATE_VALUE_RE)
            ?.let { fields["mfg_date"] = it }
        findCaptioned(lines, BATCH_ANCHORS, BATCH_VALUE_RE)
            ?.let { fields["batch_number"] = it }
        findCaptioned(lines, EXPIRY_ANCHORS, DATE_VALUE_RE, allowRelative = true)
            ?.let { fields["expiry"] = it }
        findFssai(lines)?.let { fields["fssai_licence"] = it }

        findTaxInclusive(lines)?.let { fields["tax_inclusive"] = it }

        findBrand(lines)?.let { fields["brand"] = it }

        return fields
    }

    /**
     * The tax-inclusive wording.
     *
     * Printed small, low-contrast and parenthesised, so OCR frequently breaks
     * "(INCL. OF ALL TAXES)" across two lines or attaches part of it to the
     * price above. Matching line by line therefore missed wording that is
     * plainly on the pack, so the joined text is checked as a fallback.
     */
    fun findTaxInclusive(lines: List<OcrLine>): Consensus.Observation? {
        lines.firstOrNull { TAX_INCLUSIVE_RE.containsMatchIn(it.text) }?.let {
            return Consensus.Observation(it.text.trim(), it.box)
        }

        val joined = lines.joinToString(" ") { it.text }
        if (!TAX_INCLUSIVE_RE.containsMatchIn(joined)) return null

        // Anchor the evidence to whichever line carries the recognisable part.
        val anchor = lines.firstOrNull { it.text.contains("incl", ignoreCase = true) }
            ?: lines.firstOrNull { it.text.contains("tax", ignoreCase = true) }
            ?: lines.firstOrNull { MRP_LINE_RE.containsMatchIn(it.text) }
            ?: return null

        return Consensus.Observation(anchor.text.trim(), anchor.box)
    }

    /**
     * The FSSAI licence.
     *
     * Found by its shape rather than by position: the 14-digit number is
     * printed beneath the logo, on its own line, and the logo is a stylised
     * mark OCR often does not return as text at all. Searching only to the
     * right of a caption reported clearly visible licences as missing.
     *
     * When a caption is present but no 14-digit number appears anywhere, the
     * result is a blank caption — which on this pack may equally mean the
     * number fell outside the photographed area, and the rule says so.
     */
    fun findFssai(lines: List<OcrLine>): Consensus.Observation? {
        for (line in lines) {
            for (match in DIGIT_RUN_RE.findAll(line.text)) {
                val digits = match.value.filter { it.isDigit() }
                if (digits.length == FSSAI_DIGITS) {
                    return Consensus.Observation(
                        value = digits,
                        box = line.boxFor(match.value) ?: line.box
                    )
                }
            }
        }

        // No licence number anywhere. Only report a blank caption if the pack
        // actually claims to carry one.
        val caption = lines.firstOrNull { FSSAI_ANCHOR_RE.containsMatchIn(it.text) }
        return caption?.let {
            Consensus.Observation(value = "", box = it.box, anchorOnly = true)
        }
    }

    /**
     * The brand, inferred from prominence.
     *
     * There is no caption for a brand name, so the only signal available is
     * that it is set larger than everything else. That is a heuristic, not a
     * reading of a declaration, so it is reported for a human to confirm
     * rather than treated as an authoritative extraction.
     *
     * Lines that look like captioned declarations, contact details or legal
     * boilerplate are excluded — they are often set large too.
     */
    fun findBrand(lines: List<OcrLine>): Consensus.Observation? {
        val candidates = lines.filter { line ->
            val text = line.text.trim()
            if (text.length < 2 || line.box.height <= 0) return@filter false
            if (text.contains(':')) return@filter false
            if (CAPTIONISH_RE.containsMatchIn(text)) return@filter false
            if (QTY_RE.containsMatchIn(text)) return@filter false
            if (text.any { it.isDigit() }) return@filter false
            true
        }

        val tallest = candidates.maxByOrNull { it.box.height } ?: return null

        // Only accept it if it genuinely stands out; on a pack of uniform
        // type there is no prominence signal to read.
        val median = candidates.map { it.box.height }.sorted()
            .let { it[it.size / 2] }
        if (tallest.box.height < median * 1.3) return null

        return Consensus.Observation(tallest.text.trim(), tallest.box)
    }

    private val CAPTIONISH_RE = Regex(
        """(?:mrp|price|weight|qty|quantity|batch|mfg|date|use by|best before|""" +
            """fssai|lic|care|customer|consumer|net|incl|tax|packed|marketed|""" +
            """निर्माता|पैक|मूल्य|वजन)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Find a captioned declaration such as "MFG. DATE : 06/2026".
     *
     * Returns null when the caption is absent. When the caption is printed but
     * carries no value, returns an observation flagged `anchorOnly` — that is
     * the "BATCH NO. :" with nothing after it case, which is a defect the pack
     * commits rather than a field we failed to find.
     *
     * [allowRelative] accepts wording like "best before 2 months from the date
     * of packing", which is a real declaration even though it holds no date.
     */
    fun findCaptioned(
        lines: List<OcrLine>,
        anchors: List<String>,
        valuePattern: Regex,
        allowRelative: Boolean = false
    ): Consensus.Observation? {
        var blankAnchor: OcrLine? = null

        for (line in lines) {
            val lowered = line.text.lowercase()

            // Longest match wins. "batch" and "batch no" both match the same
            // caption, and taking the shorter one leaves the word "no" behind
            // to be mistaken for the value.
            val anchor = anchors.filter { lowered.contains(it) }.maxByOrNull { it.length }
                ?: continue

            // Look only after the caption, so "BATCH NO." does not match its
            // own digits and a neighbouring value is not stolen.
            val afterAnchor = stripCaptionResidue(
                line.text.substring(
                    (lowered.indexOf(anchor) + anchor.length).coerceAtMost(line.text.length)
                )
            )

            val match = valuePattern.find(afterAnchor)
            if (match != null) {
                val value = match.value.trim()
                return Consensus.Observation(value, line.boxFor(value) ?: line.box)
            }

            if (allowRelative && afterAnchor.isNotBlank()) {
                // A period rather than a date: "2 months from the date of
                // packing". Flagged so the rule can check that the date it
                // counts from is itself declared.
                return Consensus.Observation(
                    value = afterAnchor.trim(),
                    box = line.box,
                    relative = true
                )
            }

            // Caption seen, no value on this line. Keep looking in case another
            // line carries a populated copy, but remember the blank one.
            if (blankAnchor == null) blankAnchor = line
        }

        val caption = blankAnchor ?: return null

        // Nothing followed the caption on its own line. Before reporting the
        // declaration as blank, look along the row for it.
        valueInSameRow(caption, lines, valuePattern)?.let { return it }

        return Consensus.Observation(value = "", box = caption.box, anchorOnly = true)
    }

    /**
     * A value printed in the column beside its caption.
     *
     * Labels are routinely set as two columns — captions down the left, values
     * down the right — and OCR groups that layout by column, so the value
     * arrives as a line of its own several lines away from the caption it
     * belongs to. Reading only the caption's own line reports every one of
     * those declarations as blank, which would fail a compliant pack.
     *
     * Pairing by geometry rather than by reading order is what makes this
     * safe, and the conditions are deliberately strict: a value must sit on
     * the same row as its caption, start to the right of where the caption
     * ends, and not be another field's caption. A pairing that is merely
     * plausible would put a declaration on the report that the pack never
     * made, which is worse than reporting the field blank.
     */
    private fun valueInSameRow(
        caption: OcrLine,
        lines: List<OcrLine>,
        valuePattern: Regex
    ): Consensus.Observation? {
        val height = caption.box.height.takeIf { it > 0 } ?: return null
        val centre = (caption.box.top + caption.box.bottom) / 2.0

        val candidate = lines
            .asSequence()
            .filter { it !== caption }
            // Same row. Half a line's height of drift is the most that can be
            // allowed before the row below starts to qualify.
            .filter { kotlin.math.abs((it.box.top + it.box.bottom) / 2.0 - centre) <= height * 0.5 }
            // In the column immediately to the right, which is where a value
            // column sits. A little overlap is tolerated because caption boxes
            // often run on past the printed text.
            //
            // The upper bound is what keeps a second pack out. Photograph two
            // packets side by side — which is how a shelf is photographed —
            // and a nutrition row on the neighbour can share a row with this
            // caption and carry digits that pass for a batch code. A value
            // belonging to this caption sits beside it, not a label away.
            .filter { it.box.left >= caption.box.right - height }
            .filter { it.box.left - caption.box.right <= caption.box.width }
            // Never another declaration's caption: that line's value belongs
            // to it, not to this one.
            .filter { candidate ->
                val lowered = candidate.text.lowercase()
                ALL_CAPTION_ANCHORS.none { lowered.contains(it) }
            }
            // Nearest wins, so a distant line cannot outbid the real value.
            .minByOrNull { it.box.left }
            ?: return null

        val text = stripCaptionResidue(candidate.text)
        val match = valuePattern.find(text) ?: return null
        val value = match.value.trim()

        return Consensus.Observation(value, candidate.boxFor(value) ?: candidate.box)
    }

    /**
     * MRP must be anchored to an explicit price marker. A bare number on the
     * pack is not a price — treating it as one would invent a declaration.
     */
    fun findMrp(lines: List<OcrLine>): Consensus.Observation? {
        for (line in lines) {
            if (!MRP_LINE_RE.containsMatchIn(line.text)) continue
            val match = PRICE_RE.find(line.text) ?: continue
            val value = match.groupValues[1]
            // Prefer the word-level box: it is what cap height must be
            // measured on, and it makes a tighter evidence crop.
            return Consensus.Observation(value, line.boxFor(value) ?: line.box)
        }
        return null
    }

    /**
     * Net quantity needs a value AND a unit. A line carrying an explicit
     * "Net Qty" style anchor is preferred; failing that, any value+unit token
     * is accepted, since many packs print "500 g" with no label.
     */
    fun findNetQuantity(lines: List<OcrLine>): Consensus.Observation? {
        val anchored = lines.firstOrNull {
            QTY_CONTEXT_RE.containsMatchIn(it.text) && QTY_RE.containsMatchIn(it.text)
        }
        if (anchored != null) {
            val value = QTY_RE.find(anchored.text)!!.value.trim()
            return Consensus.Observation(value, anchored.boxFor(value) ?: anchored.box)
        }

        for (line in lines) {
            val value = (QTY_RE.find(line.text) ?: continue).value.trim()
            return Consensus.Observation(value, line.boxFor(value) ?: line.box)
        }
        return null
    }

    /** Longest anchor wins, so compound phrases beat their substrings. */
    private fun matchAnchor(text: String): Pair<AddressRole, String>? {
        val lowered = text.lowercase()
        var best: Pair<AddressRole, String>? = null

        for ((role, anchors) in ROLE_ANCHORS) {
            for (anchor in anchors) {
                if (lowered.contains(anchor.lowercase())) {
                    if (best == null || anchor.length > best!!.second.length) {
                        best = role to anchor
                    }
                }
            }
        }
        return best
    }

    fun tagAddress(text: String, box: Box): TaggedAddress {
        val match = matchAnchor(text)
            ?: return TaggedAddress(AddressRole.UNKNOWN, text.trim(), box, null, text.trim())

        val body = text.substring(
            (text.lowercase().indexOf(match.second.lowercase()) + match.second.length)
                .coerceAtMost(text.length)
        ).trim { it in SEPARATORS }

        return TaggedAddress(match.first, text.trim(), box, match.second, body)
    }

    /**
     * An address is at least this many characters. Shorter than this and what
     * follows the caption is punctuation or an OCR fragment, not a place.
     */
    private const val MIN_ADDRESS_BODY = 6

    /** How many lines below a role caption its address may run to. */
    private const val ADDRESS_CONTINUATION_LINES = 3

    /**
     * Tag address lines by role, following an address onto the lines below.
     *
     * Packs print the role on one line and the address under it:
     *
     *     निर्माता :
     *     गोकुल
     *     रोड़ नं. 7, नेहरू नगर, इन्दौर
     *
     * Tagging each line on its own left the manufacturer "address" as the
     * word "निर्माता" — the caption, with the address sitting untagged
     * underneath. The check then passed on a pack that had named a role and
     * given no address, which is exactly the declaration it is meant to test.
     *
     * Continuation stops at the next caption of any kind, so one field's
     * address cannot swallow the next field's declaration.
     */
    fun tagAddresses(lines: List<OcrLine>): List<TaggedAddress> {
        val tagged = mutableListOf<TaggedAddress>()

        for ((index, line) in lines.withIndex()) {
            val head = tagAddress(line.text, line.box)
            if (head.anchor == null || head.body.length >= MIN_ADDRESS_BODY) {
                tagged += head
                continue
            }

            // The caption stands alone. Read on for the address it introduces.
            val absorbed = mutableListOf<String>()
            var box = line.box

            for (offset in 1..ADDRESS_CONTINUATION_LINES) {
                val next = lines.getOrNull(index + offset) ?: break
                val lowered = next.text.lowercase()

                val isAnotherCaption = matchAnchor(next.text) != null ||
                    ALL_CAPTION_ANCHORS.any { lowered.contains(it) }
                if (isAnotherCaption) break

                val text = next.text.trim { it in SEPARATORS }
                if (text.isBlank()) continue

                absorbed += text
                box = Box(
                    left = minOf(box.left, next.box.left),
                    top = minOf(box.top, next.box.top),
                    right = maxOf(box.right, next.box.right),
                    bottom = maxOf(box.bottom, next.box.bottom)
                )
            }

            val body = absorbed.joinToString(", ")
            tagged += head.copy(
                text = if (body.isBlank()) head.text else head.text + " " + body,
                box = box,
                body = body
            )
        }

        return tagged
    }

    /**
     * Consumer care requires an explicit anchor. A stray phone number
     * elsewhere on the pack is not consumer-care contact detail.
     */
    fun findConsumerCare(lines: List<OcrLine>): ConsumerCare? {
        for (line in lines) {
            val lowered = line.text.lowercase()
            if (CONSUMER_CARE_ANCHORS.none { lowered.contains(it.lowercase()) }) continue

            return ConsumerCare(
                text = line.text.trim(),
                box = line.box,
                phone = PHONE_RE.find(line.text)?.value?.trim(),
                email = EMAIL_RE.find(line.text)?.value?.trim()
            )
        }
        return null
    }
}
