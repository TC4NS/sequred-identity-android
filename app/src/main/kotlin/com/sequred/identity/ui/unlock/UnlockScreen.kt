package com.sequred.identity.ui.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sequred.identity.SeQuredApp
import com.sequred.identity.data.UnlockResult
import com.sequred.identity.data.VaultSession
import com.sequred.identity.ui.biometric.BiometricOutcome
import com.sequred.identity.ui.biometric.canUseBiometric
import com.sequred.identity.ui.biometric.promptBiometric
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.BrandType
import com.sequred.identity.ui.theme.SeQuredHeader
import com.sequred.identity.ui.theme.SqPrimaryButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UnlockScreen(session: VaultSession) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val app = ctx.applicationContext as SeQuredApp
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(cooldown) {
        while (cooldown > 0) { delay(1000); cooldown-- }
    }

    val biometricEnrolled = app.biometric.isEnrolled() && (activity?.let { canUseBiometric(it) } ?: false)
    var autoPromptShown by remember { mutableStateOf(false) }

    fun attemptUnlock(typedPin: String) {
        busy = true; error = null
        scope.launch {
            when (val r = session.unlock(typedPin)) {
                UnlockResult.Match -> {}
                is UnlockResult.Wrong -> {
                    error = "Wrong PIN."
                    if (r.cooldownSecs > 0) cooldown = r.cooldownSecs
                    pin = ""
                }
                is UnlockResult.LockedOut -> {
                    cooldown = r.cooldownSecs; pin = ""
                }
            }
            busy = false
        }
    }

    // Auto-launch biometric on first show, once per UnlockScreen instance.
    LaunchedEffect(biometricEnrolled) {
        if (biometricEnrolled && activity != null && !autoPromptShown) {
            autoPromptShown = true
            val cipher = runCatching { app.biometric.cipherForDecrypt() }.getOrNull() ?: return@LaunchedEffect
            promptBiometric(activity, cipher, "Unlock Identity", "Use your biometric to unlock") { outcome ->
                if (outcome is BiometricOutcome.Success) {
                    val decryptedPin = runCatching { app.biometric.decryptPin(outcome.cipher) }.getOrNull() ?: return@promptBiometric
                    attemptUnlock(decryptedPin)
                }
            }
        }
    }

    Surface(color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            SeQuredHeader()
            Spacer(Modifier.height(24.dp))

            Text("Enter your PIN", style = MaterialTheme.typography.headlineSmall, color = Brand.TextPrimary)

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(32); error = null },
                label = { Text("PIN", style = BrandType.sectionLabel().copy(color = Brand.TextSecondary)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (pin.isNotEmpty() && !busy && cooldown == 0) attemptUnlock(pin) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Brand.InputBg,
                    unfocusedContainerColor = Brand.InputBg,
                    focusedBorderColor = Brand.Capri,
                    unfocusedBorderColor = Brand.Border,
                    cursorColor = Brand.Capri,
                    focusedTextColor = Brand.TextPrimary,
                    unfocusedTextColor = Brand.TextPrimary,
                    errorBorderColor = Brand.Danger,
                ),
                isError = error != null,
                supportingText = error?.let { { Text(it, color = Brand.Danger) } },
                enabled = !busy && cooldown == 0,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )

            if (cooldown > 0) {
                Text(
                    "Too many wrong attempts. Try again in ${cooldown}s.",
                    color = Brand.Danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SqPrimaryButton(
                enabled = pin.isNotEmpty() && !busy && cooldown == 0,
                onClick = { attemptUnlock(pin) },
                label = if (busy) "Unlocking…" else "Unlock",
                modifier = Modifier.fillMaxWidth(),
            )

            if (biometricEnrolled && activity != null) {
                OutlinedButton(
                    onClick = {
                        val cipher = runCatching { app.biometric.cipherForDecrypt() }.getOrNull()
                            ?: return@OutlinedButton
                        promptBiometric(activity, cipher, "Unlock Identity", null) { outcome ->
                            if (outcome is BiometricOutcome.Success) {
                                val decryptedPin = runCatching { app.biometric.decryptPin(outcome.cipher) }.getOrNull()
                                    ?: return@promptBiometric
                                attemptUnlock(decryptedPin)
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Capri),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use biometric")
                }
            }
        }
    }
}
