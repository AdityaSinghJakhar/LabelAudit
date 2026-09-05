package com.labelguard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.eval.CorpusStore
import com.labelguard.app.pipeline.Box
import com.labelguard.app.pipeline.Consensus
import com.labelguard.app.pipeline.FieldExtractor
import com.labelguard.app.pipeline.RulesEngine
import com.labelguard.app.pipeline.Ruleset
import com.labelguard.app.report.ScanReport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Keeping scans so accuracy can be measured.
 *
 * The property that matters most is that the frames are **copied**. The
 * scanner deletes its originals in a `finally` block immediately afterwards,
 * so a store that moved them would turn one failed save into a lost image and
 * a corpus entry pointing at nothing.
 */
@RunWith(AndroidJUnit4::class)
class CorpusStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: CorpusStore

    private fun report(): ScanReport {
        val ruleset = context.assets.open("ruleset.yaml").use(Ruleset::load)
        val fields = Consensus.build(
            listOf(
                FieldExtractor.extract(
                    listOf(
                        com.labelguard.app.ocr.OcrLine(
                            "MAX RETAIL PRICE : 140/-", Box(10, 10, 400, 50)
                        )
                    )
                )
            )
        )
        return ScanReport.from(
            evaluation = RulesEngine.evaluate(ruleset, fields.fields),
            crops = emptyMap(),
            consensus = fields,
            framesUsed = 2,
            framesGated = 0,
            elapsedMs = 12,
            rawLines = listOf("MAX RETAIL PRICE : 140/-")
        )
    }

    /** Two throwaway JPEG-named files standing in for captured frames. */
    private fun frames(count: Int): List<File> = (1..count).map { index ->
        File(context.cacheDir, "corpus_test_$index.jpg").apply {
            writeBytes(ByteArray(64) { index.toByte() })
        }
    }

    @Before
    fun setUp() {
        store = CorpusStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        CorpusStore(context).clear()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("corpus_test_") }
            ?.forEach { it.delete() }
    }

    @Test
    fun anEmptyCorpusReportsItself() {
        assertTrue(store.entries().isEmpty())
        assertEquals(0L, store.sizeBytes())
        assertTrue(store.summary().contains("No scans"))
    }

    @Test
    fun aKeptScanHoldsItsFramesAndItsPrediction() {
        val entry = store.keep(report(), frames(2))

        assertNotNull("the scan should have been kept", entry)
        val files = entry!!.directory.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("frame-01.jpg", "frame-02.jpg", "scan.json"), files)
    }

    @Test
    fun theOriginalFramesAreLeftForTheCallerToDelete() {
        // The scanner deletes them itself, right after this returns. Moving
        // them here would destroy evidence on the caller's behalf.
        val originals = frames(2)
        store.keep(report(), originals)

        assertTrue("frames must be copied, not moved", originals.all { it.exists() })
    }

    @Test
    fun theKeptPredictionIsReadableByTheHarness() {
        val entry = store.keep(report(), frames(1))!!
        val json = JSONObject(File(entry.directory, "scan.json").readText())

        // The shape the Python harness matches on.
        assertEquals(entry.id, json.getString("image_id"))
        assertTrue(json.has("verdict"))
        assertTrue(json.has("fields"))
        assertTrue(json.has("raw_lines"))
        assertTrue(
            "the prediction must carry the value that was read",
            json.getJSONObject("fields").getJSONObject("mrp").getString("value").contains("140")
        )
    }

    @Test
    fun scansInTheSameSecondDoNotCollide() {
        // A bulk run finishes several scans inside one second. Sharing a
        // directory would overwrite one prediction with another.
        val first = store.keep(report(), frames(1))!!
        val second = store.keep(report(), frames(1))!!

        assertTrue("ids must differ: ${first.id}", first.id != second.id)
        assertEquals(2, store.entries().size)
    }

    @Test
    fun theNewestScanIsListedFirst() {
        store.keep(report(), frames(1))
        Thread.sleep(1100)
        val newer = store.keep(report(), frames(1))!!

        assertEquals(newer.id, store.entries().first().id)
    }

    @Test
    fun theSummaryStatesWhatIsOnDisk() {
        store.keep(report(), frames(3))

        val summary = store.summary()
        assertTrue(summary, summary.contains("1 scan"))
        assertTrue(summary, summary.contains("3 frame"))
        assertTrue("size must be visible; the store is unbounded", summary.contains("MB"))
    }

    @Test
    fun everyKeptFileCanBeSharedOffTheDevice() {
        store.keep(report(), frames(2))

        val shared = store.allFiles().map { it.name }
        assertTrue(shared.contains("scan.json"))
        assertEquals(2, shared.count { it.endsWith(".jpg") })
    }

    @Test
    fun clearingRemovesEverything() {
        store.keep(report(), frames(2))
        store.clear()

        assertTrue(store.entries().isEmpty())
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun aMissingFrameDoesNotLoseTheScan() {
        // A frame can be gone by the time the corpus runs. The prediction is
        // still worth keeping; a partial entry beats no entry.
        val present = frames(1)
        val missing = File(context.cacheDir, "corpus_test_gone.jpg")

        val entry = store.keep(report(), present + missing)

        assertNotNull(entry)
        assertTrue(File(entry!!.directory, "scan.json").exists())
    }
}
