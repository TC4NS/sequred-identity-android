package co.sequred.identity.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.sequred.identity.crypto.CoreBridge
import co.sequred.identity.crypto.PinVerifyResult

/**
 * Persists per-device PIN material plus the throttling state. The PIN itself
 * is never stored — we hash it with Argon2id and verify by recompute in
 * constant time inside the Rust core.
 *
 * Backed by EncryptedSharedPreferences so the hash, salt, AND the throttle
 * counter are sealed under an AES-256 master key in the Android Keystore.
 * Without that, a rooted-device attacker could (a) read the salt+hash and
 * brute-force a 4-digit PIN offline and (b) reset the failure counter to
 * bypass the lockout ladder. Throttling matches the iOS app:
 * 3rd fail = 30s, 4th = 60s, 5th = 120s, 6th = 300s, 7th+ = 600s.
 */
class PinStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = try {
            EncryptedSharedPreferences.create(
                context, PREF_NAME_ENC, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            // Keystore master key got invalidated (factory reset of biometrics
            // can do this). Wipe the encrypted prefs file and recreate so we
            // don't crash-loop. Vault remains intact — it's keyed by the PIN.
            context.deleteSharedPreferences(PREF_NAME_ENC)
            EncryptedSharedPreferences.create(
                context, PREF_NAME_ENC, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        migrateFromLegacyIfNeeded(context, encrypted)
        encrypted
    }

    /**
     * One-shot migration of the pre-encryption MODE_PRIVATE prefs into the new
     * EncryptedSharedPreferences store. Runs at most once per install: after a
     * successful copy the legacy file is deleted so future launches skip the
     * migration entirely. Without this the upgrade would lose the user's PIN
     * hash + throttle state and force them back to first-run setup.
     */
    private fun migrateFromLegacyIfNeeded(context: Context, encrypted: SharedPreferences) {
        val legacy = context.getSharedPreferences(PREF_NAME_LEGACY, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        encrypted.edit().apply {
            legacy.getString(KEY_SALT, null)?.let { putString(KEY_SALT, it) }
            legacy.getString(KEY_HASH, null)?.let { putString(KEY_HASH, it) }
            if (legacy.contains(KEY_FAIL_COUNT)) putInt(KEY_FAIL_COUNT, legacy.getInt(KEY_FAIL_COUNT, 0))
            if (legacy.contains(KEY_LOCKED_UNTIL)) putLong(KEY_LOCKED_UNTIL, legacy.getLong(KEY_LOCKED_UNTIL, 0L))
            if (legacy.contains(KEY_IDLE_MINUTES)) putInt(KEY_IDLE_MINUTES, legacy.getInt(KEY_IDLE_MINUTES, 5))
        }.apply()
        legacy.edit().clear().apply()
        context.deleteSharedPreferences(PREF_NAME_LEGACY)
    }

    fun isProvisioned(): Boolean =
        prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = CoreBridge.randomBytes(SALT_BYTES)
        val hash = CoreBridge.pinHash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(pin: String): PinVerifyResult {
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return PinVerifyResult.NoMatch
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return PinVerifyResult.NoMatch
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val hash = Base64.decode(hashB64, Base64.NO_WRAP)
        val result = CoreBridge.pinVerify(pin, salt, hash)
        if (result is PinVerifyResult.MatchNeedsRehash) setPin(pin)
        return result
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    // ─── Throttling ──────────────────────────────────────────────────────────

    /** Returns the new cooldown (seconds) after recording this failure. */
    fun recordFailure(): Int {
        val newCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val cooldown = cooldownFor(newCount)
        val until = if (cooldown > 0) System.currentTimeMillis() + cooldown * 1000L else 0L
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, newCount)
            .putLong(KEY_LOCKED_UNTIL, until)
            .apply()
        return cooldown
    }

    /** Seconds remaining in the current lockout, or 0 if not locked out. */
    fun secondsUntilUnlocked(): Int {
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    fun clearThrottle() {
        prefs.edit()
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_LOCKED_UNTIL)
            .apply()
    }

    /** Matches the ladder in iOS AppState.recordPINFailure(). */
    private fun cooldownFor(failCount: Int): Int = when (failCount) {
        in 0..2 -> 0
        3 -> 30
        4 -> 60
        5 -> 120
        6 -> 300
        else -> 600
    }

    // ─── User preferences (persisted with the PIN store for convenience) ────

    var inactivityTimeoutMinutes: Int
        get() = prefs.getInt(KEY_IDLE_MINUTES, 5)
        set(value) { prefs.edit().putInt(KEY_IDLE_MINUTES, value.coerceIn(0, 60)).apply() }

    companion object {
        private const val PREF_NAME_LEGACY = "sq.pin"     // pre-encryption file
        private const val PREF_NAME_ENC = "sq.pin.enc"    // EncryptedSharedPreferences
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
        private const val KEY_FAIL_COUNT = "fc"
        private const val KEY_LOCKED_UNTIL = "lu"
        private const val KEY_IDLE_MINUTES = "idle_min"
        private const val SALT_BYTES = 16
    }
}
