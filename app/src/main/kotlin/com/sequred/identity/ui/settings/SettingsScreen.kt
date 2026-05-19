package com.sequred.identity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sequred.identity.SeQuredApp
import com.sequred.identity.crypto.CoreBridge
import com.sequred.identity.data.VaultSession
import com.sequred.identity.ui.biometric.BiometricOutcome
import com.sequred.identity.ui.biometric.canUseBiometric
import com.sequred.identity.ui.biometric.promptBiometric
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.BrandType
import com.sequred.identity.ui.theme.LocalWindowSize
import com.sequred.identity.ui.theme.SeQuredHeader
import com.sequred.identity.ui.theme.SqPrimaryButton
import com.sequred.identity.ui.theme.WindowSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(session: VaultSession, onOpenImportExport: () -> Unit = {}) {
    var confirmReset by remember { mutableStateOf(false) }
    var idleMinutes by remember { mutableStateOf(session.inactivityTimeoutMinutes) }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as SeQuredApp
    val activity = ctx as? FragmentActivity
    val hardwareReady = activity?.let { canUseBiometric(it) } == true
    var biometricOn by remember { mutableStateOf(app.biometric.isEnrolled()) }
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked

    val windowSize = LocalWindowSize.current
    val gutter = when (windowSize) {
        WindowSize.Compact -> 16.dp
        WindowSize.Medium -> 24.dp
        WindowSize.Expanded -> 40.dp
    }
    val contentMaxWidth = when (windowSize) {
        WindowSize.Expanded -> 720.dp
        else -> 4096.dp
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
      Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SeQuredHeader(
                modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp),
                tagline = "Identity · Settings",
            )
        Column(
            Modifier.padding(horizontal = gutter).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("Security")
            Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Auto-lock after",
                        color = Brand.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (idleMinutes == 0) "Never (only locks on background)"
                        else "$idleMinutes minute${if (idleMinutes == 1) "" else "s"} of inactivity",
                        color = Brand.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = idleMinutes.toFloat(),
                        onValueChange = { idleMinutes = it.toInt() },
                        onValueChangeFinished = { session.inactivityTimeoutMinutes = idleMinutes },
                        valueRange = 0f..30f,
                        steps = 29,
                        colors = SliderDefaults.colors(
                            thumbColor = Brand.Capri,
                            activeTrackColor = Brand.Capri,
                            inactiveTrackColor = Brand.Border,
                        ),
                    )
                }
            }

            // Biometric unlock toggle — only visible when device has enrolled bio
            // hardware. Requires the session PIN so we can wrap it with the
            // Keystore key; we always have it here since this screen is only
            // reachable from an unlocked state.
            if (hardwareReady && unlocked != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Biometric unlock", color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Use Face Unlock or fingerprint instead of the PIN. The PIN is encrypted by a Keystore key bound to your biometric.",
                                color = Brand.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = biometricOn,
                            onCheckedChange = { want ->
                                if (want) {
                                    // The keystore key requires user auth even
                                    // for encrypt, so the enrollment write
                                    // happens inside the prompt success.
                                    val cipher = runCatching { app.biometric.cipherForEnroll() }.getOrNull()
                                    if (cipher == null || activity == null) return@Switch
                                    promptBiometric(
                                        activity, cipher,
                                        title = "Enable biometric unlock",
                                        subtitle = "Authenticate to encrypt your PIN",
                                    ) { outcome ->
                                        if (outcome is BiometricOutcome.Success) {
                                            runCatching {
                                                app.biometric.completeEnrollment(outcome.cipher, unlocked.pin)
                                            }.onSuccess { biometricOn = true }
                                        }
                                    }
                                } else {
                                    app.biometric.clear()
                                    biometricOn = false
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Brand.Capri,
                                checkedThumbColor = Brand.Background,
                            ),
                        )
                    }
                }
            }

            SqPrimaryButton(
                modifier = Modifier.fillMaxWidth(),
                label = "Lock now",
                onClick = { session.lock() },
            )

            SectionLabel("Data")
            OutlinedButton(
                onClick = onOpenImportExport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Capri),
            ) { Text("Import / Export vault") }

            SectionLabel("About")
            Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Crypto core", color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "v${CoreBridge.version()} · Argon2id ${CoreBridge.argon2DefaultMemoryKb() / 1024} MB × ${CoreBridge.argon2DefaultIters()} iters",
                        color = Brand.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionLabel("Danger zone")
            OutlinedButton(
                onClick = { confirmReset = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Danger),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset app (delete vault + PIN)") }
        } // inner form Column
        } // outer scrollable Column
      } // Box
    } // Scaffold lambda

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Brand.Surface,
            title = { Text("Reset SeQured Identity?", color = Brand.TextPrimary) },
            text = {
                Text(
                    "This permanently deletes the encrypted vault and PIN on this device. " +
                        "Derived passwords are unaffected — they re-derive from your master + PIN + site + username on any device. This cannot be undone.",
                    color = Brand.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmReset = false; session.resetEverything() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.Danger),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmReset = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.TextSecondary),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = BrandType.sectionLabel(),
        color = Brand.TextSecondary,
        modifier = Modifier.padding(start = 4.dp),
    )
}
