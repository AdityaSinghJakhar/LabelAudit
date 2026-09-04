package com.labelaudit.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelaudit.app.pipeline.Box
import com.labelaudit.app.pipeline.Consensus
import com.labelaudit.app.pipeline.EvidenceCrops
import com.labelaudit.app.pipeline.FieldExtractor
import com.labelaudit.app.pipeline.RuleStatus
import com.labelaudit.app.pipeline.RulesEngine
import com.labelaudit.app.pipeline.Ruleset
import com.labelaudit.app.pipeline.Verdict
import com.labelaudit.app.report.ReportPdf
import com.labelaudit.app.report.ScanReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Asset loading, evidence cropping and PDF rendering all need a real device.
 * The PDF is checked as an actual file with a PDF header, not just "no
 * exception thrown".
 */
@RunWith(AndroidJUnit4::class)
class ReportPdfTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ruleset(): Ruleset =
        context.assets.open("ruleset.yaml").use(Ruleset::load)

    private fun field(value: String, box: Box = Box(40, 40, 300, 100)) =
        Consensus.AgreedField(value, 1.0f, box, 5, 5)

    private fun report(): ScanReport {
        val rules = ruleset()
        val fields = mapOf(
            "mrp" to field("45.00"),
            "net_quantity" to field("500 g", Box(40, 120, 320, 180))
        )
        val evaluation = RulesEngine.evaluate(
            rules,
            fields,
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("Manufactured by Acme")
                )
            )
        )

        return ScanReport.from(
            evaluation = evaluation,
            crops = mapOf("mrp" to solidBitmap(260, 60)),
            consensus = Consensus.Result(
                fields = fields,
                failures = mapOf(
                    "consumer_care" to Consensus.Failure(
                        "ocr_no_consensus",
                        listOf(Consensus.Candidate("care@acme.in", 2)),
                        5
                    )
                )
            ),
            framesUsed = 5,
            framesGated = 0,
            elapsedMs = 1234
        )
    }

    private fun solidBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.LTGRAY)
        }

    // ------------------------------------------------------- asset loading

    @Test
    fun rulesetLoadsFromAssets() {
        val rules = ruleset()

        assertTrue(rules.version.isNotBlank())
        assertEquals("cap_height", rules.heightMetric)
        assertEquals(12, rules.rules.size)
        assertEquals(2, rules.exemptions.size)
        assertTrue("registry should ship unpopulated", !rules.registry.populated)
    }

    @Test
    fun everyShippedRuleHasACitation() {
        ruleset().rules.forEach {
            assertTrue("${it.id} has no citation", it.citation.isNotBlank())
        }
    }

    // ------------------------------------------------------ evidence crops

    @Test
    fun evidenceCropIsCutFromTheFrame() {
        val frame = File(context.cacheDir, "crop-source.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(100f, 100f, 300f, 200f, Paint().apply { color = Color.BLACK })
        }
        frame.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()

        val crops = EvidenceCrops.extract(frame, mapOf("mrp" to Box(100, 100, 300, 200)))

        assertNotNull(crops["mrp"])
        val crop = crops["mrp"]!!
        // Padding widens the crop beyond the box but it must stay in bounds.
        assertTrue(crop.width in 200..800)
        assertTrue(crop.height in 100..600)

        crop.recycle()
        frame.delete()
    }

    @Test
    fun aCropAtTheFrameEdgeIsClamped() {
        val frame = File(context.cacheDir, "crop-edge.jpg")
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        frame.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()

        // A box flush against the bottom-right corner: padding would run off.
        val crops = EvidenceCrops.extract(frame, mapOf("x" to Box(380, 280, 400, 300)))
        val crop = crops["x"]

        assertNotNull(crop)
        assertTrue(crop!!.width <= 400)
        assertTrue(crop.height <= 300)

        crop.recycle()
        frame.delete()
    }

    @Test
    fun aZeroSizedBoxProducesNoCrop() {
        val frame = File(context.cacheDir, "crop-zero.jpg")
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
            frame.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }

        assertNull(EvidenceCrops.extract(frame, mapOf("x" to Box.EMPTY))["x"])
        frame.delete()
    }

    // ---------------------------------------------------------- the report

    @Test
    fun reportCarriesACitationForEveryCheck() {
        report().fields.flatMap { it.checks }.forEach {
            assertTrue("${it.ruleId} has no citation", it.citation.isNotBlank())
        }
    }

    @Test
    fun eachFieldAppearsOnceWithItsChecksNested() {
        // mrp is checked three times (presence, registry, height). Listing it
        // as three top-level rows repeated the same read value and crop, which
        // read as inconsistency rather than as three checks on one declaration.
        val r = report()
        val names = r.fields.map { it.field }

        assertEquals("a field is listed more than once", names.size, names.toSet().size)

        val mrp = r.fields.first { it.field == "mrp" }
        assertTrue("expected several checks under mrp", mrp.checks.size >= 2)
        assertTrue(mrp.checks.any { it.ruleId == "MRP-01" })
        assertTrue(mrp.checks.any { it.ruleId == "MRP-02" })
    }

    @Test
    fun theVerdictNamesTheChecksResponsibleForIt() {
        val r = report()
        assertTrue(
            "a verdict sitting beside PASS rows must say which checks caused it",
            r.blockingChecks.isNotEmpty()
        )
        r.blockingChecks.forEach { assertEquals(RuleStatus.FAIL, it.status) }
    }

    @Test
    fun fieldStatusTakesTheWorstOfItsChecks() {
        // mfg_date is absent, so its field status must be the failure rather
        // than any passing sibling check.
        val date = report().fields.first { it.field == "mfg_date" }
        assertEquals(RuleStatus.FAIL, date.status)
    }

    @Test
    fun anInapplicableCheckDoesNotMakeAFieldLookUnassessable() {
        // mrp is read fine; only its registry comparison is inapplicable, and
        // that must not present the declaration as unreadable.
        val mrp = report().fields.first { it.field == "mrp" }

        assertEquals(RuleStatus.PASS, mrp.status)
        assertTrue(mrp.checks.any { it.status == RuleStatus.NOT_APPLICABLE })
    }

    @Test
    fun reportSurfacesFieldsWithoutConsensus() {
        val unresolved = report().unresolved

        assertEquals(1, unresolved.size)
        assertEquals("consumer_care", unresolved.first().field)
        assertEquals("ocr_no_consensus", unresolved.first().reason)
    }

    @Test
    fun anEmptyRegistryDoesNotSuppressRealViolations() {
        // Registry comparisons are inapplicable without a registered SKU. That
        // is a configuration state, and reporting NOT_ASSESSABLE for it hid
        // the violations the pipeline had actually substantiated.
        val r = report()

        assertEquals(Verdict.FAIL, r.verdict)
        assertTrue(
            "the verdict must disclose the checks that did not run",
            r.verdictExplanation.contains("did not apply")
        )
    }

    @Test
    fun exemptRowsCarryTheExemptionCitation() {
        val rules = ruleset()
        val evaluation = RulesEngine.evaluate(
            rules,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("8 g"))
        )
        val care = evaluation.findings.first { it.ruleId == "CARE-01" }

        assertEquals(RuleStatus.EXEMPT, care.status)
        assertTrue(care.citation.contains("r. 26"))
    }

    // ------------------------------------------------------------- the PDF

    @Test
    fun pdfIsWrittenAndIsAValidPdfFile() {
        val destination = File(context.cacheDir, "reports/test-report.pdf")
        val written = ReportPdf.write(context, report(), destination)

        assertTrue("pdf was not created", written.exists())
        assertTrue("pdf is suspiciously small: ${written.length()}", written.length() > 1000)

        val header = written.inputStream().use { stream ->
            ByteArray(5).also { stream.read(it) }
        }
        assertEquals("%PDF-", String(header))

        written.delete()
    }

    @Test
    fun pdfHandlesAReportWithNoCrops() {
        val rules = ruleset()
        val evaluation = RulesEngine.evaluate(rules, mapOf("mrp" to field("45.00")))
        val bare = ScanReport.from(
            evaluation = evaluation,
            crops = emptyMap(),
            consensus = Consensus.Result(emptyMap(), emptyMap()),
            framesUsed = 3,
            framesGated = 2,
            elapsedMs = 900
        )

        val written = ReportPdf.write(context, bare, File(context.cacheDir, "reports/bare.pdf"))

        assertTrue(written.exists())
        assertTrue(written.length() > 500)
        written.delete()
    }

    @Test
    fun pdfPaginatesALongReport() {
        val rules = ruleset()
        val evaluation = RulesEngine.evaluate(rules, mapOf("mrp" to field("45.00")))

        // Enough rows to overflow one A4 page. No per-row bitmaps: pagination
        // is a layout concern, and 40 live bitmaps in one test is what made
        // this suite flaky under memory pressure.
        val many = ScanReport(
            verdict = evaluation.verdict,
            fields = (1..30).map { i ->
                ScanReport.FieldGroup(
                    field = "field_$i",
                    status = RuleStatus.NOT_ASSESSABLE,
                    observedValue = "value $i",
                    agreement = "5/5 frames",
                    crop = null,
                    checks = listOf(evaluation.findings.first().copy(ruleId = "REPEAT-$i"))
                )
            },
            unresolved = emptyList(),
            rulesetVersion = rules.version,
            sourceCitation = rules.sourceCitation,
            framesUsed = 5,
            framesGated = 0,
            elapsedMs = 1000
        )

        val written = ReportPdf.write(context, many, File(context.cacheDir, "reports/long.pdf"))

        assertTrue(written.exists())
        // A multi-page document is materially larger than a single page.
        assertTrue("expected a paginated pdf, got ${written.length()} bytes", written.length() > 3000)
        written.delete()
    }
}
