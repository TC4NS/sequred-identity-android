package co.sequred.identity.data

import android.content.Context
import co.sequred.identity.crypto.CoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * On-disk persistence for the encrypted vault. Mirrors the iOS app's
 * "multi-account file" layout so the same encrypted blob can later round-trip
 * between platforms. For phase 1 we only ever read/write a single account
 * slot (`accountId = DEFAULT_ACCOUNT_ID`); multi-account is a future feature.
 */
class VaultRepository(context: Context) {

    private val vaultFile: File = File(context.filesDir, VAULT_FILENAME)

    fun exists(): Boolean = vaultFile.exists()

    suspend fun load(pin: String): VaultPayload = loadWithMeta(pin).first

    /** Like load() but also returns whether the on-disk vault uses weaker
     *  KDF params than the current defaults, so the session can trigger a
     *  silent re-encrypt under the stronger params. */
    suspend fun loadWithMeta(pin: String): Pair<VaultPayload, Boolean> = withContext(Dispatchers.IO) {
        require(vaultFile.exists()) { "vault file does not exist — call save() first" }
        val file = vaultJson.decodeFromString<MultiAccountVaultFile>(vaultFile.readText())
        val mine = file.accounts.firstOrNull { it.accountId == DEFAULT_ACCOUNT_ID }
            ?: error("no slot for default account in vault file")
        val needsUpgrade = CoreBridge.vaultNeedsKdfUpgrade(mine)
        CoreBridge.decryptPayload(mine, pin) to needsUpgrade
    }

    /**
     * Encrypts and writes the payload. The session-stable salt + Argon2 params
     * come from the caller so we can avoid running the KDF on every save when
     * the same PIN is reused (which it always is during a session).
     */
    suspend fun save(
        payload: VaultPayload,
        pin: String,
        salt: ByteArray,
        memoryKb: Int = CoreBridge.argon2DefaultMemoryKb(),
        iters: Int = CoreBridge.argon2DefaultIters(),
    ) = withContext(Dispatchers.IO) {
        val account = CoreBridge.encryptPayload(
            payload = payload,
            pin = pin,
            salt = salt,
            accountId = DEFAULT_ACCOUNT_ID,
            isOwner = true,
            familyMode = false,
            memoryKb = memoryKb,
            iters = iters,
        )
        val file = MultiAccountVaultFile(
            version = 4,
            kdf = "argon2id",
            kdfIterations = iters,
            accounts = listOf(account),
        )
        // Atomic write — never leave a half-written vault on disk.
        // fsync the tmp file before rename and fsync the parent dir after,
        // otherwise a crash between write and rename can leave a zero-byte
        // file — catastrophic for a password manager.
        val tmp = File(vaultFile.parentFile, "$VAULT_FILENAME.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(vaultJson.encodeToString(file).toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(vaultFile)) error("failed to atomically replace vault file")
        // fsync the directory entry so the rename itself is durable.
        runCatching {
            RandomAccessFile(vaultFile.parentFile, "r").use { it.fd.sync() }
        }
    }

    /** Returns a fresh default-account ID, used at first-launch setup. */
    fun newAccountId(): String = UUID.randomUUID().toString()

    /** Permanently destroys the on-disk vault. Used by the reset flow. */
    fun reset() {
        if (vaultFile.exists()) vaultFile.delete()
    }

    companion object {
        private const val VAULT_FILENAME = "vault.enc"
        // Stable single-account ID for phase 1. When we add multi-account this
        // gets replaced by per-account UUIDs and a picker on the unlock screen.
        const val DEFAULT_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001"
    }
}
