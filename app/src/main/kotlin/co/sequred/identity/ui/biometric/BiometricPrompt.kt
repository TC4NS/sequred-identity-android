package co.sequred.identity.ui.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

sealed class BiometricOutcome {
    data class Success(val cipher: Cipher) : BiometricOutcome()
    data class Failure(val reason: String) : BiometricOutcome()
    object Cancelled : BiometricOutcome()
}

fun canUseBiometric(activity: FragmentActivity): Boolean {
    val mgr = BiometricManager.from(activity)
    val status = mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    return status == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Launch the system biometric sheet using the supplied init'd cipher.
 *
 * Callbacks fire on the main thread — the previous worker-pool executor
 * caused the UI to update from the wrong thread and the prompt to silently
 * fail to advance the unlock flow.
 */
fun promptBiometric(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String?,
    onResult: (BiometricOutcome) -> Unit,
) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setNegativeButtonText("Use PIN instead")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onResult(BiometricOutcome.Success(c))
                else onResult(BiometricOutcome.Failure("no cipher returned"))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> onResult(BiometricOutcome.Cancelled)
                    else -> onResult(BiometricOutcome.Failure(errString.toString()))
                }
            }
            override fun onAuthenticationFailed() { /* user can retry */ }
        },
    )
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}
