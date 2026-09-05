package com.labelguard.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labelguard.app.ocr.OcrEngine
import com.labelguard.app.pipeline.Consensus
import com.labelguard.app.pipeline.FieldExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises OCR -> extraction -> consensus on real hardware with the real
 * recogniser, against a rendered label. The camera cannot be aimed at a
 * product from a test, so the image is synthesised instead; everything after
 * the image is the production path.
 */
@RunWith(AndroidJUnit4::class)
class PipelineOnDeviceTest {

    private fun label(vararg lines: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 120 * lines.size + 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
        }
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, 40f, 110f + i * 120f, paint)
        }
        return bitmap
    }

    private val realisticLabel = arrayOf(
        "TASTY OATS",
        "MRP Rs. 45.00 incl. of all taxes",
        "Net Qty: 500 g",
        "Manufactured by Acme Foods, Jaipur",
        "Consumer care: care@acme.in"
    )

    /**
     * Runs the production per-frame path: OCR then field extraction.
     *
     * Recycles the bitmap afterwards. Each rendered label is several megabytes
     * and a five-frame test makes five of them; leaving them to the collector
     * exhausted the instrumentation process partway through the suite.
     */
    private fun frameFields(bitmap: Bitmap): Map<String, Consensus.Observation> = runBlocking {
        try {
            FieldExtractor.extract(OcrEngine.recognize(bitmap).lines)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun ocrAndExtractionFindTheRegulatedFields() {
        val fields = frameFields(label(*realisticLabel))

        assertNotNull("mrp not extracted; got ${fields.keys}", fields["mrp"])
        assertNotNull("net_quantity not extracted; got ${fields.keys}", fields["net_quantity"])
        assertNotNull("manufacturer not extracted; got ${fields.keys}", fields["manufacturer_address"])
        assertNotNull("consumer_care not extracted; got ${fields.keys}", fields["consumer_care"])
    }

    @Test
    fun extractedMrpAndQuantityNormaliseToTheExpectedValues() {
        val fields = frameFields(label(*realisticLabel))

        assertEquals(
            45.0,
            com.labelguard.app.pipeline.Normalize.money(fields["mrp"]!!.value)!!,
            0.001
        )
        assertEquals(
            Pair(500.0, "g"),
            com.labelguard.app.pipeline.Normalize.quantity(fields["net_quantity"]!!.value)
        )
    }

    @Test
    fun fiveIdenticalFramesReachFullConsensus() {
        val frames = List(5) { frameFields(label(*realisticLabel)) }
        val result = Consensus.build(frames)

        val mrp = result.fields["mrp"]
        assertNotNull("mrp had no consensus: ${result.failures}", mrp)
        assertEquals(5, mrp!!.agreement)
        assertEquals(1.0f, mrp.confidence, 0.001f)
    }

    @Test
    fun aMinorityMisreadIsDiscardedByConsensus() {
        // Three good frames plus two showing a different price: the majority
        // value survives and the outliers are dropped.
        val frames = List(3) { frameFields(label(*realisticLabel)) } +
            List(2) { frameFields(label("MRP Rs. 61.00", "Net Qty: 500 g")) }

        val result = Consensus.build(frames)
        val mrp = result.fields["mrp"]

        assertNotNull("mrp had no consensus: ${result.failures}", mrp)
        assertEquals(
            45.0,
            com.labelguard.app.pipeline.Normalize.money(mrp!!.value)!!,
            0.001
        )
        assertEquals(3, mrp.agreement)
    }

    @Test
    fun anEvenlySplitReadingReachesNoConsensus() {
        val frames = List(2) { frameFields(label("MRP Rs. 45.00")) } +
            List(2) { frameFields(label("MRP Rs. 61.00")) } +
            List(1) { frameFields(label("MRP Rs. 72.00")) }

        val result = Consensus.build(frames)

        assertNull(result.fields["mrp"])
        assertEquals("ocr_no_consensus", result.failures["mrp"]!!.reason)
    }

    @Test
    fun devanagariManufacturerAnchorIsTagged() {
        val fields = frameFields(label("निर्माता एक्मे फूड्स", "MRP Rs. 45.00"))

        assertNotNull(
            "devanagari manufacturer anchor not matched; got ${fields.keys}",
            fields["manufacturer_address"]
        )
    }

    @Test
    fun anUntaggedAddressIsNotPromotedToManufacturer() {
        val fields = frameFields(label("Acme Foods, Sitapura, Jaipur 302022", "MRP Rs. 45.00"))
        assertNull(fields["manufacturer_address"])
    }

    @Test
    fun aBlankLabelYieldsNoFields() {
        val blank = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.WHITE)
        }
        // frameFields recycles the bitmap it is given.
        assertTrue(frameFields(blank).isEmpty())
    }
}
