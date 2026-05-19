package co.sequred.identity.crypto

import co.sequred.identity.data.AccountVault
import co.sequred.identity.data.VaultPayload
import co.sequred.identity.data.vaultJson
import kotlinx.serialization.encodeToString
import uniffi.sequred_core.PinAlgoResult
import uniffi.sequred_core.coreVersion
import uniffi.sequred_core.defaultArgon2Iters
import uniffi.sequred_core.defaultArgon2MemoryKb
import uniffi.sequred_core.derivedFingerprint
import uniffi.sequred_core.derivePassphrase as ffiDerivePassphrase
import uniffi.sequred_core.derivePassword as ffiDerivePassword
import uniffi.sequred_core.pinHash as ffiPinHash
import uniffi.sequred_core.pinVerify as ffiPinVerify
import uniffi.sequred_core.vaultDecrypt as ffiVaultDecrypt
import uniffi.sequred_core.vaultEncrypt as ffiVaultEncrypt
import uniffi.sequred_core.vaultNeedsKdfUpgrade as ffiVaultNeedsKdfUpgrade
import uniffi.sequred_core.exportOpen as ffiExportOpen
import uniffi.sequred_core.exportSeal as ffiExportSeal
import java.security.SecureRandom

/**
 * Thin Kotlin wrapper over the UniFFI surface. Centralising the calls here
 * means the rest of the app never imports `uniffi.*` directly, which keeps
 * the UI free of FFI-specific types and makes future migration to a pure-JVM
 * implementation (if we ever need one) a single-file change.
 */
object CoreBridge {

    fun version(): String = coreVersion()
    fun argon2DefaultMemoryKb(): Int = defaultArgon2MemoryKb().toInt()
    fun argon2DefaultIters(): Int = defaultArgon2Iters().toInt()

    fun derivePassword(
        master: String,
        site: String,
        username: String,
        pin: String,
        length: Int,
        useUpper: Boolean,
        useLower: Boolean,
        useDigits: Boolean,
        useSymbols: Boolean,
        version: Int,
    ): String = ffiDerivePassword(
        privatePassword = master,
        site = site,
        username = username,
        pin = pin,
        length = length.toUInt(),
        useUpper = useUpper,
        useLower = useLower,
        useDigits = useDigits,
        useSymbols = useSymbols,
        version = version,
    )

    fun derivePassphrase(
        master: String,
        site: String,
        username: String,
        pin: String,
        wordCount: Int,
        separator: String,
        version: Int,
    ): String = ffiDerivePassphrase(
        privatePassword = master,
        site = site,
        username = username,
        pin = pin,
        wordCount = wordCount.toUInt(),
        separator = separator,
        version = version,
    )

    fun fingerprint(derived: String): ByteArray = derivedFingerprint(derived)

    fun pinHash(pin: String, salt: ByteArray): ByteArray = ffiPinHash(pin, salt)

    fun pinVerify(pin: String, salt: ByteArray, storedHash: ByteArray): PinVerifyResult =
        when (ffiPinVerify(pin, salt, storedHash)) {
            PinAlgoResult.MATCH -> PinVerifyResult.Match
            PinAlgoResult.NEEDS_REHASH_LEGACY_ARGON -> PinVerifyResult.MatchNeedsRehash
            PinAlgoResult.NEEDS_REHASH_LEGACY_PBKDF2 -> PinVerifyResult.MatchNeedsRehash
            PinAlgoResult.NO_MATCH -> PinVerifyResult.NoMatch
        }

    /**
     * Decrypts the AccountVault and returns the inner payload parsed.
     * Throws on wrong PIN or malformed ciphertext.
     */
    fun decryptPayload(vault: AccountVault, pin: String): VaultPayload {
        val vaultJsonStr = vaultJson.encodeToString(vault)
        val payloadJson = ffiVaultDecrypt(vaultJsonStr, pin)
        return vaultJson.decodeFromString<VaultPayload>(payloadJson)
    }

    /**
     * True when the stored KDF metadata is weaker than the core's current
     * defaults (legacy PBKDF2 or Argon2id below VAULT_ARGON2_* thresholds).
     * Used by VaultSession at unlock to silently re-encrypt under stronger
     * params — every login gradually upgrades the user's vault.
     */
    fun vaultNeedsKdfUpgrade(vault: AccountVault): Boolean =
        ffiVaultNeedsKdfUpgrade(vaultJson.encodeToString(vault))

    /**
     * Encrypts the payload into a new AccountVault. The caller supplies the
     * salt (random per session, reused across that session's saves so the
     * Argon2id KDF only runs at unlock — not on every edit) plus account
     * metadata. Memory/iters default to the core's recommended values.
     */
    fun encryptPayload(
        payload: VaultPayload,
        pin: String,
        salt: ByteArray,
        accountId: String,
        isOwner: Boolean,
        familyMode: Boolean = false,
        memoryKb: Int = argon2DefaultMemoryKb(),
        iters: Int = argon2DefaultIters(),
    ): AccountVault {
        val payloadJson = vaultJson.encodeToString(payload)
        val accountJson = ffiVaultEncrypt(
            payloadJson = payloadJson,
            pin = pin,
            salt = salt,
            accountId = accountId,
            isOwner = isOwner,
            familyMode = familyMode,
            fcNonce = null,
            fcCipher = null,
            memoryKb = memoryKb.toUInt(),
            iters = iters.toUInt(),
        )
        return vaultJson.decodeFromString(accountJson)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /**
     * Seal arbitrary bytes under PIN — matches the iOS `EncryptedVaultExport`
     * envelope (Argon2id(PIN, 32-byte salt) → AES-256-GCM). Used by the
     * vault export path so the same `.sqvault` file can be read on iOS.
     */
    fun exportSeal(plaintext: ByteArray, pin: String): ExportEnvelopeBytes {
        val r = ffiExportSeal(plaintext, pin)
        return ExportEnvelopeBytes(salt = r.salt, nonce = r.nonce, ciphertext = r.ciphertext)
    }

    fun exportOpen(salt: ByteArray, nonce: ByteArray, ciphertext: ByteArray, pin: String): ByteArray =
        ffiExportOpen(salt, nonce, ciphertext, pin)
}

data class ExportEnvelopeBytes(val salt: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

sealed class PinVerifyResult {
    object Match : PinVerifyResult()
    object MatchNeedsRehash : PinVerifyResult()
    object NoMatch : PinVerifyResult()
}
