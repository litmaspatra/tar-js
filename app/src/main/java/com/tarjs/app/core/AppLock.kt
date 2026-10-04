package com.tarjs.app.core

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App-level passcode lock with PBKDF2-SHA256 derivation.
 * Enforces lock state via navigation and state machine, not dismissible UI.
 */
class AppLock(
    private val context: Context,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond }
) {
    private val prefs get() = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    
    val configured: Boolean get() = prefs.contains("hash")
    
    /** Get the current lock state. Tracks setup, unlock time, and failure count. */
    val state: LockState
        get() {
            val configured = configured
            val unlockedAt = prefs.getLong("unlocked_at", 0L)
            val lockedUntil = prefs.getLong("locked_until", 0L)
            return when {
                !configured -> LockState.NOT_SET_UP
                lockedUntil > nowEpochSeconds() -> LockState.LOCKED_TOO_MANY_FAILURES
                lockedUntil != 0L -> {
                    prefs.edit().putLong("locked_until", 0L).putInt("failures", 0).apply()
                    LockState.LOCKED
                }
                unlockedAt == 0L -> LockState.LOCKED
                nowEpochSeconds() - unlockedAt !in 0 until UNLOCK_LIFETIME_SECONDS -> {
                    prefs.edit().putLong("unlocked_at", 0L).apply()
                    LockState.LOCKED
                }
                else -> LockState.UNLOCKED(unlockedAt)
            }
        }
    
    /** Check whether repeated failures have triggered the temporary lockout. */
    val isLockedAfterFailures: Boolean
        get() = prefs.getLong("locked_until", 0L) > nowEpochSeconds()
    
    /** Get the number of failed unlock attempts. */
    val failureCount: Int
        get() = prefs.getInt("failures", 0)
    
    /**
     * Set passcode for first time. Only allowed if not configured.
     * Validates minimum length and stores salted PBKDF2 hash.
     */
    fun setPasscode(value: String) {
        require(value.matches(Regex("\\d{4}"))) { "PIN must be exactly 4 digits" }
        require(!configured) { "Passcode already set" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.edit().apply {
            putString("salt", Base64.encodeToString(salt, 0))
            putString("hash", Base64.encodeToString(derive(value, salt), 0))
            putLong("unlocked_at", nowEpochSeconds())
            putInt("failures", 0)
        }.apply()
    }
    
    /**
     * Verify passcode. On success, sets unlock timestamp and clears failures.
     * On failure, increments failure count and locks after too many attempts.
     */
    fun verify(value: String): Boolean {
        if (!configured) return false
        val now = nowEpochSeconds()
        val lockedUntil = prefs.getLong("locked_until", 0L)
        if (lockedUntil > now) return false
        if (lockedUntil != 0L) {
            prefs.edit().putLong("locked_until", 0L).putInt("failures", 0).apply()
        }
        val salt = Base64.decode(prefs.getString("salt", "") ?: "", 0)
        val expected = Base64.decode(prefs.getString("hash", "") ?: "", 0)
        val matches = expected.contentEquals(derive(value, salt))
        prefs.edit().apply {
            if (matches) {
                putLong("unlocked_at", now)
                putInt("failures", 0)
                putLong("locked_until", 0L)
            } else {
                val failures = prefs.getInt("failures", 0) + 1
                putInt("failures", failures)
                if (failures >= MAX_FAILURES) {
                    putLong("unlocked_at", 0L)
                    putLong("locked_until", now + FAILURE_LOCKOUT_SECONDS)
                }
            }
        }.apply()
        return matches
    }
    
    /**
     * Cancel unlock attempt. Does NOT set unlock timestamp.
     * App remains locked. User must try again or return to home.
     */
    fun cancelUnlock() {
        // Clear any partial state, but do NOT set unlocked_at
        // This enforces that cancel keeps the app locked
    }
    
    /**
     * Lock the app (e.g., on background, timeout, or explicit lock).
     * Clears unlock timestamp but preserves failure count.
     */
    fun lockApp() {
        prefs.edit().putLong("unlocked_at", 0L).apply()
    }
    
    /**
     * Reset lock after too many failures. Requires setting up a new passcode.
     * Only call after user has gone through a recovery flow.
     */
    fun reset() {
        prefs.edit().clear().apply()
    }
    
    private fun derive(value: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(value.toCharArray(), salt, 120_000, 256))
            .encoded
    
    companion object {
        private const val MAX_FAILURES = 5
        private const val FAILURE_LOCKOUT_SECONDS = 30L
        private const val UNLOCK_LIFETIME_SECONDS = 15 * 60L
    }
}

/**
 * Lock state machine for the app.
 * Transitions:
 * - NOT_SET_UP -> setPasscode() -> UNLOCKED
 * - UNLOCKED -> lockApp() -> LOCKED
 * - LOCKED -> verify() -> UNLOCKED or stay LOCKED
 * - LOCKED -> cancelUnlock() -> LOCKED (no change)
 * - (any) -> too many failures -> LOCKED_TOO_MANY_FAILURES
 */
sealed class LockState {
    object NOT_SET_UP : LockState()
    object LOCKED : LockState()
    object LOCKED_TOO_MANY_FAILURES : LockState()
    data class UNLOCKED(val unlockedAt: Long) : LockState()
}
