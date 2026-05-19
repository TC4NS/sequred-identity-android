package co.sequred.identity.ui.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.sequred.identity.crypto.CoreBridge
import co.sequred.identity.data.VaultCategory
import co.sequred.identity.data.VaultEntry
import co.sequred.identity.data.VaultSession
import co.sequred.identity.data.VaultUuid
import co.sequred.identity.ui.qr.parseOtpAuth
import co.sequred.identity.ui.qr.rememberQrLauncher
import co.sequred.identity.ui.theme.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditScreen(
    session: VaultSession,
    entryId: VaultUuid?,
    onDone: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return
    val existing = entryId?.let { id -> unlocked.payload.entries.firstOrNull { it.id == id } }

    // rememberSaveable across the board so the form survives the activity
    // recreation that ZXing's CaptureActivity can trigger on memory-tight
    // devices — without it, scanned values land on a torn-down composition.
    var site by rememberSaveable { mutableStateOf(existing?.site ?: "") }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "") }
    var email by rememberSaveable { mutableStateOf(existing?.email ?: "") }
    var length by rememberSaveable { mutableStateOf((existing?.passwordLength ?: 20).toString()) }
    var useUpper by rememberSaveable { mutableStateOf(existing?.useUpper ?: true) }
    var useLower by rememberSaveable { mutableStateOf(existing?.useLower ?: true) }
    var useDigits by rememberSaveable { mutableStateOf(existing?.useDigits ?: true) }
    var useSymbols by rememberSaveable { mutableStateOf(existing?.useSymbols ?: true) }
    var isPassphrase by rememberSaveable { mutableStateOf(existing?.isPassphrase ?: false) }
    var wordCount by rememberSaveable { mutableStateOf((existing?.passphraseWordCount ?: 6).toString()) }
    var separator by rememberSaveable { mutableStateOf(existing?.passphraseSeparator ?: "-") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: VaultCategory.None) }
    // NOT rememberSaveable: secrets must never land in the Bundle that
    // SavedInstanceState may persist (ADB dumpsys, process snapshots, OEM
    // backups). On activity recreation these reset to blank — acceptable
    // trade-off since the alternative leaks the master and TOTP seed.
    var totpSecret by remember { mutableStateOf(existing?.totpSecret ?: "") }
    var master by remember { mutableStateOf("") }
    var savingError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New entry" else "Edit entry", color = Brand.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Brand.TextPrimary)
                    }
                },
                actions = {
                    TextButton(
                        enabled = site.isNotBlank() && (username.isNotBlank() || email.isNotBlank()),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Brand.Capri,
                            disabledContentColor = Brand.TextSecondary.copy(alpha = 0.4f),
                        ),
                        onClick = {
                            // Username is the derivation identifier. If the user only
                            // typed an email, promote it into the username slot so the
                            // derivation pipeline gets a non-empty identifier — and
                            // store the same value as email so the autofill can put
                            // it back in an email field on this site's login page.
                            val finalUsername = username.trim().ifBlank { email.trim() }
                            val finalEmail = email.trim().takeIf { it.isNotEmpty() }
                            val proposed = (existing ?: VaultEntry(site = site, username = finalUsername)).copy(
                                site = site.trim(),
                                username = finalUsername,
                                email = finalEmail,
                                passwordLength = length.toIntOrNull()?.coerceIn(4, 128) ?: 20,
                                useUpper = useUpper, useLower = useLower,
                                useDigits = useDigits, useSymbols = useSymbols,
                                isPassphrase = isPassphrase,
                                passphraseWordCount = wordCount.toIntOrNull()?.coerceIn(3, 16) ?: 6,
                                passphraseSeparator = separator.ifEmpty { "-" },
                                category = category,
                                totpSecret = totpSecret.trim().takeIf { it.isNotEmpty() },
                            )
                            val hash = computePasswordHash(
                                proposed = proposed,
                                existing = existing,
                                master = master,
                                pin = unlocked.pin,
                            )
                            val finalEntry = proposed.copy(passwordHash = hash)
                            // If derivation params changed but no master supplied, we
                            // cleared the stale hash above. Warn so the user knows
                            // the next reveal won't verify against the old derivation.
                            if (existing?.passwordHash != null && hash == null) {
                                savingError = "Derivation changed and you didn't supply your master — verification hash cleared. Reveal once with your master to re-store it."
                            }
                            session.upsertEntry(finalEntry)
                            onDone()
                        },
                    ) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandTextField(value = site, onValueChange = { site = it }, label = "Site (e.g. github.com)")
            BrandTextField(value = username, onValueChange = { username = it }, label = "Username (optional if email set)")
            BrandTextField(value = email, onValueChange = { email = it }, label = "Email (optional if username set)", keyboardType = KeyboardType.Email)
            Text(
                "At least one of username or email is required — both can be filled for sites that ask for either.",
                color = Brand.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface.copy(alpha = 0.85f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Master-password verification (optional)",
                        color = Brand.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Type your master to stamp a SHA3-256 hash of the derived password on this entry. Future reveals can then detect a wrong master without ever storing the master itself.",
                        color = Brand.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    BrandTextField(
                        value = master,
                        onValueChange = { master = it },
                        label = "Master password",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }

            ToggleRow(
                label = if (isPassphrase) "Passphrase mode" else "Password mode",
                checked = isPassphrase,
                onChange = { isPassphrase = it },
            )

            if (isPassphrase) {
                BrandTextField(value = wordCount, onValueChange = { wordCount = it.filter(Char::isDigit) },
                    label = "Word count", keyboardType = KeyboardType.Number)
                BrandTextField(value = separator, onValueChange = { separator = it.take(3) },
                    label = "Separator")
            } else {
                BrandTextField(value = length, onValueChange = { length = it.filter(Char::isDigit) },
                    label = "Length", keyboardType = KeyboardType.Number)
                CharsetToggles(
                    useUpper, useLower, useDigits, useSymbols,
                    onUpper = { useUpper = it }, onLower = { useLower = it },
                    onDigits = { useDigits = it }, onSymbols = { useSymbols = it },
                )
            }

            CategoryDropdown(selected = category, onSelect = { category = it })

            val ctx = androidx.compose.ui.platform.LocalContext.current
            val launchScan = rememberQrLauncher(prompt = "Scan a TOTP QR code") { result ->
                val contents = result.contents
                if (contents.isNullOrBlank()) {
                    android.widget.Toast.makeText(ctx, "Scan cancelled — no QR detected.", android.widget.Toast.LENGTH_SHORT).show()
                    return@rememberQrLauncher
                }
                val otp = parseOtpAuth(contents)
                if (otp == null) {
                    android.widget.Toast.makeText(ctx, "Not a TOTP QR (need otpauth://totp/…)", android.widget.Toast.LENGTH_LONG).show()
                    return@rememberQrLauncher
                }
                totpSecret = otp.secret
                android.widget.Toast.makeText(ctx, "Scanned TOTP secret.", android.widget.Toast.LENGTH_SHORT).show()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BrandTextField(
                        value = totpSecret, onValueChange = { totpSecret = it },
                        label = "TOTP secret (optional, base32)",
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { launchScan() },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Brand.Capri,
                        contentColor = Color.Black,
                    ),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR")
                }
            }

            savingError?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Mirrors iOS GeneratorView.autoSaveToVault logic for the verification hash:
 *
 *   • Master supplied  → derive with the new params and store SHA3-256(derived).
 *   • Master not supplied AND derivation params unchanged on an existing entry
 *     → preserve the previous hash so it still verifies.
 *   • Master not supplied AND new entry OR derivation changed
 *     → no hash (legacy mode). The detail screen will fill it in on first reveal.
 */
private fun computePasswordHash(
    proposed: VaultEntry,
    existing: VaultEntry?,
    master: String,
    pin: String,
): ByteArray? {
    if (master.isNotEmpty()) {
        val derived = if (proposed.isPassphrase) {
            CoreBridge.derivePassphrase(
                master = master, site = proposed.site, username = proposed.username, pin = pin,
                wordCount = proposed.passphraseWordCount,
                separator = proposed.passphraseSeparator,
                version = proposed.version,
            )
        } else {
            CoreBridge.derivePassword(
                master = master, site = proposed.site, username = proposed.username, pin = pin,
                length = proposed.passwordLength,
                useUpper = proposed.useUpper, useLower = proposed.useLower,
                useDigits = proposed.useDigits, useSymbols = proposed.useSymbols,
                version = proposed.version,
            )
        }
        return CoreBridge.fingerprint(derived)
    }
    return if (existing != null && derivationParamsEqual(existing, proposed)) existing.passwordHash else null
}

private fun derivationParamsEqual(a: VaultEntry, b: VaultEntry): Boolean =
    a.site.equals(b.site, ignoreCase = true) &&
    a.username.equals(b.username, ignoreCase = true) &&
    a.version == b.version &&
    a.isPassphrase == b.isPassphrase &&
    (if (a.isPassphrase) {
        a.passphraseWordCount == b.passphraseWordCount &&
        a.passphraseSeparator == b.passphraseSeparator
    } else {
        a.passwordLength == b.passwordLength &&
        a.useUpper == b.useUpper && a.useLower == b.useLower &&
        a.useDigits == b.useDigits && a.useSymbols == b.useSymbols
    })

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Brand.TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Brand.Capri,
                checkedThumbColor = Brand.Background,
                uncheckedTrackColor = Brand.Panel,
                uncheckedThumbColor = Brand.TextSecondary,
                uncheckedBorderColor = Brand.Border,
            ),
        )
    }
}

