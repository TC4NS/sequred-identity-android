package co.sequred.identity.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the user's PIN with an Android Keystore key that requires biometric
 * auth on every use. The plaintext PIN never lives on disk.
 *
 * Important: because the Keystore key has `setUserAuthenticationRequired(true)`,
 * BOTH encrypt and decrypt operations must be performed inside a successful
 * `BiometricPrompt.CryptoObject` callback. Trying to encrypt directly outside
 * a prompt throws `KEY_USER_NOT_AUTHENTICATED` and crashes the app.
 *
 * Enrollment flow:
 *   • cipherForEnroll()        → caller hands cipher to BiometricPrompt
 *   • completeEnrollment(c, p) → call from prompt success; persists ciphertext
 *
 * Unlock flow:
 *   • cipherForDecrypt()       → caller hands cipher to BiometricPrompt
 *   • decryptPin(cipher)       → call from prompt success
 */
class BiometricStore(context: Context) {

    private val prefs = run {
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
            context.deleteSharedPreferences(PREF_NAME_ENC)
            EncryptedSharedPreferences.create(
                context, PREF_NAME_ENC, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        // Migrate the pre-encryption wrapped-PIN blob. The Keystore key that
        // wraps the PIN doesn't change — we're only moving where the
        // ciphertext lives. Without this, a user with biometric unlock
        // enrolled on an older build gets prompted to re-enrol on upgrade.
        val legacy = context.getSharedPreferences(PREF_NAME_LEGACY, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            encrypted.edit().apply {
                legacy.getString(KEY_CIPHERTEXT, null)?.let { putString(KEY_CIPHERTEXT, it) }
                legacy.getString(KEY_IV, null)?.let { putString(KEY_IV, it) }
            }.apply()
            legacy.edit().clear().apply()
            context.deleteSharedPreferences(PREF_NAME_LEGACY)
        }
        encrypted
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    /**
     * Provision a fresh keystore key and return an encrypt-mode cipher init'd
     * with it. The cipher is uninitialised against any data — hand to the
     * BiometricPrompt, then call [completeEnrollment] in the success callback.
     */
    fun cipherForEnroll(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Finalise enrollment after a successful biometric prompt. */
    fun completeEnrollment(cipher: Cipher, pin: String) {
        val ct = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun cipherForDecrypt(): Cipher {
        val ivB64 = prefs.getString(KEY_IV, null) ?: error("biometric not enrolled")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val key = getKey() ?: error("biometric key missing — re-enrol")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    fun decryptPin(cipher: Cipher): String {
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null) ?: error("biometric not enrolled")
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun clear() {
        deleteKey()
        prefs.edit().clear().apply()
    }

    // ─── Keystore helpers ────────────────────────────────────────────────────

    private fun generateKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        // API 28+: refuse the key while the screen is locked. Closes a window
        // where a malicious foreground app could submit a CryptoObject
        // operation while the lock screen is up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(builder.build())
        return gen.generateKey()
    }

    private fun getKey(): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    companion object {
        private const val PREF_NAME_LEGACY = "sq.bio"     // pre-encryption file
        private const val PREF_NAME_ENC = "sq.bio.enc"    // EncryptedSharedPreferences
        private const val KEY_CIPHERTEXT = "ct"
        private const val KEY_IV = "iv"
        private const val KEY_ALIAS = "sq_pin_wrap_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
