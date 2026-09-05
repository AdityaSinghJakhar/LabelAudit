package com.labelguard.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BoundingBox(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int
)

@Serializable
data class OcrToken(
    val text: String,
    val confidence: Float,
    val bbox: BoundingBox
)

@Serializable
data class OcrResult(
    val tokens: List<OcrToken>,
    @SerialName("full_text") val fullText: String,
    @SerialName("processing_time_ms") val processingTimeMs: Int,
    val model: String
)

@Serializable
data class ScanAccepted(
    @SerialName("scan_id") val scanId: String,
    @SerialName("image_key") val imageKey: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("received_at") val receivedAt: String,
    val ocr: OcrResult
)
