package com.labelguard.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.labelguard.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Single Retrofit instance for the app. Dependency injection comes later;
 * for now a lazy singleton keeps the wiring obvious.
 */
object ApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Retrofit needs a base URL up front, but the reachable address is only
     * known at runtime, so requests are retargeted here on the way out.
     */
    private val hostInterceptor = Interceptor { chain ->
        val base = BackendResolver.baseUrl()
        val request = chain.request()
        val retargeted = request.newBuilder()
            .url(
                request.url.newBuilder()
                    .scheme(base.scheme)
                    .host(base.host)
                    .port(base.port)
                    .build()
            )
            .build()

        try {
            chain.proceed(retargeted)
        } catch (e: IOException) {
            // The chosen host stopped answering — the phone may have moved
            // networks. Re-probe so the next attempt can pick another.
            BackendResolver.forget()
            throw e
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(hostInterceptor)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
        )
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val service: ApiService by lazy {
        Retrofit.Builder()
            // Placeholder; hostInterceptor rewrites this per request.
            .baseUrl("http://127.0.0.1:8000/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}
