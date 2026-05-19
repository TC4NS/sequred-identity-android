package co.sequred.identity.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import co.sequred.identity.data.VaultSession
import co.sequred.identity.ui.theme.Brand
import co.sequred.identity.ui.theme.BrandType
import co.sequred.identity.ui.theme.SeQuredHeader
import co.sequred.identity.ui.theme.SqPrimaryButton

@Composable
fun PinSetupScreen(session: VaultSession) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            SeQuredHeader(tagline = "Your passwords don't exist until you need them.")

            Spacer(Modifier.height(16.dp))

            Text("Set up", style = MaterialTheme.typography.headlineSmall, color = Brand.TextPrimary)
            Text(
                "Choose a PIN. You'll enter it each time you open the app. Your master password is never stored — you'll type it in only when you reveal a credential.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextSecondary,
            )

            BrandPinField(
                value = pin, label = "PIN",
                onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LEN) },
            )
            BrandPinField(
                value = confirm, label = "Confirm PIN",
                onValueChange = { confirm = it.filter(Char::isDigit).take(MAX_PIN_LEN) },
            )

            error?.let {
                Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall)
            }

            SqPrimaryButton(
                modifier = Modifier.fillMaxWidth(),
                label = "Continue",
                enabled = pin.length >= MIN_PIN_LEN && pin == confirm,
                onClick = {
                    when {
                        pin.length < MIN_PIN_LEN -> error = "PIN must be at least $MIN_PIN_LEN digits."
                        pin != confirm -> error = "PINs do not match."
                        else -> session.setupNewVault(pin)
                    }
                },
            )
        }
    }
}

@Composable
private fun BrandPinField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = BrandType.sectionLabel().copy(color = Brand.TextSecondary)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Brand.InputBg,
            unfocusedContainerColor = Brand.InputBg,
            focusedBorderColor = Brand.Capri,
            unfocusedBorderColor = Brand.Border,
            cursorColor = Brand.Capri,
            focusedTextColor = Brand.TextPrimary,
            unfocusedTextColor = Brand.TextPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// 2026 audit: a 4-digit PIN is brute-forceable in seconds even at 64 MiB / 3
// iters Argon2id. Six digits gives 10⁶ candidates — still small but a
// meaningful jump (~minutes on a workstation rather than seconds), and the
// PIN store throttling ladder caps online guesses cleanly.
private const val MIN_PIN_LEN = 6
private const val MAX_PIN_LEN = 32
