package com.labelguard.app.report

import com.labelguard.app.pipeline.RuleStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports scan results as JSON for offline accuracy scoring.
 *
 * The app can say what it read; it cannot say whether it read correctly. That
 * judgement needs a human-typed ground truth and a statistic, which is what
 * the LabelGuard eval harness does. This is the file that connects the two —
 * without it, any accuracy claim about the app is an assertion rather than a
 * measurement.
 *
 * Shape matches what `labelguard.eval.harness` expects:
 *
 *     {
 *       "image_id": "sku001",
 *       "verdict": "FAIL",
 *       "fields": { "mrp": {"present": true, "value": "140"} },
 *       "violations": ["MFG-01"]
 *     }
 */
object ResultsExport {

    /** One scan's predictions, keyed the way the harness reads them. */
    fun toJson(imageId: String, report: ScanReport): JSONObject {
        val fields = JSONObject()
        for (group in report.fields) {
            // A blank caption is not a value the pipeline read, so it is
            // exported as absent — scoring it as a successful extraction
            // would flatter the numbers.
            val readable = group.observedValue?.takeIf { it.isNotBlank() }
            fields.put(
                group.field,
                JSONObject()
                    .put("present", readable != null)
                    .put("value", readable ?: JSONObject.NULL)
                    .put("status", group.status.name)
                    .put("agreement", group.agreement ?: JSONObject.NULL)
            )
        }

        val violations = JSONArray()
        report.fields
            .flatMap { it.checks }
            .filter { it.status == RuleStatus.FAIL }
            .forEach { violations.put(it.ruleId) }

        return JSONObject()
            .put("image_id", imageId)
            .put("verdict", report.verdict.name)
            .put("fields", fields)
            .put("violations", violations)
            .put("ruleset_version", report.rulesetVersion)
            .put("frames_used", report.framesUsed)
            .put("elapsed_ms", report.elapsedMs)
            .put("scanned_at", report.timestamp)
            // Included so a disagreement between the app and a ground truth
            // can be traced to recognition or to matching.
            .put("raw_lines", JSONArray(report.rawLines))
    }

    /** Writes one JSON file per scan into [directory], named by image id. */
    fun write(directory: File, imageId: String, report: ScanReport): File {
        directory.mkdirs()
        val safeId = imageId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(directory, "$safeId.json")
        destination.writeText(toJson(imageId, report).toString(2))
        return destination
    }

    /** Writes a whole bulk run, one file per image. */
    fun writeAll(
        directory: File,
        reports: List<Pair<String, ScanReport>>
    ): List<File> = reports.map { (id, report) -> write(directory, id, report) }
}
