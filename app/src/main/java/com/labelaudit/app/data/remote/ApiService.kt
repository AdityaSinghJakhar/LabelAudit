package com.labelaudit.app.data.remote

import retrofit2.http.GET

interface ApiService {

    @GET("api/health")
    suspend fun getHealth(): HealthResponse
}
