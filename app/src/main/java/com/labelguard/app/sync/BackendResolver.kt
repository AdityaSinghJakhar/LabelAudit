package com.labelguard.app.sync

import android.util.Log
import com.labelguard.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes candidate backend addresses and remembers the first one that answers
 * /api/health for the rest of the session.
 *
 * Candidate addresses (defined in BuildConfig.API_BASE_URLS):
 *   - LAN IP (works over Wi-Fi)
 *   - 127.0.0.1 (works with adb reverse tcp:8000 tcp:8000)
 *   - 10.0.2.2 (Android emulator host loopback)
 */
object BackendResolver {

    private const val TAG = "BackendResolver"
    private const val TIMEOUT_MS = 1500

    private val candidates: List<String> by lazy {
        BuildConfig.API_BASE_URLS
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.endsWith("/")) it else "$it/" }
    }

    @Volatile
    private var resolved: String? = null

    /** Clears cached address so the next call probes again. */
    fun forget() {
        resolved = null
    }

    /**
     * Returns a reachable base URL, probing candidate endpoints if not already resolved.
     * Falls back to the first candidate if none responds so callers get a clean error.
     */
    fun baseUrl(): String {
        resolved?.let { return it }

        synchronized(this) {
            resolved?.let { return it }

            for (candidate in candidates) {
                if (isAlive(candidate)) {
                    Log.i(TAG, "Backend reached at $candidate")
                    resolved = candidate
                    return candidate
                }
            }

            val fallback = candidates.firstOrNull() ?: "http://10.0.2.2:8000/"
            Log.w(TAG, "No backend responded to probe; falling back to $fallback")
            resolved = fallback
            return fallback
        }
    }

    fun isOnline(): Boolean {
        val current = resolved ?: return runCatching { isAlive(baseUrl()) }.getOrDefault(false)
        return isAlive(current)
    }

    private fun isAlive(base: String): Boolean = runCatching {
        val healthUrl = URL("${base}api/health")
        val conn = (healthUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
        }
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
