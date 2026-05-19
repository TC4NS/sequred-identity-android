package com.sequred.identity.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sequred.identity.data.ImportException
import com.sequred.identity.data.ImportSource
import com.sequred.identity.data.MergeReport
import com.sequred.identity.data.ParsedImport
import com.sequred.identity.data.VaultEntry
import com.sequred.identity.data.VaultImportParser
import com.sequred.identity.data.VaultSession
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.BrandType
import com.sequred.identity.ui.theme.LocalWindowSize
import com.sequred.identity.ui.theme.SeQuredHeader
import com.sequred.identity.ui.theme.SqPrimaryButton
import com.sequred.identity.ui.theme.WindowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stepper-style import/export flow. The screen renders exactly one step at a
 * time (Idle → Picked → NeedsPin → Ready → Merged / Exported / Error) so the
 * user only sees the controls relevant to their current decision. Each step
 * is a Card so the visual rhythm matches the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(session: VaultSession, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return
    val scope = rememberCoroutineScope()
    val windowSize = LocalWindowSize.current
    val gutter = when (windowSize) {
        WindowSize.Compact -> 16.dp; WindowSize.Medium -> 24.dp; WindowSize.Expanded -> 40.dp
    }
    val contentMaxWidth = when (windowSize) { WindowSize.Expanded -> 720.dp; else -> 4096.dp }

    var step by remember { mutableStateOf<Step>(Step.Idle) }

    // ─── File picker (Import) ────────────────────────────────────────────────
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        session.resumeAutoLock()
        if (uri == null) {
            // User cancelled — fall back to whichever step was live before.
            if (step is Step.PickingImport) step = Step.Idle
            return@rememberLauncherForActivityResult
        }
        val name = resolveDisplayName(ctx, uri)
        step = Step.Parsing(name)
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw ImportException("Couldn't read the selected file.")
                step = parseStep(bytes, name, pin = null)
            } catch (t: Throwable) {
                step = Step.Error(t.message ?: "Failed to read file.")
            }
        }
    }

    // ─── File picker (Export) ────────────────────────────────────────────────
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        session.resumeAutoLock()
        if (uri == null) { if (step is Step.PickingExport) step = Step.Idle; return@rememberLauncherForActivityResult }
        step = Step.Exporting
        scope.launch {
            try {
                val json = session.exportEncrypted()
                    ?: throw IllegalStateException("Session locked — re-unlock and try again.")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Couldn't open output file.")
                }
                step = Step.ExportDone(entryCount = unlocked.payload.entries.size, authCount = unlocked.payload.authEntries.size)
            } catch (t: Throwable) {
                step = Step.Error(t.message ?: "Export failed.")
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Import / Export", color = Brand.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Brand.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SeQuredHeader(
                    modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp),
                    tagline = "Move credentials in or out — encrypted with your PIN",
                )

                AnimatedContent(
                    targetState = step,
                    label = "import-export-step",
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.padding(horizontal = gutter).fillMaxWidth(),
                ) { s ->
                    when (s) {
                        is Step.Idle -> IdleCard(
                            entryCount = unlocked.payload.entries.size,
                            authCount = unlocked.payload.authEntries.size,
                            onExport = {
                                step = Step.PickingExport
                                session.suspendAutoLock()
                                saveExport.launch(defaultExportName())
                            },
                            onImport = {
                                step = Step.PickingImport
                                session.suspendAutoLock()
                                pickFile.launch("*/*")
                            },
                        )

                        is Step.PickingImport, is Step.PickingExport -> WaitingCard("Opening file picker…")
                        is Step.Parsing -> WaitingCard("Reading ${s.name ?: "file"}…")
                        is Step.Exporting -> WaitingCard("Encrypting and writing…")

                        is Step.NeedsPin -> NeedsPinCard(
                            filename = s.filename,
                            onDecrypt = { pin ->
                                step = Step.Decrypting(s.bytes, s.filename)
                                scope.launch {
                                    step = parseStep(s.bytes, s.filename, pin)
                                }
                            },
                            onCancel = { step = Step.Idle },
                        )
                        is Step.Decrypting -> WaitingCard("Decrypting ${s.filename ?: "vault"}…")

                        is Step.Ready -> PreviewCard(
                            parsed = s.parsed,
                            filename = s.filename,
                            onMerge = {
                                step = Step.Merging
                                scope.launch {
                                    val report = session.importMerge(s.parsed)
                                    step = Step.Merged(report, s.parsed.source)
                                }
                            },
                            onCancel = { step = Step.Idle },
                        )
                        is Step.Merging -> WaitingCard("Merging into vault…")

                        is Step.Merged -> DoneCard(
                            title = "Imported from ${humanise(s.source)}",
                            lines = listOf(
                                "${s.report.added} added",
                                "${s.report.updated} updated with new TOTP",
                                "${s.report.skipped} skipped (already present)",
                            ),
                            onClose = { step = Step.Idle },
                        )
                        is Step.ExportDone -> DoneCard(
                            title = "Vault exported",
                            lines = listOf(
                                "${s.entryCount} credentials · ${s.authCount} TOTP codes",
                                "Encrypted with Argon2id(PIN) + AES-256-GCM",
                                "Compatible with the iOS app",
                            ),
                            onClose = { step = Step.Idle },
                        )
                        is Step.Error -> ErrorCard(message = s.message, onDismiss = { step = Step.Idle })
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ─── State machine ──────────────────────────────────────────────────────────

private sealed class Step {
    data object Idle : Step()
    data object PickingImport : Step()
    data object PickingExport : Step()
    data class Parsing(val name: String?) : Step()
    data class NeedsPin(val bytes: ByteArray, val filename: String?) : Step()
    data class Decrypting(val bytes: ByteArray, val filename: String?) : Step()
    data class Ready(val parsed: ParsedImport, val filename: String?) : Step()
    data object Merging : Step()
    data class Merged(val report: MergeReport, val source: ImportSource) : Step()
    data object Exporting : Step()
    data class ExportDone(val entryCount: Int, val authCount: Int) : Step()
    data class Error(val message: String) : Step()
}

private fun parseStep(bytes: ByteArray, filename: String?, pin: String?): Step =
    try {
        val parsed = VaultImportParser.parse(bytes, filename, pin)
        Step.Ready(parsed, filename)
    } catch (e: ImportException) {
        if (pin == null && e.message?.contains("SeQured encrypted") == true) {
            Step.NeedsPin(bytes, filename)
        } else {
            Step.Error(e.message ?: "Couldn't read that file.")
        }
    } catch (t: Throwable) {
        Step.Error(t.message ?: "Couldn't read that file.")
    }

// ─── Step cards ─────────────────────────────────────────────────────────────

@Composable
private fun IdleCard(entryCount: Int, authCount: Int, onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigChoiceCard(
            icon = Icons.Filled.Upload,
            title = "Export vault",
            subtitle = "$entryCount credentials · $authCount TOTP codes — wrapped under your PIN.",
            cta = "Export .sqvault",
            onClick = onExport,
        )
        BigChoiceCard(
            icon = Icons.Filled.Download,
            title = "Import",
            subtitle = "SeQured .sqvault, Bitwarden JSON, or CSV from LastPass / 1Password / Chrome / KeePass. Merges by site + username; never overwrites your derivation params.",
            cta = "Choose a file",
            onClick = onImport,
        )
    }
}

@Composable
private fun BigChoiceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    cta: String,
    onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brand.Capri.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = Brand.Capri) }
                Spacer(Modifier.width(10.dp))
                Text(title, color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            SqPrimaryButton(modifier = Modifier.fillMaxWidth(), label = cta, onClick = onClick)
        }
    }
}

