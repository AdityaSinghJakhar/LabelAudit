package com.labelguard.app.registry

import com.labelguard.app.pipeline.Consensus
import com.labelguard.app.pipeline.Normalize
import com.labelguard.app.pipeline.Ruleset

/**
 * Turning a scan into a reference, and a reference into ruleset registry
 * values.
 *
 * Enrolment is the practical way to populate a registry: photograph one pack
 * you have checked by hand, and later packs of the same product are compared
 * against it. The alternative — typing every value — is slow and circular,
 * because whoever types it is reading the same label the app is.
 *
 * The circularity does not disappear entirely, which is why enrolment records
 * its source and why the operator has to confirm the pack is genuinely
 * compliant first. The app cannot verify that claim; it can only record who
 * made it.
 */
object Enrolment {

    /**
     * Build a candidate record from what a scan read.
     *
     * Only fields that reached consensus are carried over: a value the frames
     * disagreed on is not fit to become the reference every later pack is
     * measured against.
     */
    fun fromScan(
        skuId: String,
        fields: Map<String, Consensus.AgreedField>,
        note: String = ""
    ): SkuRecord {
        fun value(name: String): String? = fields[name]
            ?.takeIf { !it.anchorOnly && it.value.isNotBlank() }
            ?.value

        val brand = value("brand")

        return SkuRecord(
            skuId = skuId,
            brandStrings = listOfNotNull(brand),
            mrpExact = Normalize.money(value("mrp")),
            netQuantity = value("net_quantity"),
            manufacturerAddress = value("manufacturer_address"),
            consumerCare = value("consumer_care"),
            fssaiLicence = value("fssai_licence")?.filter { it.isDigit() },
            source = RegistrySource.ENROLLED_FROM_SCAN,
            note = note
        )
    }

    /**
     * Values a scan can offer for matching, as plain strings.
     */
    fun matchableFields(fields: Map<String, Consensus.AgreedField>): Map<String, String> =
        fields.filterValues { !it.anchorOnly && it.value.isNotBlank() }
            .mapValues { it.value.value }

    /**
     * Overlay a registered SKU onto the ruleset's registry so the
     * `matches_registry` checks have something to compare against.
     *
     * The ruleset ships with an empty registry; this is what fills it at
     * runtime, per scan, from whichever SKU the pack was recognised as.
     */
    fun applyTo(ruleset: Ruleset, record: SkuRecord): Ruleset = ruleset.copy(
        registry = ruleset.registry.copy(
            populated = true,
            // Only a supplied product master carries enough authority to fail
            // a pack. Anything read off a pack with this app is an assertion,
            // and the engine caps it at NEEDS_REVIEW.
            authority = when (record.source) {
                RegistrySource.IMPORTED -> Ruleset.Authority.AUTHORITATIVE
                RegistrySource.ENROLLED_FROM_SCAN,
                RegistrySource.MANUAL -> Ruleset.Authority.ASSERTED
            },
            skuId = record.skuId,
            brandStrings = record.brandStrings,
            mrpExact = record.mrpExact,
            netQuantity = record.netQuantity
        )
    )
}