@Composable
private fun CharsetToggles(
    upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean,
    onUpper: (Boolean) -> Unit, onLower: (Boolean) -> Unit,
    onDigits: (Boolean) -> Unit, onSymbols: (Boolean) -> Unit,
) {
    val cbColors = CheckboxDefaults.colors(
        checkedColor = Brand.Capri,
        uncheckedColor = Brand.TextSecondary,
        checkmarkColor = Brand.Background,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = upper, onCheckedChange = onUpper, colors = cbColors); Text("A-Z", color = Brand.TextPrimary)
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = lower, onCheckedChange = onLower, colors = cbColors); Text("a-z", color = Brand.TextPrimary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = digits, onCheckedChange = onDigits, colors = cbColors); Text("0-9", color = Brand.TextPrimary)
        Spacer(Modifier.width(12.dp))
        Checkbox(checked = symbols, onCheckedChange = onSymbols, colors = cbColors); Text("!@#…", color = Brand.TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: VaultCategory, onSelect: (VaultCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            readOnly = true,
            value = selected.label,
            onValueChange = {},
            label = { Text("Category", color = Brand.TextSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Brand.InputBg,
                unfocusedContainerColor = Brand.InputBg,
                focusedBorderColor = Brand.Capri,
                unfocusedBorderColor = Brand.Border,
                focusedTextColor = Brand.TextPrimary,
                unfocusedTextColor = Brand.TextPrimary,
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = Brand.Surface,
        ) {
            VaultCategory.values().forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label, color = Brand.TextPrimary) },
                    onClick = { onSelect(c); expanded = false },
                )
            }
        }
    }
}
