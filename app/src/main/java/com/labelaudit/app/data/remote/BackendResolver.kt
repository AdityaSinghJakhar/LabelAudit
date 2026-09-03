package com.labelaudit.app.data.remote

import android.util.Log
import com.labelaudit.app.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The backend may be reachable over the LAN, through `adb reverse`, or at the
 * emulator's host alias, and which one works changes with how the phone is
 * connected. Rather than bake in a single address, probe the candidates and
 * remember the first that answers.
 */
object BackendResolver {

    private const val TAG = "BackendResolver"

    private val candidates: List<HttpUrl> = BuildConfig.API_BASE_URLS
        .split(",")
        .mapNotNull { it.trim().toHttpUrlOrNull() }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var resolved: HttpUrl? = null

    /** Drops the cached address so the next call probes again. */
    fun forget() {
        resolved = null
    }

    /**
     * Returns a reachable base URL, probing if one is not already known.
     * Falls back to the first candidate so callers still get a real error
     * rather than a null when nothing is up.
     */
    fun baseUrl(): HttpUrl {
        resolved?.let { return it }

        synchronized(this) {
            resolved?.let { return it }

            for (candidate in candidates) {
                if (isAlive(candidate)) {
                    Log.i(TAG, "Using backend at $candidate")
                    resolved = candidate
                    return candidate
                }
            }

            Log.w(TAG, "No backend responded; tried $candidates")
            return candidates.first()
        }
    }

    private fun isAlive(base: HttpUrl): Boolean = runCatching {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("api/health").build())
            .build()
        probeClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
