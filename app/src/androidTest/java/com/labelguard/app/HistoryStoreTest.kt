package com.labelguard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.history.HistoryStore
import com.labelguard.app.history.ScanRecord
import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The inspection history on a real device.
 *
 * The behaviour that carries weight is the conflict detection: it is the one
 * finding in the app that needs no reference data and nobody's word, because
 * the evidence is that two packs of the same product disagree with each other.
 */
@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: HistoryStore

    private fun record(
        id: String,
        brand: String? = "Gokul",
        mrp: String? = "140",
        quantity: String? = "500 g",
        verdict: Verdict = Verdict.PASS,
        checks: List<ScanRecord.Check> = emptyList(),
        at: Long = 1_000L
    ) = ScanRecord(
        id = id,
        scannedAt = at,
        verdict = verdict,
        rulesetVersion = "2026.1.0",
        skuId = null,
        brand = brand,
        mrp = mrp,
        netQuantity = quantity,
        batchNumber = "B1",
        mfgDate = "03/2025",
        framesUsed = 3,
        checks = checks,
        rawLines = listOf("MRP 140", "NET WT 500 g")
    )

    @Before
    fun setUp() {
        File(context.filesDir, "scan_history.json").delete()
        store = HistoryStore(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "scan_history.json").delete()
    }

    @Test
    fun anEmptyHistoryIsEmpty() {
        assertTrue(store.load().isEmpty())
        assertEquals(0, store.summarise().total)
    }

    @Test
    fun scansPersistAcrossInstances() {
        store.add(record("a"))

        val reopened = HistoryStore(context).load()
        assertEquals(1, reopened.size)
        assertEquals("Gokul", reopened.first().brand)
        assertEquals(listOf("MRP 140", "NET WT 500 g"), reopened.first().rawLines)
    }

    @Test
    fun aRecordSurvivesAJsonRoundTrip() {
        val original = record(
            "a",
            verdict = Verdict.FAIL,
            checks = listOf(
                ScanRecord.Check("EXPIRY-03", "Pack is within its declared date marking",
                    "expiry", RuleStatus.FAIL, "passed before today", "01/2026")
            )
        )
        val restored = ScanRecord.fromJson(original.toJson())

        assertEquals(original.id, restored.id)
        assertEquals(Verdict.FAIL, restored.verdict)
        assertEquals(1, restored.checks.size)
        assertEquals(RuleStatus.FAIL, restored.checks.first().status)
        assertEquals("01/2026", restored.checks.first().observedValue)
    }

    @Test
    fun anAbsentValueDoesNotComeBackAsTheStringNull() {
        val restored = ScanRecord.fromJson(record("a", mrp = null, brand = null).toJson())

        assertNull(restored.mrp)
        assertNull(restored.brand)
        assertEquals("Unidentified pack", restored.title)
    }

    @Test
    fun theNewestScanIsFirst() {
        store.add(record("old", at = 1_000L))
        store.add(record("new", at = 9_000L))

        assertEquals(listOf("new", "old"), store.load().map { it.id })
    }

    @Test
    fun deletingLeavesTheRest() {
        store.add(record("a"))
        store.add(record("b", at = 2_000L))
        store.remove("a")

        assertEquals(listOf("b"), store.load().map { it.id })
    }

    @Test
    fun theSummaryCountsScansNotChecks() {
        // One failing scan with three failing rules is one failure, not
        // three. Counting checks would inflate every dashboard figure by the
        // size of the ruleset.
        store.add(
            record(
                "a", verdict = Verdict.FAIL,
                checks = listOf(
                    ScanRecord.Check("MRP-01", "", "mrp", RuleStatus.FAIL, "", null),
                    ScanRecord.Check("QTY-01", "", "net_quantity", RuleStatus.FAIL, "", null),
                    ScanRecord.Check("FSSAI-01", "", "fssai_licence", RuleStatus.FAIL, "", null)
                )
            )
        )
        store.add(record("b", verdict = Verdict.PASS, at = 2_000L))

        val summary = store.summarise()
        assertEquals(2, summary.total)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.passed)
        assertEquals(1.0, summary.conclusiveRate, 0.001)
        assertEquals(3, summary.topViolations.size)
    }

    @Test
    fun anInconclusiveScanLowersTheConclusiveRate() {
        store.add(record("a", verdict = Verdict.PASS))
        store.add(record("b", verdict = Verdict.NOT_ASSESSABLE, at = 2_000L))

        assertEquals(0.5, store.summarise().conclusiveRate, 0.001)
    }

    // ------------------------------------------------------------ conflicts

    @Test
    fun packsOfOneProductDeclaringDifferentPricesConflict() {
        store.add(record("a", mrp = "20"))
        store.add(record("b", mrp = "20", at = 2_000L))
        store.add(record("c", mrp = "35", at = 3_000L))

        val conflicts = store.conflicts()
        assertEquals(1, conflicts.size)
        assertEquals("Gokul", conflicts.first().product)
        assertEquals(3, conflicts.first().scans)
        assertTrue(conflicts.first().conflictingPrices.containsAll(listOf("20", "35")))
    }

    @Test
    fun packsThatAgreeDoNotConflict() {
        store.add(record("a", mrp = "20"))
        store.add(record("b", mrp = "20", at = 2_000L))

        assertTrue(store.conflicts().isEmpty())
    }

    @Test
    fun differentProductsAreNotComparedWithEachOther() {
        store.add(record("a", brand = "Gokul", mrp = "20"))
        store.add(record("b", brand = "Kurkure", mrp = "35", at = 2_000L))

        assertTrue(
            "two different products may of course have different prices",
            store.conflicts().isEmpty()
        )
    }

    @Test
    fun unidentifiedPacksAreNotPooledTogether() {
        // Packs whose brand could not be read are not "the same product", and
        // pooling them would manufacture conflicts out of unrelated goods.
        store.add(record("a", brand = null, mrp = "20"))
        store.add(record("b", brand = null, mrp = "35", at = 2_000L))

        assertTrue(store.conflicts().isEmpty())
    }

    @Test
    fun aCorruptHistoryDoesNotBreakScanning() {
        File(context.filesDir, "scan_history.json").writeText("{ not json")

        assertTrue(HistoryStore(context).load().isEmpty())
        assertEquals(0, HistoryStore(context).summarise().total)
    }
}
