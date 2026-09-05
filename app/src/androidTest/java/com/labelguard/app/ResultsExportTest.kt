package com.labelguard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.pipeline.Box
import com.labelguard.app.pipeline.Consensus
import com.labelguard.app.pipeline.FieldExtractor
import com.labelguard.app.pipeline.RulesEngine
import com.labelguard.app.pipeline.Ruleset
import com.labelguard.app.report.ResultsExport
import com.labelguard.app.report.ScanReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The export the offline accuracy harness reads.
 *
 * Its shape is a contract with `labelguard.eval`: if this drifts, accuracy
 * numbers are computed against the wrong thing and nothing complains, so the
 * structure is asserted explicitly rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class ResultsExportTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun field(value: String, anchorOnly: Boolean = false) =
        Consensus.AgreedField(
            value = value,
            confidence = 1f,
            box = Box(10, 10, 100, 40),
            agreement = 5,
            frames = 5,
            anchorOnly = anchorOnly
        )

    private fun report(fields: Map<String, Consensus.AgreedField>): ScanReport {
        val ruleset = context.assets.open("ruleset.yaml").use(Ruleset::load)
        val evaluation = RulesEngine.evaluate(
            ruleset,
            fields,
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("Manufactured by Acme")
                )
            )
        )
        return ScanReport.from(
            evaluation = evaluation,
            crops = emptyMap(),
            consensus = Consensus.Result(fields, emptyMap()),
            framesUsed = 5,
            framesGated = 0,
            elapsedMs = 800
        )
    }

    @Test
    fun exportCarriesTheKeysTheHarnessReads() {
        val json = ResultsExport.toJson(
            "sku001",
            report(mapOf("mrp" to field("140"), "net_quantity" to field("500g")))
        )

        for (key in listOf("image_id", "verdict", "fields", "violations")) {
            assertTrue("export is missing '$key'", json.has(key))
        }
        assertEquals("sku001", json.getString("image_id"))

        val mrp = json.getJSONObject("fields").getJSONObject("mrp")
        assertTrue(mrp.getBoolean("present"))
        assertEquals("140", mrp.getString("value"))
    }

    @Test
    fun aBlankCaptionIsExportedAsAbsent() {
        // The pack printed "MFG. DATE :" and left it empty. Exporting that as
        // a successful read would flatter the accuracy numbers.
        val json = ResultsExport.toJson(
            "sku002",
            report(mapOf("mrp" to field("140"), "mfg_date" to field("", anchorOnly = true)))
        )

        val date = json.getJSONObject("fields").getJSONObject("mfg_date")
        assertFalse("a blank caption must not count as read", date.getBoolean("present"))
        assertTrue(date.isNull("value"))
    }

    @Test
    fun violationsListTheFailingRuleIds() {
        val json = ResultsExport.toJson("sku003", report(mapOf("mrp" to field("140"))))
        val violations = json.getJSONArray("violations")

        val ids = (0 until violations.length()).map { violations.getString(it) }
        // Nothing declared a net quantity, so that rule must appear.
        assertTrue("expected QTY-01 among $ids", ids.contains("QTY-01"))
    }

    @Test
    fun writesOneFilePerScan() {
        val directory = File(context.cacheDir, "export-test")
        directory.deleteRecursively()

        val written = ResultsExport.write(
            directory, "sku 004/odd:name", report(mapOf("mrp" to field("140")))
        )

        assertTrue(written.exists())
        // The id is used as a filename, so unsafe characters must be replaced.
        assertFalse(written.name.contains('/'))
        assertFalse(written.name.contains(':'))

        val parsed = JSONObject(written.readText())
        assertEquals("sku 004/odd:name", parsed.getString("image_id"))

        directory.deleteRecursively()
    }

    @Test
    fun writesAWholeBatch() {
        val directory = File(context.cacheDir, "export-batch")
        directory.deleteRecursively()

        val files = ResultsExport.writeAll(
            directory,
            listOf(
                "a" to report(mapOf("mrp" to field("140"))),
                "b" to report(mapOf("mrp" to field("45")))
            )
        )

        assertEquals(2, files.size)
        files.forEach { assertTrue(it.exists()) }

        directory.deleteRecursively()
    }

    /**
     * Writes a real export into external storage so it can be pulled off the
     * device and scored by the Python harness, proving the two ends agree in
     * practice and not only in this test's expectations.
     */
    @Test
    fun writesASampleForOfflineScoring() {
        // Internal storage: this OEM build blocks adb shell from reading
        // Android/data, and the sample has to be pullable to be useful.
        val directory = File(context.filesDir, "export-sample")
        directory.deleteRecursively()

        ResultsExport.writeAll(
            directory,
            listOf(
                "gokul_500g" to report(
                    mapOf(
                        "mrp" to field("140"),
                        "net_quantity" to field("500g"),
                        "mfg_date" to field("", anchorOnly = true)
                    )
                ),
                "pack02" to report(
                    mapOf("mrp" to field("45"), "net_quantity" to field("200 g"))
                )
            )
        )

        assertEquals(2, directory.listFiles()?.size)

        // Emit the exact bytes so the Python harness can be run against real
        // app output rather than a hand-written imitation of it.
        directory.listFiles()?.sortedBy { it.name }?.forEach {
            println("EXPORT_SAMPLE<${it.name}>${it.readText()}</EXPORT_SAMPLE>")
        }
    }
}