@Composable
private fun WaitingCard(label: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.5.dp, color = Brand.Capri, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, color = Brand.TextPrimary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedsPinCard(filename: String?, onDecrypt: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FileHeader(filename, "SeQured encrypted vault")
            Text(
                "Enter the PIN that was active when this vault was exported. " +
                    "It's also your current PIN if you exported on this device.",
                color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text("Export PIN", color = Brand.TextSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = brandFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(
                    modifier = Modifier.weight(1f),
                    label = "Decrypt",
                    onClick = { if (pin.isNotEmpty()) onDecrypt(pin) },
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(parsed: ParsedImport, filename: String?, onMerge: () -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FileHeader(filename, "Detected as ${humanise(parsed.source)}")
            StatRow(parsed)
            HorizontalDivider(color = Brand.Border)
            EntryPreviewList(parsed.entries)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(
                    modifier = Modifier.weight(1f),
                    label = "Merge ${parsed.entries.size} into vault",
                    onClick = onMerge,
                )
            }
        }
    }
}

@Composable
private fun StatRow(p: ParsedImport) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        StatPill("${p.entries.size}", "credentials")
        StatPill("${p.authEntries.size + p.entries.count { !it.totpSecret.isNullOrEmpty() }}", "TOTP")
        StatPill("${p.skipped}", "skipped")
    }
}

