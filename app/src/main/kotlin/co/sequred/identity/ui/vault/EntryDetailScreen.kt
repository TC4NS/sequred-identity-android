package co.sequred.identity.ui.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.sequred.identity.crypto.CoreBridge
import co.sequred.identity.data.VaultSession
import co.sequred.identity.data.VaultUuid
import co.sequred.identity.totp.Totp
import co.sequred.identity.ui.theme.Brand
import co.sequred.identity.ui.theme.BrandType
import co.sequred.identity.ui.theme.SiteLogo
import co.sequred.identity.ui.theme.SqPrimaryButton
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    session: VaultSession,
    entryId: VaultUuid,
    onBack: () -> Unit,
    onEdit: (VaultUuid) -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return
    val entry = unlocked.payload.entries.firstOrNull { it.id == entryId } ?: run {
        onBack(); return
    }

    var master by remember { mutableStateOf("") }
    var derived by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var wrongMaster by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val masterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { masterFocus.requestFocus() }

    fun runDerive() {
        if (master.isEmpty()) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val out = if (entry.isPassphrase) {
            CoreBridge.derivePassphrase(
                master = master, site = entry.site, username = entry.username,
                pin = unlocked.pin,
                wordCount = entry.passphraseWordCount,
                separator = entry.passphraseSeparator,
                version = entry.version,
            )
        } else {
            CoreBridge.derivePassword(
                master = master, site = entry.site, username = entry.username,
                pin = unlocked.pin,
                length = entry.passwordLength,
                useUpper = entry.useUpper, useLower = entry.useLower,
                useDigits = entry.useDigits, useSymbols = entry.useSymbols,
                version = entry.version,
            )
        }
        derived = out
        revealed = false
        val fp = CoreBridge.fingerprint(out)
        wrongMaster = entry.passwordHash != null && !fp.contentEquals(entry.passwordHash)
        if (entry.passwordHash == null) {
            session.upsertEntry(entry.copy(passwordHash = fp))
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SiteLogo(site = entry.site, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(entry.site, color = Brand.TextPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Brand.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(entry.id) }) {
                        Icon(Icons.Filled.Edit, "Edit", tint = Brand.TextPrimary)
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = Brand.Danger)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.username.isNotBlank()) InfoRow("USERNAME", entry.username)
                    entry.email?.takeIf { it.isNotBlank() }?.let { InfoRow("EMAIL", it) }
                    InfoRow(
                        if (entry.isPassphrase) "PASSPHRASE" else "PASSWORD",
                        if (entry.isPassphrase) "${entry.passphraseWordCount} words, '${entry.passphraseSeparator}'"
                        else "${entry.passwordLength} chars" +
                            listOfNotNull(
                                "A-Z".takeIf { entry.useUpper },
                                "a-z".takeIf { entry.useLower },
                                "0-9".takeIf { entry.useDigits },
                                "!@#".takeIf { entry.useSymbols },
                            ).joinToString(prefix = " · ", separator = " "),
                    )
                    if (entry.category.label != "All") {
                        InfoRow("CATEGORY", entry.category.label)
                    }
                }
            }

            OutlinedTextField(
                value = master,
                onValueChange = { master = it; derived = null; wrongMaster = false },
                label = { Text("Master password", color = Brand.TextSecondary) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { runDerive() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Brand.InputBg,
                    unfocusedContainerColor = Brand.InputBg,
                    focusedBorderColor = Brand.Capri,
                    unfocusedBorderColor = Brand.Border,
                    cursorColor = Brand.Capri,
                    focusedTextColor = Brand.TextPrimary,
                    unfocusedTextColor = Brand.TextPrimary,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(masterFocus),
            )

            SqPrimaryButton(
                enabled = master.isNotEmpty(),
                label = "Derive password",
                modifier = Modifier.fillMaxWidth(),
                onClick = { runDerive() },
            )

            derived?.let { pw ->
                if (wrongMaster) {
                    Surface(
                        color = Brand.Danger.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Wrong master password — this derived value won't match what you saved.",
                            color = Brand.Danger,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Brand.Panel)) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (revealed) pw else "•".repeat(pw.length.coerceAtMost(20)),
                            fontFamily = FontFamily.Monospace,
                            color = Brand.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) Icons.Filled.VisibilityOff
                                              else Icons.Filled.Visibility,
                                contentDescription = if (revealed) "Hide" else "Reveal",
                                tint = Brand.Capri,
                            )
                        }
                        IconButton(onClick = { copyToClipboard(ctx, pw) }) {
                            Icon(Icons.Filled.ContentCopy, "Copy", tint = Brand.Capri)
                        }
                    }
                }
            }

            entry.totpSecret?.let { secret -> TotpRow(secret = secret) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Brand.Surface,
            title = { Text("Delete entry?", color = Brand.TextPrimary) },
            text = {
                Text(
                    "This removes ${entry.site} from the vault. Derived passwords are unaffected — you can always re-create the entry.",
                    color = Brand.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false; session.deleteEntry(entry.id); onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.Danger),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDelete = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.TextSecondary),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = BrandType.sectionLabel(), color = Brand.TextSecondary)
        Text(value, color = Brand.TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = BrandType.sectionLabel().copy(color = Brand.TextSecondary)) },
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Brand.InputBg,
            unfocusedContainerColor = Brand.InputBg,
            focusedBorderColor = Brand.Capri,
            unfocusedBorderColor = Brand.Border,
            cursorColor = Brand.Capri,
            focusedTextColor = Brand.TextPrimary,
            unfocusedTextColor = Brand.TextPrimary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TotpRow(secret: String) {
    var code by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf(30) }
    LaunchedEffect(secret) {
        while (true) {
            code = runCatching { Totp.code(secret) }.getOrDefault("------")
            remaining = Totp.secondsRemaining()
            delay(1000)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("TOTP", style = BrandType.sectionLabel())
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.Capri,
                )
            }
            Text("${remaining}s", color = Brand.TextSecondary)
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    co.sequred.identity.data.ClipboardGuard.copySensitive(ctx, "password", text)
}
