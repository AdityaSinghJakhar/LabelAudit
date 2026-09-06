package com.labelguard.app.sync

import android.util.Log
import com.labelguard.app.history.HistoryStore
import com.labelguard.app.history.ScanRecord
import com.labelguard.app.measure.Calibration
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.registry.SkuRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Client for communicating with the LabelGuard sync backend.
 *
 * All network calls return Kotlin [Result] objects so failures never throw
 * across boundary lines or interrupt the offline scanner.
 */
object SyncClient {

    private const val TAG = "SyncClient"
    private const val TIMEOUT_MS = 6000

    data class ClaimResponse(
        val token: String,
        val role: String,
        val firstTime: Boolean
    )

    /** Syncs one completed scan to the backend. */
    fun syncScan(
        record: ScanRecord,
        deviceId: String,
        token: String? = null
    ): Result<String> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/scans"
        val payload = record.toJson().toString()

        val response = postJson(endpoint, payload, deviceId, token)
        val json = JSONObject(response)
        json.getString("id")
    }.onFailure { Log.w(TAG, "Failed to sync scan ${record.id}: ${it.message}") }

    /** Pulls all registered reference SKUs from the shared backend registry. */
    fun fetchSkus(
        deviceId: String,
        token: String? = null
    ): Result<List<SkuRecord>> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/skus"
        val response = get(endpoint, deviceId, token)
        val array = JSONArray(response)

        (0 until array.length()).map { SkuRecord.fromJson(array.getJSONObject(it)) }
    }.onFailure { Log.w(TAG, "Failed to fetch SKUs: ${it.message}") }

    /** Enrols a reference SKU on the backend (Inspector-only). */
    fun enrolSku(
        sku: SkuRecord,
        deviceId: String,
        token: String
    ): Result<SkuRecord> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/skus"
        val payload = sku.toJson().toString()

        val response = postJson(endpoint, payload, deviceId, token)
        SkuRecord.fromJson(JSONObject(response))
    }.onFailure { Log.w(TAG, "Failed to enrol SKU ${sku.skuId}: ${it.message}") }

    /** Claims inspector role with backend using passcode. */
    fun claimInspector(
        deviceId: String,
        passcode: String
    ): Result<ClaimResponse> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/devices/claim"
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("passcode", passcode)
            .toString()

        val response = postJson(endpoint, body, deviceId, null)
        val json = JSONObject(response)
        ClaimResponse(
            token = json.getString("token"),
            role = json.getString("role"),
            firstTime = json.optBoolean("first_time", false)
        )
    }.onFailure { Log.w(TAG, "Failed to claim inspector on backend: ${it.message}") }

    /** Releases inspector role on backend. */
    fun releaseInspector(
        deviceId: String,
        token: String
    ): Result<Unit> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/devices/release"
        postJson(endpoint, "{}", deviceId, token)
        Unit
    }

    /** Syncs camera calibration factor so reinstalls don't lose correction. */
    fun syncCalibration(
        deviceId: String,
        cal: Calibration,
        token: String? = null
    ): Result<Unit> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/calibrations"
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("correction", cal.correction)
            .put("reference_name", cal.referenceName)
            .put("reference_mm", cal.referenceMm)
            .put("measured_px", cal.measuredPx)
            .put("diopters", cal.diopters)
            .put("at", cal.at)
            .toString()

        postJson(endpoint, body, deviceId, token)
        Unit
    }.onFailure { Log.w(TAG, "Failed to sync calibration: ${it.message}") }

    /** Fetches saved calibration for a device ID if present. */
    fun fetchCalibration(
        deviceId: String
    ): Result<Calibration?> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/calibrations/$deviceId"
        val response = get(endpoint, deviceId, null)
        val json = JSONObject(response)
        Calibration(
            correction = json.getDouble("correction"),
            referenceName = json.getString("reference_name"),
            referenceMm = json.getDouble("reference_mm"),
            measuredPx = json.getInt("measured_px"),
            diopters = json.getDouble("diopters"),
            at = json.optLong("at", System.currentTimeMillis())
        )
    }.recoverCatching { null }

    /** Fetches fleet-wide declarations that contradict across packs. */
    fun fetchConflicts(): Result<List<HistoryStore.Conflict>> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/conflicts"
        val response = get(endpoint, "", null)
        val array = JSONArray(response)

        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val prices = obj.optJSONArray("conflicting_prices") ?: JSONArray()
            val quantities = obj.optJSONArray("conflicting_quantities") ?: JSONArray()

            HistoryStore.Conflict(
                product = obj.getString("product"),
                scans = obj.getInt("scans"),
                conflictingPrices = (0 until prices.length()).map { prices.getString(it) },
                conflictingQuantities = (0 until quantities.length()).map { quantities.getString(it) }
            )
        }
    }

    /** Fetches fleet reporting summary across all synced scans. */
    fun fetchFleetSummary(): Result<HistoryStore.Summary> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/reports/summary"
        val response = get(endpoint, "", null)
        val json = JSONObject(response)

        val byVerdictObj = json.optJSONObject("by_verdict") ?: JSONObject()
        val byVerdict = mutableMapOf<Verdict, Int>()
        for (key in byVerdictObj.keys()) {
            runCatching { Verdict.valueOf(key) }.getOrNull()?.let {
                byVerdict[it] = byVerdictObj.getInt(key)
            }
        }

        val topViolationsArray = json.optJSONArray("top_violations") ?: JSONArray()
        val topViolations = (0 until topViolationsArray.length()).map { i ->
            val v = topViolationsArray.getJSONObject(i)
            v.getString("rule_id") to v.getInt("count")
        }

        HistoryStore.Summary(
            total = json.getInt("total"),
            byVerdict = byVerdict,
            topViolations = topViolations,
            distinctProducts = json.getInt("distinct_products")
        )
    }

    /** Uploads evaluation frames and predictions passively. */
    fun uploadCorpus(
        cid: String,
        scanJson: String,
        frames: List<File>,
        deviceId: String,
        token: String? = null
    ): Result<Unit> = runCatching {
        val endpoint = "${BackendResolver.baseUrl()}api/v1/corpus/upload"
        val boundary = "==Boundary_${System.currentTimeMillis()}=="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (deviceId.isNotBlank()) {
                setRequestProperty("X-Device-Id", deviceId)
            }
        }

        DataOutputStream(conn.outputStream).use { out ->
            // text form field: corpus_id
            out.writeBytes("$twoHyphens$boundary$lineEnd")
            out.writeBytes("Content-Disposition: form-data; name=\"corpus_id\"$lineEnd$lineEnd")
            out.writeBytes("$cid$lineEnd")

            // text form field: device_id
            out.writeBytes("$twoHyphens$boundary$lineEnd")
            out.writeBytes("Content-Disposition: form-data; name=\"device_id\"$lineEnd$lineEnd")
            out.writeBytes("$deviceId$lineEnd")

            // text form field: scan_json
            out.writeBytes("$twoHyphens$boundary$lineEnd")
            out.writeBytes("Content-Disposition: form-data; name=\"scan_json\"$lineEnd$lineEnd")
            out.write(scanJson.toByteArray(StandardCharsets.UTF_8))
            out.writeBytes(lineEnd)

            // image files: frames
            for ((idx, frame) in frames.withIndex()) {
                if (!frame.exists()) continue
                val filename = "frame-${String.format("%02d", idx + 1)}.jpg"
                out.writeBytes("$twoHyphens$boundary$lineEnd")
                out.writeBytes("Content-Disposition: form-data; name=\"frames\"; filename=\"$filename\"$lineEnd")
                out.writeBytes("Content-Type: image/jpeg$lineEnd$lineEnd")

                FileInputStream(frame).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                }
                out.writeBytes(lineEnd)
            }

            out.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
            out.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Corpus upload failed with HTTP $code: $err")
        }
    }.onFailure { Log.w(TAG, "Failed to upload corpus $cid: ${it.message}") }

    // -------------------------------------------------------------------------
    // Internal HTTP helpers
    // -------------------------------------------------------------------------

    private fun postJson(
        endpoint: String,
        body: String,
        deviceId: String,
        token: String?
    ): String {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (deviceId.isNotBlank()) {
                setRequestProperty("X-Device-Id", deviceId)
            }
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        conn.outputStream.use { os ->
            os.write(body.toByteArray(StandardCharsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("POST $endpoint failed with HTTP $code: $err")
        }

        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun get(
        endpoint: String,
        deviceId: String,
        token: String?
    ): String {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (deviceId.isNotBlank()) {
                setRequestProperty("X-Device-Id", deviceId)
            }
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("GET $endpoint failed with HTTP $code: $err")
        }

        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
