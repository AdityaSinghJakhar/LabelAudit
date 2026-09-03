package com.labelaudit.app.data.repository

import com.labelaudit.app.data.remote.ApiClient
import com.labelaudit.app.data.remote.ScanAccepted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class ScanRepository(
    private val api: com.labelaudit.app.data.remote.ApiService = ApiClient.service
) {

    /** Uploads a captured label photo. Deletes the local file once it lands. */
    suspend fun uploadScan(image: File): Result<ScanAccepted> = withContext(Dispatchers.IO) {
        runCatching {
            val part = MultipartBody.Part.createFormData(
                name = "image",
                filename = image.name,
                body = image.asRequestBody("image/jpeg".toMediaType())
            )
            api.submitScan(part)
        }.onSuccess {
            image.delete()
        }
    }
}
