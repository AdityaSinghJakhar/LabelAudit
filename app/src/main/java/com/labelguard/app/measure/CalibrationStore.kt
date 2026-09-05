package com.labelguard.app.measure

import android.content.Context

/**
 * The calibration this device is carrying, if any.
 *
 * One record, not a history: a phone has one camera and one systematic error,
 * and a later measurement supersedes an earlier one rather than joining it.
 */
class CalibrationStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("labelguard_calibration", Context.MODE_PRIVATE)

    fun load(): Calibration? {
        val correction = prefs.getFloat(KEY_CORRECTION, 0f).toDouble()
        if (correction <= 0) return null

        return Calibration(
            correction = correction,
            referenceName = prefs.getString(KEY_NAME, "") ?: "",
            referenceMm = prefs.getFloat(KEY_MM, 0f).toDouble(),
            measuredPx = prefs.getInt(KEY_PX, 0),
            diopters = prefs.getFloat(KEY_DIOPTERS, 0f).toDouble(),
            at = prefs.getLong(KEY_AT, 0L)
        )
    }

    fun save(calibration: Calibration) {
        prefs.edit()
            .putFloat(KEY_CORRECTION, calibration.correction.toFloat())
            .putString(KEY_NAME, calibration.referenceName)
            .putFloat(KEY_MM, calibration.referenceMm.toFloat())
            .putInt(KEY_PX, calibration.measuredPx)
            .putFloat(KEY_DIOPTERS, calibration.diopters.toFloat())
            .putLong(KEY_AT, calibration.at)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_CORRECTION = "correction"
        const val KEY_NAME = "reference_name"
        const val KEY_MM = "reference_mm"
        const val KEY_PX = "measured_px"
        const val KEY_DIOPTERS = "diopters"
        const val KEY_AT = "at"
    }
}
