package com.labelguard.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.measure.Scale
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What this handset can actually tell us about its own optics.
 *
 * A capability probe, not a behaviour test. Character height is measured from
 * sensor size, focal length and focus distance, and whether those are usable
 * is a property of the phone rather than of any code here. These tests exist
 * so the answer is stated plainly by the suite instead of being inferred from
 * a NOT_APPLICABLE row on a report.
 *
 * If the calibration probe fails, that is the finding, not a defect: this
 * device cannot support Rule 9 measurement, and the per-device calibration
 * flow becomes necessary rather than optional.
 */
@RunWith(AndroidJUnit4::class)
class CameraCapabilityTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val manager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** The first back-facing camera, which is what the scanner binds. */
    private fun backCamera(): CameraCharacteristics? =
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }?.let { manager.getCameraCharacteristics(it) }

    @Test
    fun thereIsABackCamera() {
        assertNotNull("no back-facing camera on this device", backCamera())
    }

    @Test
    fun theCameraReportsItsSensorSize() {
        val size = backCamera()?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        assertNotNull(
            "SENSOR_INFO_PHYSICAL_SIZE is absent, so no millimetre scale is possible",
            size
        )
        assertTrue("sensor dimensions must be positive: $size", size!!.width > 0 && size.height > 0)
    }

    @Test
    fun theCameraReportsAFocalLength() {
        val lengths = backCamera()?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

        assertNotNull("no focal length reported", lengths)
        assertTrue("focal length list is empty", lengths!!.isNotEmpty())
        assertTrue("focal length must be positive: ${lengths.toList()}", lengths[0] > 0)
    }

    /**
     * Measured on a CPH2619 (Android 16): APPROXIMATE. Usable, but it carries
     * the wide +/-30% tolerance, which is why every height finding on this
     * class of handset lands on NEEDS_REVIEW until a per-device calibration
     * narrows it.
     */
    @Test
    fun thisDeviceCanMeasureCharacterHeight() {
        // The decisive one. UNCALIBRATED means the phone's focus distances are
        // not in real units, and Scale refuses to build a measurement from
        // them — correctly, but it makes CAP-01 and CAP-02 inert here.
        val level = backCamera()
            ?.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)

        val name = when (level) {
            Scale.CALIBRATION_CALIBRATED -> "CALIBRATED"
            Scale.CALIBRATION_APPROXIMATE -> "APPROXIMATE"
            Scale.CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
            else -> "absent"
        }

        assertTrue(
            "this device reports focus distance calibration as $name, so " +
                "character height cannot be measured from its optics alone. " +
                "That is a fact about the handset: the per-device calibration " +
                "flow is required before CAP-01 and CAP-02 can do anything here.",
            level == Scale.CALIBRATION_CALIBRATED || level == Scale.CALIBRATION_APPROXIMATE
        )
    }
}
