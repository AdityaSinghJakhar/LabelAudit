package com.labelaudit.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelaudit.app.registry.RegistrySource
import com.labelaudit.app.registry.SkuRecord
import com.labelaudit.app.registry.SkuStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Registry persistence and SKU matching on a real device.
 *
 * The behaviour that matters most is when the store declines to match:
 * comparing a pack against the wrong reference would report violations that
 * are really a mismatch of products, which is worse than having no reference
 * at all.
 */
@RunWith(AndroidJUnit4::class)
class SkuStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: SkuStore

    private val gokul500 = SkuRecord(
        skuId = "Gokul Namkeen 500g",
        brandStrings = listOf("गोकुल"),
        mrpExact = 140.0,
        netQuantity = "500 g",
        source = RegistrySource.ENROLLED_FROM_SCAN
    )

    private val gokul200 = SkuRecord(
        skuId = "Gokul Namkeen 200g",
        brandStrings = listOf("गोकुल"),
        mrpExact = 60.0,
        netQuantity = "200 g",
        source = RegistrySource.ENROLLED_FROM_SCAN
    )

    @Before
    fun setUp() {
        File(context.filesDir, "sku_registry.json").delete()
        store = SkuStore(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "sku_registry.json").delete()
    }

    @Test
    fun anEmptyRegistryMatchesNothing() {
        assertTrue(store.load().isEmpty())
        assertNull(store.bestMatch(mapOf("brand" to "गोकुल", "net_quantity" to "500 g")))
    }

    @Test
    fun recordsPersistAcrossInstances() {
        store.put(gokul500)

        val reopened = SkuStore(context).load()
        assertEquals(1, reopened.size)
        assertEquals("Gokul Namkeen 500g", reopened.first().skuId)
        assertEquals(RegistrySource.ENROLLED_FROM_SCAN, reopened.first().source)
    }

    @Test
    fun puttingTheSameIdReplacesRatherThanDuplicates() {
        store.put(gokul500)
        store.put(gokul500.copy(mrpExact = 150.0))

        val records = store.load()
        assertEquals(1, records.size)
        assertEquals(150.0, records.first().mrpExact!!, 0.001)
    }

    @Test
    fun removingLeavesTheRest() {
        store.put(gokul500)
        store.put(gokul200)
        store.remove("Gokul Namkeen 500g")

        assertEquals(listOf("Gokul Namkeen 200g"), store.load().map { it.skuId })
    }

    @Test
    fun thePackSizeDecidesWhichSkuMatches() {
        store.put(gokul500)
        store.put(gokul200)

        val matched = store.bestMatch(
            mapOf("brand" to "गोकुल", "net_quantity" to "200 g")
        )

        assertNotNull(matched)
        assertEquals("Gokul Namkeen 200g", matched!!.skuId)
    }

    @Test
    fun anOverprintedPriceStillMatchesItsSku() {
        store.put(gokul500)

        val matched = store.bestMatch(
            mapOf("brand" to "गोकुल", "net_quantity" to "500 g", "mrp" to "999")
        )

        // Recognising the pack is what allows the wrong price to be reported.
        assertNotNull("a wrong price must not stop recognition", matched)
        assertEquals("Gokul Namkeen 500g", matched!!.skuId)
    }

    @Test
    fun anAmbiguousMatchIsNoMatch() {
        // Two SKUs indistinguishable on the fields available. Picking either
        // would compare the pack against a reference it may not belong to.
        store.put(gokul500)
        store.put(gokul500.copy(skuId = "Gokul Namkeen 500g (old pack)"))

        assertNull(store.bestMatch(mapOf("brand" to "गोकुल", "net_quantity" to "500 g")))
    }

    @Test
    fun aWeakMatchIsRejected() {
        store.put(gokul500)

        // Right brand, wrong size: not this product.
        assertNull(store.bestMatch(mapOf("brand" to "गोकुल", "net_quantity" to "1 kg")))
    }

    @Test
    fun aRecordSurvivesAJsonRoundTrip() {
        val restored = SkuRecord.fromJson(gokul500.toJson())

        assertEquals(gokul500.skuId, restored.skuId)
        assertEquals(gokul500.brandStrings, restored.brandStrings)
        assertEquals(gokul500.mrpExact!!, restored.mrpExact!!, 0.001)
        assertEquals(gokul500.netQuantity, restored.netQuantity)
        assertEquals(gokul500.source, restored.source)
    }

    @Test
    fun aRecordWithNoOptionalValuesRoundTrips() {
        val restored = SkuRecord.fromJson(SkuRecord(skuId = "bare").toJson())

        assertEquals("bare", restored.skuId)
        assertNull(restored.mrpExact)
        assertNull(restored.netQuantity)
        assertTrue(restored.brandStrings.isEmpty())
    }

    @Test
    fun aRecordWithNoQuantityStillMatchesOnBrand() {
        // A missing value must survive persistence as missing. If it came back
        // as the string "null" it would be compared as a real declaration, and
        // a correct pack would be reported as contradicting a reference that
        // was never recorded.
        store.put(gokul500.copy(netQuantity = null, mrpExact = null))

        val matched = SkuStore(context).bestMatch(
            mapOf("brand" to "गोकुल", "net_quantity" to "500 g")
        )

        assertNotNull(matched)
        assertNull("an unrecorded quantity must not become a reference", matched!!.netQuantity)
    }

    @Test
    fun aCorruptRegistryDoesNotBreakScanning() {
        File(context.filesDir, "sku_registry.json").writeText("{ this is not json")

        // An unreadable registry makes comparisons inapplicable; it must not
        // stop the scanner from running.
        assertTrue(SkuStore(context).load().isEmpty())
        assertNull(SkuStore(context).bestMatch(mapOf("brand" to "x")))
    }
}
