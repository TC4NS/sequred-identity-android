package com.sequred.identity.data

import androidx.lifecycle.ViewModel
import com.sequred.identity.crypto.CoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the unlocked vault. Holds the user's PIN
 * in-memory (only while unlocked), the session-stable Argon2id salt, and the
 * decrypted entry list.
 *
 * Persistence note: saves run on an Application-lifetime scope so they
 * survive Activity recreation and a mid-save background. Without that, a
 * user could add a credential, swipe away the app, and lose the entry. A
 * Mutex serialises writes so concurrent mutations don't interleave on disk.
 */
class VaultSession(
    private val repo: VaultRepository,
    private val pinStore: PinStore,
    private val scope: CoroutineScope,
) : ViewModel() {

    sealed class State {
        object Locked : State()
        object NeedsSetup : State()
        data class Unlocked(
            val pin: String,
            val salt: ByteArray,
            val payload: VaultPayload,
        ) : State()
    }

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Number of in-flight saves; UI shows a discreet indicator when > 0. */
    private val _pendingSaves = MutableStateFlow(0)
    val pendingSaves: StateFlow<Int> = _pendingSaves.asStateFlow()

    /** Last user interaction timestamp (epoch ms). Drives inactivity lock. */
    @Volatile var lastInteractionAtMs: Long = System.currentTimeMillis()
        private set

    /**
     * Counter of in-flight "we intentionally launched another activity" holds.
     * While > 0, MainActivity.onStop skips the auto-lock so the user isn't
     * thrown back to the lock screen mid-QR-scan or mid-camera-permission.
     */
    @Volatile private var autoLockHolds: Int = 0
    fun suspendAutoLock() { autoLockHolds++ }
    fun resumeAutoLock() { autoLockHolds = (autoLockHolds - 1).coerceAtLeast(0); touch() }
    fun isAutoLockSuspended(): Boolean = autoLockHolds > 0

    /** Inactivity timeout — minutes of idle before auto-lock. 0 disables. */
    var inactivityTimeoutMinutes: Int = pinStore.inactivityTimeoutMinutes
        set(value) {
            field = value
            pinStore.inactivityTimeoutMinutes = value
        }

    private val saveMutex = Mutex()
    private var latestSaveJob: Job? = null

    private fun initialState(): State =
        if (repo.exists() && pinStore.isProvisioned()) State.Locked else State.NeedsSetup

    /** Resets the inactivity timer. Called from MainActivity on any user touch. */
    fun touch() { lastInteractionAtMs = System.currentTimeMillis() }

    /** True if the configured idle timeout has elapsed since the last touch. */
    fun isIdleExpired(): Boolean {
        val mins = inactivityTimeoutMinutes
        if (mins <= 0) return false
        return System.currentTimeMillis() - lastInteractionAtMs >= mins * 60_000L
    }

    fun setupNewVault(pin: String) {
        pinStore.setPin(pin)
        pinStore.clearThrottle()
        val salt = CoreBridge.randomBytes(SALT_BYTES)
        val empty = VaultPayload()
        scope.launch {
            saveMutex.withLock { repo.save(empty, pin, salt) }
            _state.value = State.Unlocked(pin, salt, empty)
            touch()
        }
    }

    /** Returns the unlock outcome — `Match`, `Wrong`, or `LockedOut(secsRemaining)`. */
    suspend fun unlock(pin: String): UnlockResult {
        val remaining = pinStore.secondsUntilUnlocked()
        if (remaining > 0) return UnlockResult.LockedOut(remaining)
        val verify = pinStore.verifyPin(pin)
        if (verify is com.sequred.identity.crypto.PinVerifyResult.NoMatch) {
            val wait = pinStore.recordFailure()
            return UnlockResult.Wrong(wait)
        }
        val (payload, _) = try { repo.loadWithMeta(pin) } catch (_: Throwable) {
            val wait = pinStore.recordFailure()
            return UnlockResult.Wrong(wait)
        }
        pinStore.clearThrottle()
        val salt = CoreBridge.randomBytes(SALT_BYTES)
        _state.value = State.Unlocked(pin, salt, payload)
        touch()
        // Re-encrypt with the fresh session salt so subsequent edits reuse it.
        // Because save() always writes the core's current defaults, this also
        // silently upgrades any vault that was written under weaker KDF params
        // (pre-2026 32 MiB / 2 iters, or the original PBKDF2-SHA3-256 path).
        scope.launch { saveMutex.withLock { repo.save(payload, pin, salt) } }
        return UnlockResult.Match
    }

    /**
     * Drops every reference to the decrypted vault, PIN, and session salt.
     * Note: Kotlin Strings are immutable and we cannot zero the underlying
     * char[] (it's owned by the String). The best we can do is null the
     * references and hint the GC, so a later heap dump only sees free space.
     * The PIN refactor to CharArray is a follow-up.
     */
    fun lock() {
        val prev = _state.value as? State.Unlocked
        _state.value = State.Locked
        if (prev != null) {
            // Overwrite the session salt buffer (this one we DO own).
            prev.salt.fill(0)
            // Suggest GC so the previous payload + PIN String become eligible
            // immediately. Not a guarantee — but it shrinks the leak window.
            System.gc()
        }
    }

    fun resetEverything() {
        // Order matters: clear biometric first so the wrapped (now-stale) PIN
        // can't decrypt to anything, then wipe disk state, then drop session.
        biometricStore?.clear()
        repo.reset()
        pinStore.reset()
        lock()
        _state.value = State.NeedsSetup
    }

    /** Set by SeQuredApp at construction so resetEverything can wipe biometric. */
    var biometricStore: BiometricStore? = null

    /** Blocks until all pending disk writes have flushed. Called from onStop(). */
    suspend fun awaitPendingSaves() {
        latestSaveJob?.join()
    }

    // ─── Mutators (auto-persist on appScope) ────────────────────────────────

    fun upsertEntry(entry: VaultEntry) {
        val unlocked = _state.value as? State.Unlocked ?: return
        val withEntry = if (unlocked.payload.entries.any { it.id == entry.id }) {
            unlocked.payload.copy(
                entries = unlocked.payload.entries.map {
                    if (it.id == entry.id) entry.copy(updatedAt = AppleDate.now()) else it
                }
            )
        } else {
            unlocked.payload.copy(entries = unlocked.payload.entries + entry)
        }
        // Mirror the credential's TOTP into the standalone Authenticator list
        // so a code added here also shows on the Auth tab. Match an existing
        // mirror by (issuer=site, account=username) so re-saving the same
        // credential updates the secret instead of duplicating the row.
        val withMirror = syncTotpMirror(withEntry, entry)
        persist(unlocked.copy(payload = withMirror))
    }

    private fun syncTotpMirror(payload: VaultPayload, entry: VaultEntry): VaultPayload {
        val secret = entry.totpSecret?.trim()?.replace(" ", "")?.uppercase()
        val issuer = entry.site.trim()
        val account = entry.username.trim()
        val existing = payload.authEntries.firstOrNull {
            it.issuer.equals(issuer, ignoreCase = true) &&
                it.account.equals(account, ignoreCase = true)
        }
        return when {
            secret.isNullOrEmpty() -> payload // nothing to mirror; leave Auth list alone
            existing == null -> payload.copy(
                authEntries = payload.authEntries + AuthenticatorEntry(
                    issuer = issuer, account = account, secret = secret,
                )
            )
            existing.secret == secret -> payload // already in sync
            else -> payload.copy(
                authEntries = payload.authEntries.map {
                    if (it.id == existing.id) it.copy(secret = secret) else it
                }
            )
        }
    }

    fun deleteEntry(id: VaultUuid) {
        val unlocked = _state.value as? State.Unlocked ?: return
        val updated = unlocked.payload.copy(entries = unlocked.payload.entries.filterNot { it.id == id })
        persist(unlocked.copy(payload = updated))
    }

    fun upsertAuthenticator(entry: AuthenticatorEntry) {
        val unlocked = _state.value as? State.Unlocked ?: return
        val updated = if (unlocked.payload.authEntries.any { it.id == entry.id }) {
            unlocked.payload.copy(authEntries = unlocked.payload.authEntries.map { if (it.id == entry.id) entry else it })
        } else {
            unlocked.payload.copy(authEntries = unlocked.payload.authEntries + entry)
        }
        persist(unlocked.copy(payload = updated))
    }

    fun deleteAuthenticator(id: VaultUuid) {
        val unlocked = _state.value as? State.Unlocked ?: return
        val updated = unlocked.payload.copy(authEntries = unlocked.payload.authEntries.filterNot { it.id == id })
        persist(unlocked.copy(payload = updated))
    }

    /** Merge imported entries into the live payload; dedupe by site+username. */
    fun importMerge(imported: ParsedImport): MergeReport {
        val unlocked = _state.value as? State.Unlocked
            ?: return MergeReport(added = 0, updated = 0, skipped = imported.entries.size)
        val (merged, report) = VaultMerger.merge(unlocked.payload, imported)
        persist(unlocked.copy(payload = merged))
        return report
    }

    /** Returns the encrypted `.sqvault` JSON. Pin is the current session PIN. */
    fun exportEncrypted(): String? {
        val unlocked = _state.value as? State.Unlocked ?: return null
        return VaultExporter.export(unlocked.payload, unlocked.pin)
    }

    private fun persist(newUnlocked: State.Unlocked) {
        _state.value = newUnlocked
        touch()
        _pendingSaves.value++
        latestSaveJob = scope.launch {
            try {
                saveMutex.withLock {
                    repo.save(newUnlocked.payload, newUnlocked.pin, newUnlocked.salt)
                }
            } finally {
                _pendingSaves.value = (_pendingSaves.value - 1).coerceAtLeast(0)
            }
        }
    }

    companion object {
        private const val SALT_BYTES = 32
    }
}

sealed class UnlockResult {
    object Match : UnlockResult()
    data class Wrong(val cooldownSecs: Int) : UnlockResult()
    data class LockedOut(val cooldownSecs: Int) : UnlockResult()
}
