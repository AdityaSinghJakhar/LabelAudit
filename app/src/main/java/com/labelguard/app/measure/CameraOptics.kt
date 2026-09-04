package com.labelguard.app.measure

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture

/**
 * The lens numbers needed to turn pixels into millimetres.
 *
 * Two sources, and they arrive at different times. Sensor size and the focus
 * distance calibration level are fixed properties of the camera, read once
 * when it binds. Focal length and focus distance belong to an individual
 * frame and arrive with each capture result, so they are recorded as the
 * session runs and read when a photo is taken.
 *
 * Everything here is device-reported and every field is nullable, because a
 * phone is free to report none of it. When that happens the height rules see
 * no measurement and report NOT_ASSESSABLE, which is the correct outcome: the
 * app could not measure, and saying so is better than a number with nothing
 * behind it.
 */
class CameraOptics {

    // Fixed characteristics of the bound camera.
    @Volatile private var sensorWidthMm: Float? = null
    @Volatile private var sensorHeightMm: Float? = null
    @Volatile private var calibration: Int = Scale.CALIBRATION_UNCALIBRATED

    // Per-frame, updated as the capture session runs.
    @Volatile private var focalLengthMm: Float? = null
    @Volatile private var focusDiopters: Float? = null

    /**
     * A correction measured once on this device against an object of known
     * size. Until that flow exists this stays 1.0, and the wider
     * device-reported tolerance applies.
     */
    @Volatile var userCorrection: Double = 1.0

    val available: Boolean
        get() = sensorHeightMm != null && focalLengthMm != null && focusDiopters != null

    /** Why no measurement was possible, for the report's evidence line. */
    val unavailableReason: String?
        get() = when {
            sensorHeightMm == null -> "the camera did not report its sensor size"
            calibration == Scale.CALIBRATION_UNCALIBRATED ->
                "this device reports its focus distance as uncalibrated"
            focalLengthMm == null -> "the camera did not report a focal length"
            focusDiopters == null -> "the camera did not report a focus distance"
            else -> null
        }

    @OptIn(ExperimentalCamera2Interop::class)
    fun readCharacteristics(info: CameraInfo) {
        val characteristics = runCatching { Camera2CameraInfo.from(info) }.getOrNull() ?: return

        characteristics
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.let {
                sensorWidthMm = it.width
                sensorHeightMm = it.height
            }

        calibration = characteristics.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
        ) ?: Scale.CALIBRATION_UNCALIBRATED
    }

    /**
     * Attach to an ImageCapture so each frame's focal length and focus
     * distance are recorded as they arrive.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun attachTo(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    result.get(TotalCaptureResult.LENS_FOCAL_LENGTH)?.let {
                        focalLengthMm = it
                    }
                    result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE)?.let {
                        focusDiopters = it
                    }
                }
            }
        )
    }

    /**
     * The scale for an image of this size, or null when the device cannot
     * supply one.
     *
     * The image dimensions matter twice: they set the pixel pitch, and they
     * decide how much of the sensor the frame actually covers, since a wider
     * aspect ratio than the sensor's is produced by cropping it.
     */
    fun scaleFor(imageWidthPx: Int, imageHeightPx: Int): Scale? {
        val sensorW = sensorWidthMm?.toDouble() ?: return null
        val sensorH = sensorHeightMm?.toDouble() ?: return null
        val focal = focalLengthMm?.toDouble() ?: return null
        val diopters = focusDiopters?.toDouble() ?: return null

        return Scale.from(
            sensorMm = Scale.sensorHeightCovering(sensorW, sensorH, imageWidthPx, imageHeightPx),
            imagePx = imageHeightPx,
            focalLengthMm = focal,
            focusDiopters = diopters,
            calibration = calibration,
            userCorrection = userCorrection
        )
    }

    /** Discard per-frame values so a stale focus distance cannot be reused. */
    fun clearFrameValues() {
        focalLengthMm = null
        focusDiopters = null
    }
}
