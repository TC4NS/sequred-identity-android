package com.sequred.identity.ui.generator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sequred.identity.crypto.CoreBridge
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.LocalWindowSize
import com.sequred.identity.ui.theme.SeQuredHeader
import com.sequred.identity.ui.theme.WindowSize

/**
 * Standalone deterministic generator. Same 4-factor pipeline as the vault
 * entries, but the inputs aren't persisted anywhere — useful for quick
 * one-off lookups or for exploring how site/username changes affect output.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen() {
    val ctx = LocalContext.current
    var master by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("20") }
    var passphrase by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf("6") }
    var derived by remember { mutableStateOf("") }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SeQuredHeader(
                    modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp),
                    tagline = "Stateless · 4-factor derivation",
                )
                Column(
                    Modifier.padding(horizontal = gutter).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
            OutlinedTextField(
                value = master, onValueChange = { master = it },
                label = { Text("Master password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = site, onValueChange = { site = it },
                label = { Text("Site") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = passphrase, onCheckedChange = { passphrase = it })
                Spacer(Modifier.width(8.dp))
                Text(if (passphrase) "Passphrase" else "Password")
            }
            if (passphrase) {
                OutlinedTextField(
                    value = words, onValueChange = { words = it.filter(Char::isDigit) },
                    label = { Text("Word count") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = length, onValueChange = { length = it.filter(Char::isDigit) },
                    label = { Text("Length") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                enabled = master.isNotEmpty() && pin.isNotEmpty() && site.isNotEmpty() && username.isNotEmpty(),
                onClick = {
                    derived = if (passphrase) {
                        CoreBridge.derivePassphrase(
                            master = master, site = site, username = username, pin = pin,
                            wordCount = words.toIntOrNull()?.coerceIn(3, 16) ?: 6,
                            separator = "-", version = 1,
                        )
                    } else {
                        CoreBridge.derivePassword(
                            master = master, site = site, username = username, pin = pin,
                            length = length.toIntOrNull()?.coerceIn(4, 128) ?: 20,
                            useUpper = true, useLower = true, useDigits = true, useSymbols = true,
                            version = 1,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Derive") }

                if (derived.isNotEmpty()) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                derived,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { copy(ctx, derived) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                }
                } // inner form Column
            } // outer scrollable Column
        } // Box
    } // Scaffold lambda
}

private fun copy(ctx: Context, text: String) {
    com.sequred.identity.data.ClipboardGuard.copySensitive(ctx, "derived", text)
}