@Composable
private fun StatPill(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Brand.Capri, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, color = Brand.TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EntryPreviewList(entries: List<VaultEntry>) {
    if (entries.isEmpty()) {
        Text("No usable entries found.", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.take(5).forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("•", color = Brand.Capri, modifier = Modifier.padding(end = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.site, color = Brand.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.username, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!e.totpSecret.isNullOrEmpty()) {
                    Text("TOTP", color = Brand.Capri, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (entries.size > 5) {
            Text("…and ${entries.size - 5} more", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DoneCard(title: String, lines: List<String>, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Brand.Capri)
                Spacer(Modifier.width(8.dp))
                Text(title, color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            lines.forEach { Text(it, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
            SqPrimaryButton(modifier = Modifier.fillMaxWidth(), label = "Done", onClick = onClose)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Brand.Surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Brand.Danger)
                Spacer(Modifier.width(8.dp))
                Text("Couldn't import", color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(message, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            SqPrimaryButton(modifier = Modifier.fillMaxWidth(), label = "OK", onClick = onDismiss)
        }
    }
}

@Composable
private fun FileHeader(filename: String?, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = Brand.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(filename ?: "Selected file", color = Brand.TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Brand.Capri, style = BrandType.sectionLabel())
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brandFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Brand.InputBg,
    unfocusedContainerColor = Brand.InputBg,
    focusedBorderColor = Brand.Capri,
    unfocusedBorderColor = Brand.Border,
    cursorColor = Brand.Capri,
    focusedTextColor = Brand.TextPrimary,
    unfocusedTextColor = Brand.TextPrimary,
)

private fun humanise(s: ImportSource): String = when (s) {
    ImportSource.SeQured -> "SeQured vault"
    ImportSource.Bitwarden -> "Bitwarden JSON"
    ImportSource.OnePasswordCsv -> "1Password CSV"
    ImportSource.LastPassCsv -> "LastPass CSV"
    ImportSource.GenericCsv -> "Generic CSV"
    ImportSource.GenericJson -> "Generic JSON"
}

private fun defaultExportName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "sequred-vault-$stamp.sqvault.json"
}

/**
 * Read the human filename out of a SAF URI. `uri.lastPathSegment` returns the
 * provider-internal document ID (e.g. "25" for Downloads), not the file's
 * display name — querying OpenableColumns.DISPLAY_NAME is the supported way
 * to get the actual filename the user sees in their Files app.
 */
private fun resolveDisplayName(ctx: Context, uri: Uri): String? {
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
    }
    // Fall back to the last path segment only if it looks file-shaped.
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
}

// AnimatedContent uses tween from compose.animation.core but we imported a
// different one; alias here so the spec block stays single-line.
private fun tween(durationMs: Int) = androidx.compose.animation.core.tween<Float>(durationMs)
