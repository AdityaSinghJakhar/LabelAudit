package com.labelaudit.app.data.remote

import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @GET("api/health")
    suspend fun getHealth(): HealthResponse

    @Multipart
    @POST("api/scan")
    suspend fun submitScan(@Part image: MultipartBody.Part): ScanAccepted
}
