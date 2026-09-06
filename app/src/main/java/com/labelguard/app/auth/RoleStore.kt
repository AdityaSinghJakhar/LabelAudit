package com.labelguard.app.auth

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The role this phone is currently operating in, and the gate for changing it.
 *
 * WHAT THIS IS NOT: authentication. A passcode checked on the same device it
 * is stored on keeps an honest user in the right lane; it does not stop anyone
 * who controls the phone. The passcode is stored only as a salted SHA-256
 * digest so it is not sitting in a file in the clear, but that is hygiene, not
 * a security boundary.
 *
 * Real authentication needs a server to authenticate against — the same server
 * the reference-sync design calls for. When that exists, [Role] is what it
 * would issue, and this class becomes the local cache of a verified claim
 * rather than the claim itself.
 */
class RoleStore(context: Context) {

    private val prefs = context.getSharedPreferences("labelguard_role", Context.MODE_PRIVATE)

    /** Persistent device identifier generated once per installation. */
    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    /** Bearer token issued by sync backend upon claiming inspector or registering. */
    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    /** Everyone starts as a shopper; the larger audience needs no setup. */
    var role: Role
        get() = runCatching { Role.valueOf(prefs.getString(KEY_ROLE, null) ?: "") }
            .getOrDefault(Role.CONSUMER)
        private set(value) {
            prefs.edit().putString(KEY_ROLE, value.name).apply()
        }

    /** True once an inspector passcode has been set on this device. */
    val hasPasscode: Boolean
        get() = prefs.getString(KEY_DIGEST, null) != null

    /**
     * Claim the inspector role.
     *
     * The first claim sets the passcode, because there is nobody to issue one:
     * this is a single device with no server behind it. That is a real
     * weakness and the reason the role governs what an assertion is *worth*
     * rather than being trusted on its own — an enrolled reference stays
     * incapable of failing a pack no matter who registered it.
     */
    fun claimInspector(passcode: String): Result {
        if (passcode.length < MIN_PASSCODE) {
            return Result.Rejected("Use at least $MIN_PASSCODE characters")
        }

        val stored = prefs.getString(KEY_DIGEST, null)
        if (stored == null) {
            val salt = newSalt()
            prefs.edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_DIGEST, digest(passcode, salt))
                .apply()
            role = Role.INSPECTOR
            return Result.Granted(firstTime = true)
        }

        val salt = prefs.getString(KEY_SALT, null) ?: return Result.Rejected("Passcode unusable")
        if (digest(passcode, salt) != stored) {
            return Result.Rejected("Passcode does not match")
        }

        role = Role.INSPECTOR
        return Result.Granted(firstTime = false)
    }

    /** Step back down. Never gated — giving up authority needs no proof. */
    fun releaseInspector() {
        role = Role.CONSUMER
        token = null
    }

    /**
     * Forget the passcode entirely, returning the device to a shopper's phone
     * with no inspector set up.
     */
    fun reset() {
        prefs.edit().clear().apply()
    }

    sealed interface Result {
        data class Granted(val firstTime: Boolean) : Result
        data class Rejected(val reason: String) : Result
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun digest(passcode: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + passcode).toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MIN_PASSCODE = 4
        private const val KEY_ROLE = "role"
        private const val KEY_DIGEST = "passcode_digest"
        private const val KEY_SALT = "passcode_salt"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "bearer_token"
    }
}
