package com.labelguard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.measure.Calibration
import com.labelguard.app.measure.CalibrationStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keeping a camera calibration across sessions.
 *
 * Persistence matters more here than for most stored values: a calibration
 * that failed to survive a restart would leave the app measuring with the
 * wide uncorrected band while the settings screen still said it was
 * calibrated, and nothing in the output would show the difference.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: CalibrationStore

    private val measured = Calibration(
        correction = 1.07,
        referenceName = "Bank card, long edge",
        referenceMm = 85.60,
        measuredPx = 1043,
        diopters = 5.0,
        at = 1_700_000_000_000L
    )

    @Before
    fun setUp() {
        store = CalibrationStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        CalibrationStore(context).clear()
    }

    @Test
    fun anUncalibratedDeviceHasNothingStored() {
        assertNull(store.load())
    }

    @Test
    fun aCalibrationSurvivesAcrossInstances() {
        store.save(measured)

        val restored = CalibrationStore(context).load()
        assertNotNull(restored)
        assertEquals(1.07, restored!!.correction, 0.0001)
        assertEquals("Bank card, long edge", restored.referenceName)
        assertEquals(85.60, restored.referenceMm, 0.01)
        assertEquals(1043, restored.measuredPx)
        assertEquals(5.0, restored.diopters, 0.001)
    }

    @Test
    fun savingReplacesRatherThanAccumulates() {
        store.save(measured)
        store.save(measured.copy(correction = 0.94, measuredPx = 900))

        val restored = CalibrationStore(context).load()!!
        assertEquals(0.94, restored.correction, 0.0001)
        assertEquals(900, restored.measuredPx)
    }

    @Test
    fun clearingRemovesIt() {
        store.save(measured)
        store.clear()

        assertNull(CalibrationStore(context).load())
    }

    @Test
    fun aRestoredCalibrationKeepsItsDistanceWindow() {
        store.save(measured)
        val restored = CalibrationStore(context).load()!!

        // The window is what stops a calibration being extrapolated to a
        // distance it says nothing about, so it has to survive the round trip.
        assertTrue(restored.appliesAt(5.0))
        assertTrue(restored.appliesAt(8.0))
        assertFalse(restored.appliesAt(40.0))
    }
}
