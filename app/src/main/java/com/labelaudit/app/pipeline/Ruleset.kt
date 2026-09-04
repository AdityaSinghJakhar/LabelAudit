package com.labelaudit.app.pipeline

import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * The compliance ruleset, loaded from YAML.
 *
 * YAML rather than a generated JSON blob so the on-device copy stays diffable
 * against the Python ruleset a reviewer reads. Parsing happens once at load.
 */
data class Ruleset(
    val version: String,
    val sourceCitation: String,
    val heightMetric: String,
    val exemptions: List<Exemption>,
    val rules: List<Rule>,
    val registry: Registry
) {
    data class Exemption(
        val id: String,
        val condition: Condition,
        val exempts: List<String>,
        val citation: String
    )

    data class Condition(
        val field: String,
        val op: String,
        val value: Double,
        val unit: String?
    )

    data class Rule(
        val id: String,
        val field: String,
        val check: Check,
        val citation: String
    )

    data class Check(
        val type: String,
        val registryKey: String? = null,
        val role: String? = null,
        val minMm: Double? = null,
        val needsLegalConfirmation: Boolean = false,
        /**
         * For date_marking: the field a relative period counts from. Without
         * it declared, the period yields no date.
         */
        val relativeRequires: String? = null
    )

    data class Registry(
        val populated: Boolean,
        val skuId: String?,
        val brandStrings: List<String>,
        val addresses: Map<String, String?>,
        val consumerCare: Map<String, String?>,
        val mrpExact: Double?,
        val netQuantity: String?
    ) {
        fun valueFor(key: String): Any? = when (key) {
            "brand_strings" -> brandStrings.takeIf { it.isNotEmpty() }
            "mrp_exact" -> mrpExact
            "net_quantity" -> netQuantity
            else -> null
        }
    }

    companion object {

        fun load(stream: InputStream): Ruleset =
            stream.bufferedReader().use { parse(it.readText()) }

        @Suppress("UNCHECKED_CAST")
        fun parse(yaml: String): Ruleset {
            val root = Yaml().load<Map<String, Any?>>(yaml)
                ?: throw IllegalArgumentException("ruleset is empty")

            val version = root["version"]?.toString()
                ?: throw IllegalArgumentException("ruleset is missing required key: version")
            val sourceCitation = root["source_citation"]?.toString()
                ?: throw IllegalArgumentException("ruleset is missing required key: source_citation")
            val heightMetric = root["height_metric"]?.toString()
                ?: throw IllegalArgumentException("ruleset is missing required key: height_metric")

            val exemptions = (root["exemptions"] as? List<Map<String, Any?>> ?: emptyList())
                .map { raw ->
                    val condition = raw["condition"] as? Map<String, Any?>
                        ?: throw IllegalArgumentException("exemption ${raw["id"]} has no condition")
                    Exemption(
                        id = raw["id"]?.toString().orEmpty(),
                        condition = Condition(
                            field = condition["field"]?.toString().orEmpty(),
                            op = condition["op"]?.toString().orEmpty(),
                            value = (condition["value"] as? Number)?.toDouble() ?: 0.0,
                            unit = condition["unit"]?.toString()
                        ),
                        exempts = (raw["exempts"] as? List<Any?> ?: emptyList())
                            .map { it.toString() },
                        citation = raw["citation"]?.toString()
                            ?: throw IllegalArgumentException(
                                "exemption ${raw["id"]} has no citation"
                            )
                    )
                }

            val rules = (root["rules"] as? List<Map<String, Any?>> ?: emptyList())
                .map { raw ->
                    val id = raw["id"]?.toString().orEmpty()
                    val check = raw["check"] as? Map<String, Any?>
                        ?: throw IllegalArgumentException("rule $id has no check")
                    val params = check["params"] as? Map<String, Any?> ?: emptyMap()

                    Rule(
                        id = id,
                        field = raw["field"]?.toString().orEmpty(),
                        check = Check(
                            type = check["type"]?.toString().orEmpty(),
                            registryKey = check["registry_key"]?.toString(),
                            role = check["role"]?.toString(),
                            minMm = (params["min_mm"] as? Number)?.toDouble(),
                            needsLegalConfirmation =
                                params["needs_legal_confirmation"] as? Boolean ?: false,
                            relativeRequires = check["relative_requires"]?.toString()
                        ),
                        // Refuse at load time rather than emitting an
                        // unsubstantiated finding later.
                        citation = raw["citation"]?.toString()
                            ?: throw IllegalArgumentException("rule $id has no citation")
                    )
                }

            val registryRaw = root["registry"] as? Map<String, Any?> ?: emptyMap()
            val quantity = registryRaw["net_quantity"] as? Map<String, Any?>
            val quantityValue = quantity?.get("value")
            val quantityUnit = quantity?.get("unit")

            val registry = Registry(
                populated = registryRaw["populated"] as? Boolean ?: false,
                skuId = registryRaw["sku_id"]?.toString(),
                brandStrings = (registryRaw["brand_strings"] as? List<Any?> ?: emptyList())
                    .map { it.toString() },
                addresses = (registryRaw["addresses"] as? Map<String, Any?> ?: emptyMap())
                    .mapValues { it.value?.toString() },
                consumerCare = (registryRaw["consumer_care"] as? Map<String, Any?> ?: emptyMap())
                    .mapValues { it.value?.toString() },
                mrpExact = (registryRaw["mrp_exact"] as? Number)?.toDouble(),
                netQuantity = if (quantityValue != null && quantityUnit != null) {
                    "$quantityValue $quantityUnit"
                } else {
                    null
                }
            )

            return Ruleset(
                version = version,
                sourceCitation = sourceCitation,
                heightMetric = heightMetric,
                exemptions = exemptions,
                rules = rules,
                registry = registry
            )
        }
    }
}
