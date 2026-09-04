package com.labelaudit.app.pipeline


/**
 * Per-field consensus across multiple frames of the same package.
 *
 * A single OCR pass is not trustworthy enough to assert a compliance
 * violation, so the operator captures several frames and a field is accepted
 * only when the same normalised value appears in at least MIN_AGREEMENT of
 * them.
 *
 * This also supplies the confidence that ML Kit does not expose: agreement
 * across frames, rather than a model score. A value read identically in 5 of
 * 5 frames is trusted more than one read in 3 of 5, and that ratio is what
 * downstream rules see.
 *
 * Capture protocol is 3-5 frames. With 5 frames MIN_AGREEMENT = 3 is a
 * majority; with 3 frames it is unanimity. Fewer frames buy less evidence, so
 * they are required to agree more.
 *
 * A field that misses the threshold is not guessed — it lands in [failures]
 * with the disagreeing candidates attached, so a reviewer can see what the
 * frames actually said.
 */
object Consensus {

    const val MIN_FRAMES = 1
    const val MAX_FRAMES = 5
    const val MIN_AGREEMENT = 3

    data class Observation(
        val value: String,
        val box: Box,
        /**
         * The label printed this field's caption but left the value blank —
         * "MFG. DATE :" with nothing after it. That is a declaration the pack
         * makes and does not honour, which is a different defect from the
         * caption being absent altogether, and must not be reported as a
         * value that was read.
         */
        val anchorOnly: Boolean = false,
        /**
         * The value states a period rather than a date — "best before 2
         * months from the date of packing". Such a declaration only yields a
         * date if the date it counts from is itself declared.
         */
        val relative: Boolean = false
    )

    data class AgreedField(
        val value: String,
        /** Fraction of frames that agreed. Not a model score. */
        val confidence: Float,
        val box: Box,
        val agreement: Int,
        val frames: Int,
        /** The caption was printed but the value left blank. */
        val anchorOnly: Boolean = false,
        /** The value is a period, not a date. See [Observation.relative]. */
        val relative: Boolean = false
    )

    data class Candidate(val value: String, val votes: Int)

    data class Failure(
        val reason: String,
        val candidates: List<Candidate>,
        val frames: Int
    )

    data class Result(
        val fields: Map<String, AgreedField>,
        val failures: Map<String, Failure>
    )

    private const val UNPARSED = "__unparsed__"
    private const val BLANK = "__blank__"

    /**
     * Group observations by normalised value. Unparseable values are bucketed
     * under a sentinel keyed by their raw text so they cannot merge with a
     * genuinely-read value.
     */
    private fun bucketKey(field: String, observation: Observation): String {
        // Blank captions group together deterministically rather than relying
        // on the empty string surviving each field's normaliser.
        if (observation.anchorOnly) return BLANK

        val normalised = Normalize.field(field, observation.value)
            ?: return "$UNPARSED:${observation.value}"
        return normalised.toString()
    }

    /**
     * Reduce per-frame observations to agreed values.
     *
     * [frames] is one map per captured frame, field name -> observation. A
     * field absent from a frame simply contributes no vote.
     */
    fun build(
        frames: List<Map<String, Observation>>,
        minAgreement: Int = MIN_AGREEMENT
    ): Result {
        require(frames.size in MIN_FRAMES..MAX_FRAMES) {
            "expected $MIN_FRAMES-$MAX_FRAMES frames, got ${frames.size}"
        }
        // A bulk upload of one image per product cannot reach a 3-frame
        // threshold, so the requirement drops to unanimity over what is
        // available. Fewer frames genuinely is weaker evidence, and the report
        // shows the agreement ratio so a reviewer can see that.
        val required = minOf(minAgreement, frames.size)

        val fieldNames = frames.flatMap { it.keys }.toSortedSet()
        val agreed = mutableMapOf<String, AgreedField>()
        val failures = mutableMapOf<String, Failure>()

        for (name in fieldNames) {
            val buckets = mutableMapOf<String, MutableList<Observation>>()
            for (frame in frames) {
                val observation = frame[name] ?: continue
                buckets.getOrPut(bucketKey(name, observation)) { mutableListOf() }
                    .add(observation)
            }

            if (buckets.isEmpty()) {
                failures[name] = Failure("ocr_no_consensus", emptyList(), frames.size)
                continue
            }

            val (key, winners) = buckets.maxByOrNull { it.value.size }!!

            // A bucket of values none of which could be parsed never wins,
            // however many frames agree on the same garbage.
            val unparsed = key.startsWith("$UNPARSED:")

            if (unparsed || winners.size < required) {
                failures[name] = Failure(
                    reason = "ocr_no_consensus",
                    candidates = buckets.values
                        .map { Candidate(it.first().value, it.size) }
                        .sortedByDescending { it.votes },
                    frames = frames.size
                )
                continue
            }

            agreed[name] = AgreedField(
                value = winners.first().value,
                confidence = winners.size.toFloat() / frames.size,
                box = winners.first().box,
                agreement = winners.size,
                frames = frames.size,
                anchorOnly = winners.first().anchorOnly,
                relative = winners.first().relative
            )
        }

        return Result(agreed, failures)
    }

    /**
     * Combine the per-side results for one product.
     *
     * Sides are complementary, not corroborating: the MRP may be printed only
     * on the back, so a field absent from the front is not disagreement. Where
     * two sides do read the same field differently, that is a genuine conflict
     * and is reported rather than silently resolved in favour of whichever
     * side happened to be processed first.
     */
    fun merge(sides: List<Result>): Result {
        if (sides.size == 1) return sides.first()

        val agreed = mutableMapOf<String, AgreedField>()
        val failures = mutableMapOf<String, Failure>()

        val names = sides.flatMap { it.fields.keys + it.failures.keys }.toSortedSet()

        for (name in names) {
            val all = sides.mapNotNull { it.fields[name] }

            // A caption printed on one face with its value on another is one
            // declaration split across the pack, not two faces disagreeing.
            // Letting a blank outvote or conflict with a real reading threw
            // away a value the pack plainly carries.
            val readings = all.filterNot { it.anchorOnly }
                .ifEmpty { all }

            if (readings.isEmpty()) {
                // No side agreed on a value; carry the first side's reason.
                sides.firstNotNullOfOrNull { it.failures[name] }?.let { failures[name] = it }
                continue
            }

            val distinct = readings
                .groupBy { Normalize.field(name, it.value)?.toString() ?: it.value }

            if (distinct.size > 1) {
                failures[name] = Failure(
                    reason = "sides_disagree",
                    candidates = distinct.values
                        .map { Candidate(it.first().value, it.size) }
                        .sortedByDescending { it.votes },
                    frames = readings.sumOf { it.frames }
                )
                continue
            }

            // Prefer the strongest reading of the agreed value.
            agreed[name] = readings.maxByOrNull { it.confidence }!!
        }

        return Result(agreed, failures)
    }
}
