package com.sequred.identity

import android.app.Application
import com.sequred.identity.data.BiometricStore
import com.sequred.identity.data.PinStore
import com.sequred.identity.data.VaultRepository
import com.sequred.identity.data.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SeQuredApp : Application() {
    lateinit var pinStore: PinStore
        private set
    lateinit var vaultRepo: VaultRepository
        private set
    lateinit var biometric: BiometricStore
        private set

    /**
     * Application-lifetime scope for persistence work. Disk writes are launched
     * here (not on viewModelScope) so they survive Activity recreation and the
     * user backgrounding the app mid-save — losing a freshly-added credential
     * to a race against `onStop()` is unacceptable for a password manager.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: VaultSession by lazy { VaultSession(vaultRepo, pinStore, appScope) }

    override fun onCreate() {
        super.onCreate()
        pinStore = PinStore(this)
        vaultRepo = VaultRepository(this)
        biometric = BiometricStore(this)
        // So resetEverything can wipe the wrapped PIN too — otherwise a reset
        // leaves a stale biometric blob pointing at a deleted PIN hash.
        session.biometricStore = biometric
    }
}
