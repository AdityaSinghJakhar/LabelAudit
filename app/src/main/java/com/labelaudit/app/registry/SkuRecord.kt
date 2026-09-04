package com.labelaudit.app.registry

import com.labelaudit.app.pipeline.Normalize
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a registry entry's values came from.
 *
 * This is not bookkeeping. A reference enrolled by photographing a pack is
 * only as trustworthy as that pack: if the enrolled sample was itself
 * mislabelled, every later comparison inherits the error and reports the
 * wrong thing as correct. A reference from an official product master is a
 * different kind of claim, and a reader has to be able to tell which they are
 * looking at.
 */
enum class RegistrySource {
    /** Read off a pack with this app and confirmed by an operator. */
    ENROLLED_FROM_SCAN,

    /** Imported from a product master file supplied by a brand or authority. */
    IMPORTED,

    /** Typed in by hand. */
    MANUAL;

    val trustNote: String
        get() = when (this) {
            ENROLLED_FROM_SCAN ->
                "Reference read from a scanned pack, not an authoritative source. " +
                    "Comparisons are only as good as the pack it was taken from."
            IMPORTED -> "Reference imported from a supplied product master."
            MANUAL -> "Reference entered by hand."
        }
}

/**
 * The known-correct declarations for one SKU — one product in one pack size.
 *
 * "Gokul Shahi Namkeen 500 g" and the same namkeen in 200 g are different
 * SKUs: the price and net quantity differ, so they cannot share a reference.
 */
data class SkuRecord(
    val skuId: String,
    val brandStrings: List<String> = emptyList(),
    val mrpExact: Double? = null,
    val netQuantity: String? = null,
    val manufacturerAddress: String? = null,
    val consumerCare: String? = null,
    val fssaiLicence: String? = null,
    val source: RegistrySource = RegistrySource.MANUAL,
    val note: String = "",
    val savedAt: Long = System.currentTimeMillis()
) {
    /**
     * How well a scan's fields match this record, 0..1.
     *
     * Brand and net quantity carry the weight because they are what
     * distinguishes one SKU from another; price is deliberately excluded from
     * matching, since a wrong price is exactly the violation we are looking
     * for and must not prevent the pack from being recognised.
     */
    fun matchScore(fields: Map<String, String>): Double {
        var considered = 0
        var matched = 0

        val brand = fields["brand"]
        if (brand != null && brandStrings.isNotEmpty()) {
            considered += 2
            if (brandStrings.any { Normalize.valuesEqual("brand", brand, it) }) matched += 2
        }

        val quantity = fields["net_quantity"]
        if (quantity != null && netQuantity != null) {
            considered += 2
            if (Normalize.valuesEqual("net_quantity", quantity, netQuantity)) matched += 2
        }

        val licence = fields["fssai_licence"]
        if (licence != null && fssaiLicence != null) {
            considered += 1
            if (licence.filter { it.isDigit() } == fssaiLicence.filter { it.isDigit() }) {
                matched += 1
            }
        }

        return if (considered == 0) 0.0 else matched.toDouble() / considered
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sku_id", skuId)
        .put("brand_strings", JSONArray(brandStrings))
        .put("mrp_exact", mrpExact ?: JSONObject.NULL)
        .put("net_quantity", netQuantity ?: JSONObject.NULL)
        .put("manufacturer_address", manufacturerAddress ?: JSONObject.NULL)
        .put("consumer_care", consumerCare ?: JSONObject.NULL)
        .put("fssai_licence", fssaiLicence ?: JSONObject.NULL)
        .put("source", source.name)
        .put("note", note)
        .put("saved_at", savedAt)

    companion object {

        /**
         * A string field, or null when it was written as an explicit JSON null.
         *
         * [JSONObject.optString] coerces the NULL sentinel to the *string*
         * "null", which would make an absent value look like a recorded one.
         * A record carrying "null" as its net quantity would then be pushed
         * into the ruleset as the expected declaration, failing a correct pack
         * against a reference that was never there.
         */
        private fun JSONObject.text(name: String): String? =
            if (isNull(name)) null else optString(name).ifBlank { null }

        fun fromJson(json: JSONObject): SkuRecord {
            val brands = json.optJSONArray("brand_strings")
            return SkuRecord(
                skuId = json.getString("sku_id"),
                brandStrings = (0 until (brands?.length() ?: 0))
                    .map { brands!!.getString(it) },
                mrpExact = if (json.isNull("mrp_exact")) null
                    else json.optDouble("mrp_exact").takeIf { !it.isNaN() },
                netQuantity = json.text("net_quantity"),
                manufacturerAddress = json.text("manufacturer_address"),
                consumerCare = json.text("consumer_care"),
                fssaiLicence = json.text("fssai_licence"),
                source = runCatching {
                    RegistrySource.valueOf(json.optString("source"))
                }.getOrDefault(RegistrySource.MANUAL),
                note = json.optString("note"),
                savedAt = json.optLong("saved_at", System.currentTimeMillis())
            )
        }
    }
}
