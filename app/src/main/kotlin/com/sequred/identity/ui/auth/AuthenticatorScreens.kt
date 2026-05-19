package com.sequred.identity.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sequred.identity.data.AuthenticatorEntry
import com.sequred.identity.data.VaultSession
import com.sequred.identity.data.VaultUuid
import com.sequred.identity.totp.Totp
import com.sequred.identity.ui.qr.parseOtpAuth
import com.sequred.identity.ui.qr.rememberQrLauncher
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.LocalWindowSize
import com.sequred.identity.ui.theme.SeQuredHeader
import com.sequred.identity.ui.theme.WindowSize
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorListScreen(
    session: VaultSession,
    onAdd: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return
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
    val entries = unlocked.payload.authEntries

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = Brand.Capri,
                contentColor = Color.Black,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = contentMaxWidth).fillMaxSize()) {
                SeQuredHeader(
                    modifier = Modifier.padding(horizontal = gutter, vertical = 12.dp),
                    tagline = if (entries.isEmpty()) "Authenticator codes refresh every 30s."
                              else "${entries.size} ${if (entries.size == 1) "code" else "codes"} · refreshes every 30s",
                )
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Tap + to add a TOTP — paste a Base32 secret or scan a QR.",
                            color = Brand.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = gutter, vertical = 8.dp),
                    ) {
                        items(entries, key = { it.id.value }) { entry ->
                            AuthRow(entry = entry, onDelete = { session.deleteAuthenticator(entry.id) })
                            HorizontalDivider(color = Brand.Border)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthRow(entry: AuthenticatorEntry, onDelete: () -> Unit) {
    var code by remember { mutableStateOf("------") }
    var remaining by remember { mutableStateOf(30) }
    val ctx = LocalContext.current
    LaunchedEffect(entry.secret) {
        while (true) {
            code = runCatching { Totp.code(entry.secret) }.getOrDefault("------")
            remaining = Totp.secondsRemaining()
            delay(1000)
        }
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.issuer, style = MaterialTheme.typography.titleMedium, color = Brand.TextPrimary)
            Text(entry.account, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
            Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge, color = Brand.Capri)
        }
        Text("${remaining}s", color = Brand.TextSecondary)
        IconButton(onClick = { copy(ctx, code) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code", tint = Brand.TextPrimary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Brand.Danger)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorEditScreen(
    session: VaultSession,
    entryId: VaultUuid?,
    onDone: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val unlocked = state as? VaultSession.State.Unlocked ?: return
    val existing = entryId?.let { id -> unlocked.payload.authEntries.firstOrNull { it.id == id } }

    // rememberSaveable so the form survives the activity recreation that can
    // happen when ZXing's CaptureActivity comes back. Without this, the
    // scanned values land on a torn-down composition and never paint.
    var issuer by rememberSaveable { mutableStateOf(existing?.issuer ?: "") }
    var account by rememberSaveable { mutableStateOf(existing?.account ?: "") }
    // NOT rememberSaveable: TOTP seed is a long-lived secret. Lost on recreation
    // but the alternative leaks it via the Bundle.
    var secret by remember { mutableStateOf(existing?.secret ?: "") }
    val ctx = LocalContext.current

    val launchScan = rememberQrLauncher(prompt = "Scan an otpauth:// QR") { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            Toast.makeText(ctx, "Scan cancelled — no QR detected.", Toast.LENGTH_SHORT).show()
            return@rememberQrLauncher
        }
        val otp = parseOtpAuth(contents)
        if (otp == null) {
            Toast.makeText(ctx, "Not a TOTP QR (need otpauth://totp/…)", Toast.LENGTH_LONG).show()
            return@rememberQrLauncher
        }
        secret = otp.secret
        if (issuer.isBlank()) otp.issuer?.let { issuer = it }
        if (account.isBlank()) otp.account?.let { account = it }
        Toast.makeText(ctx, "Scanned — review and tap Save.", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New TOTP" else "Edit TOTP") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { launchScan() }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR")
                    }
                    TextButton(
                        enabled = issuer.isNotBlank() && account.isNotBlank() && secret.isNotBlank(),
                        onClick = {
                            val entry = (existing ?: AuthenticatorEntry(issuer = issuer, account = account, secret = secret)).copy(
                                issuer = issuer.trim(),
                                account = account.trim(),
                                secret = secret.trim().replace(" ", "").uppercase(),
                            )
                            session.upsertAuthenticator(entry)
                            onDone()
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = issuer, onValueChange = { issuer = it },
                label = { Text("Issuer (e.g. GitHub)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = account, onValueChange = { account = it },
                label = { Text("Account (e.g. you@example.com)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text("Base32 secret") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun copy(ctx: Context, text: String) {
    com.sequred.identity.data.ClipboardGuard.copySensitive(ctx, "totp", text)
}
