package com.sequred.identity.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sequred.identity.SeQuredApp
import com.sequred.identity.crypto.CoreBridge
import com.sequred.identity.crypto.PinVerifyResult
import com.sequred.identity.data.AppleDate
import com.sequred.identity.data.VaultEntry
import com.sequred.identity.data.VaultSession
import com.sequred.identity.data.VaultUuid
import com.sequred.identity.ui.biometric.BiometricOutcome
import com.sequred.identity.ui.biometric.canUseBiometric
import com.sequred.identity.ui.biometric.promptBiometric
import com.sequred.identity.ui.theme.Brand
import com.sequred.identity.ui.theme.SeQuredTheme
import com.sequred.identity.ui.theme.SqPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Transparent overlay activity that handles the prompt-every-time flow.
 *
 * Modes:
 *   - FillExisting: pick an existing entry (or use the supplied one), derive
 *     password, fill the form.
 *   - CreateNew: collect site/username/email/master, derive a fresh password,
 *     persist the new entry, then fill the form.
 *
 * Filling behaviour: the Dataset returned to the system carries values for
 * every requested field — username field gets entry.username, email field
 * gets entry.email (falling back to entry.username if no email), and every
 * password field (including confirm-password) gets the same derived value.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutofillUnlockActivity : FragmentActivity() {

    enum class Mode { FillExisting, CreateNew }

    private lateinit var app: SeQuredApp
    private lateinit var mode: Mode
    private var hintEntryId: VaultUuid? = null
    private var usernameAfId: AutofillId? = null
    private var emailAfId: AutofillId? = null
    private var passwordAfIds: List<AutofillId> = emptyList()
    private var hintSite: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        app = applicationContext as SeQuredApp
        mode = Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Mode.FillExisting.name)
        hintEntryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }
            ?.let { VaultUuid(UUID.fromString(it)) }
        usernameAfId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
        emailAfId = intent.getParcelableExtra(EXTRA_EMAIL_ID, AutofillId::class.java)
        passwordAfIds = intent.getParcelableArrayListExtra(EXTRA_PASSWORD_IDS, AutofillId::class.java) ?: emptyList()
        hintSite = intent.getStringExtra(EXTRA_HINT_SITE)
        android.util.Log.i(
            TAG,
            "onCreate: mode=$mode hintEntry=${hintEntryId?.value} u=$usernameAfId e=$emailAfId pws=${passwordAfIds.size} site=$hintSite",
        )

        setContent { SeQuredTheme { UnlockSheet() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun UnlockSheet() {
        var step by remember { mutableStateOf<Step>(Step.Loading) }

        LaunchedEffect(Unit) {
            val live = app.session.state.value
            step = when {
                live is VaultSession.State.Unlocked -> nextStepAfterAuth(live.pin)
                app.biometric.isEnrolled() && canUseBiometric(this@AutofillUnlockActivity) -> Step.AwaitingBiometric
                else -> Step.NeedPin
            }
        }

        Box(
            Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Brand.Surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        fadeIn(androidx.compose.animation.core.tween(150)) togetherWith
                            fadeOut(androidx.compose.animation.core.tween(120))
                    },
                    label = "autofill-step",
                ) { s ->
                    Column(
                        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (s) {
                            Step.Loading -> CircularProgressIndicator(color = Brand.Capri)

                            Step.AwaitingBiometric -> BiometricCard(
                                onUsePin = { step = Step.NeedPin },
                                onCancel = ::cancelAndFinish,
                                onAuthenticated = { pin -> step = nextStepAfterAuth(pin) },
                                onError = { msg -> step = Step.Error(msg) },
                            )

                            Step.NeedPin -> PinCard(
                                onSubmit = { pin ->
                                    lifecycleScope.launch {
                                        val res = withContext(Dispatchers.Default) { app.pinStore.verifyPin(pin) }
                                        step = if (res is PinVerifyResult.NoMatch)
                                            Step.Error("Wrong PIN. Open the app to retry.")
                                        else nextStepAfterAuth(pin)
                                    }
                                },
                                onCancel = ::cancelAndFinish,
                            )

                            is Step.PickEntry -> EntryPickerCard(
                                pin = s.pin,
                                onPick = { entry -> step = Step.EnterMasterFill(s.pin, entry) },
                                onCreateNew = { step = Step.CreateNew(s.pin) },
                                onCancel = ::cancelAndFinish,
                                onError = { msg -> step = Step.Error(msg) },
                            )

                            is Step.EnterMasterFill -> MasterFillCard(
                                pin = s.pin,
                                entry = s.entry,
                                onCancel = ::cancelAndFinish,
                                onComplete = { entry, derived -> completeAndFinish(entry, derived) },
                                onError = { msg -> step = Step.Error(msg) },
                            )

                            is Step.CreateNew -> CreateNewCard(
                                pin = s.pin,
                                onCancel = ::cancelAndFinish,
                                onComplete = { entry, derived -> completeAndFinish(entry, derived) },
                                onError = { msg -> step = Step.Error(msg) },
                            )

                            is Step.Error -> ErrorCard(s.message, onDismiss = ::cancelAndFinish)
                        }
                    }
                }
            }
        }
    }

    // ─── Cards ───────────────────────────────────────────────────────────────

    @Composable
    private fun BiometricCard(
        onUsePin: () -> Unit,
        onCancel: () -> Unit,
        onAuthenticated: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        LaunchedEffect(Unit) {
            val cipher = runCatching { app.biometric.cipherForDecrypt() }.getOrNull()
            if (cipher == null) {
                onUsePin(); return@LaunchedEffect
            }
            promptBiometric(
                this@AutofillUnlockActivity,
                cipher,
                title = "Unlock SeQured Identity",
                subtitle = "Authenticate to fill ${hintSite ?: "this login"}",
            ) { outcome ->
                when (outcome) {
                    is BiometricOutcome.Success -> {
                        val pin = runCatching { app.biometric.decryptPin(outcome.cipher) }.getOrNull()
                        if (pin == null) onError("Biometric key invalidated — open the app and re-enrol.")
                        else onAuthenticated(pin)
                    }
                    BiometricOutcome.Cancelled -> onCancel()
                    is BiometricOutcome.Failure -> onUsePin()
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Unlock SeQured", hintSite)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Brand.Capri)
                Spacer(Modifier.width(8.dp))
                Text("Authenticate to fill", color = Brand.TextSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUsePin, modifier = Modifier.weight(1f)) { Text("Use PIN") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PinCard(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Enter PIN", hintSite)
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text("PIN", color = Brand.TextSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty()) onSubmit(pin) }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(label = "Unlock", onClick = { if (pin.isNotEmpty()) onSubmit(pin) }, modifier = Modifier.weight(1f))
            }
        }
    }

    /** Decide which step comes next once we have a verified PIN. */
    private fun nextStepAfterAuth(pin: String): Step = when (mode) {
        Mode.CreateNew -> Step.CreateNew(pin)
        Mode.FillExisting -> if (hintEntryId != null) {
            // Specific entry already chosen by the service. We'll resolve it
            // inside MasterFillCard from the live payload. Wrap in a synthetic
            // entry first; MasterFillCard re-resolves and shows a friendly
            // error if the id doesn't actually exist.
            Step.EnterMasterFill(pin, placeholderEntry())
        } else {
            Step.PickEntry(pin)
        }
    }

    private fun placeholderEntry(): VaultEntry =
        VaultEntry(id = hintEntryId ?: VaultUuid(UUID.randomUUID()), site = hintSite.orEmpty(), username = "")

    /** Picker step: list of matching entries + a Create-new button at the
     *  bottom. Master password is collected on the next step, not here. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EntryPickerCard(
        pin: String,
        onPick: (VaultEntry) -> Unit,
        onCreateNew: () -> Unit,
        onCancel: () -> Unit,
        onError: (String) -> Unit,
    ) {
        var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
        LaunchedEffect(pin) {
            val live = app.session.state.value as? VaultSession.State.Unlocked
            val payload = live?.payload ?: runCatching {
                withContext(Dispatchers.IO) { app.vaultRepo.load(pin) }
            }.getOrNull()
            if (payload == null) { onError("Couldn't open vault — wrong PIN?"); return@LaunchedEffect }
            val all = payload.entries
            val matches = hintSite?.let { siteHint ->
                AutofillMatcher.matchEntries(
                    all,
                    listOf(AutofillMatcher.Candidate(
                        AutofillMatcher.stripWww(siteHint).lowercase(),
                        AutofillMatcher.Source.WebDomain,
                    )),
                )
            }.orEmpty()
            entries = if (matches.isNotEmpty()) matches else all.sortedBy { it.site.lowercase() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Pick a credential", hintSite)
            EntryPickList(entries, onPick = onPick)
            OutlinedButton(
                onClick = onCreateNew,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Capri),
            ) { Text("＋ Create new credential") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    /**
     * Master-password card for a specific already-picked entry. No picker
     * here — just the password input + Fill button. If the entry came from
     * a placeholder (hintEntryId path), we resolve it from the live payload.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MasterFillCard(
        pin: String,
        entry: VaultEntry,
        onCancel: () -> Unit,
        onComplete: (VaultEntry, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var master by remember { mutableStateOf("") }
        var deriving by remember { mutableStateOf(false) }
        var inlineError by remember { mutableStateOf<String?>(null) }
        var resolved by remember { mutableStateOf(entry.takeIf { it.username.isNotBlank() || !it.email.isNullOrBlank() }) }

        // If `entry` is a placeholder (came from hintEntryId), look up the
        // real one in the live payload.
        LaunchedEffect(entry.id) {
            if (resolved != null) return@LaunchedEffect
            val live = app.session.state.value as? VaultSession.State.Unlocked
            val payload = live?.payload ?: runCatching {
                withContext(Dispatchers.IO) { app.vaultRepo.load(pin) }
            }.getOrNull()
            val match = payload?.entries?.firstOrNull { it.id == entry.id }
            if (match == null) onError("That credential isn't in this vault anymore.")
            else resolved = match
        }
        val current = resolved ?: return // shows nothing until LaunchedEffect resolves

        val canSubmit = !deriving && master.isNotEmpty()
        fun submit() {
            if (!canSubmit) return
            deriving = true
            derive(pin, master, current) { result ->
                deriving = false
                result.fold(
                    onSuccess = { derived -> onComplete(current, derived) },
                    onFailure = { e -> inlineError = e.message ?: "Derivation failed." },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Master password", current.site)
            Text(
                "Filling: ${current.displayId}",
                color = Brand.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = master,
                onValueChange = { master = it; inlineError = null },
                label = { Text("Master password", color = Brand.TextSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            inlineError?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(
                    label = if (deriving) "Deriving…" else "Fill",
                    enabled = canSubmit,
                    onClick = ::submit,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    /**
     * Registration card. Captures site + username + email + master, derives a
     * fresh password with default params (length 20, all charsets), persists
     * the VaultEntry, then fills the form.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CreateNewCard(
        pin: String,
        onCancel: () -> Unit,
        onComplete: (VaultEntry, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var site by remember { mutableStateOf(hintSite.orEmpty()) }
        var username by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var master by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var inlineError by remember { mutableStateOf<String?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderRow("Create new credential", hintSite)
            Text(
                "We'll derive a fresh password using your master + this site + your username/email. " +
                    "Nothing about the password is stored — only the metadata you enter below.",
                color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = site, onValueChange = { site = it; inlineError = null },
                label = { Text("Site (e.g. example.com)", color = Brand.TextSecondary) },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it; inlineError = null },
                label = { Text("Username (optional if email set)", color = Brand.TextSecondary) },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it; inlineError = null },
                label = { Text("Email (optional if username set)", color = Brand.TextSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = master, onValueChange = { master = it; inlineError = null },
                label = { Text("Master password", color = Brand.TextSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            inlineError?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }

            val identifier = username.trim().ifBlank { email.trim() }
            val canSubmit = !working && site.isNotBlank() && master.isNotEmpty() && identifier.isNotBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(
                    label = if (working) "Saving…" else "Create & Fill",
                    enabled = canSubmit,
                    onClick = {
                        working = true
                        lifecycleScope.launch {
                            val newEntry = VaultEntry(
                                site = site.trim(),
                                username = identifier,
                                email = email.trim().takeIf { it.isNotEmpty() },
                                passwordLength = 20,
                                useUpper = true, useLower = true, useDigits = true, useSymbols = true,
                            )
                            val derived = runCatching {
                                withContext(Dispatchers.Default) {
                                    val pw = CoreBridge.derivePassword(
                                        master = master, site = newEntry.site, username = newEntry.username, pin = pin,
                                        length = newEntry.passwordLength,
                                        useUpper = newEntry.useUpper, useLower = newEntry.useLower,
                                        useDigits = newEntry.useDigits, useSymbols = newEntry.useSymbols,
                                        version = newEntry.version,
                                    )
                                    pw to CoreBridge.fingerprint(pw)
                                }
                            }.getOrElse { e ->
                                working = false
                                inlineError = e.message ?: "Derivation failed."
                                return@launch
                            }
                            val (pw, hash) = derived
                            val persisted = newEntry.copy(passwordHash = hash)
                            // Persist on the session. If session isn't live
                            // we open it transiently using the verified PIN.
                            val live = app.session.state.value as? VaultSession.State.Unlocked
                            if (live != null) {
                                app.session.upsertEntry(persisted)
                            } else {
                                // No live session: load → mutate → save manually so
                                // we don't have to spin up the full unlock flow.
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val payload = app.vaultRepo.load(pin)
                                        val merged = payload.copy(entries = payload.entries + persisted)
                                        val salt = CoreBridge.randomBytes(32)
                                        app.vaultRepo.save(merged, pin, salt)
                                    }
                                }.onFailure { e ->
                                    working = false
                                    inlineError = "Couldn't save: ${e.message}"
                                    return@launch
                                }
                            }
                            onComplete(persisted, pw)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    private fun derive(pin: String, master: String, entry: VaultEntry, cb: (Result<String>) -> Unit) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val derived = if (entry.isPassphrase) {
                        CoreBridge.derivePassphrase(
                            master = master, site = entry.site, username = entry.username, pin = pin,
                            wordCount = entry.passphraseWordCount,
                            separator = entry.passphraseSeparator,
                            version = entry.version,
                        )
                    } else {
                        CoreBridge.derivePassword(
                            master = master, site = entry.site, username = entry.username, pin = pin,
                            length = entry.passwordLength,
                            useUpper = entry.useUpper, useLower = entry.useLower,
                            useDigits = entry.useDigits, useSymbols = entry.useSymbols,
                            version = entry.version,
                        )
                    }
                    val stored = entry.passwordHash
                    if (stored != null && !CoreBridge.fingerprint(derived).contentEquals(stored)) {
                        error("Wrong master — derived password doesn't match this entry's fingerprint.")
                    }
                    derived
                }
            }
            cb(result)
        }
    }

    @Composable
    private fun EntryPickList(entries: List<VaultEntry>, onPick: (VaultEntry) -> Unit) {
        if (entries.isEmpty()) {
            Text("No matching entries.", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
            return
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(entries, key = { it.id.value }) { e ->
                Surface(
                    color = Brand.Panel.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(e) },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(e.site, color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.displayId, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun ErrorCard(message: String, onDismiss: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Can't autofill", hintSite)
            Text(message, color = Brand.TextSecondary)
            SqPrimaryButton(label = "OK", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun HeaderRow(title: String, site: String?) {
        Column {
            Text(title, color = Brand.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            site?.let { Text(it, color = Brand.Capri, style = MaterialTheme.typography.bodySmall) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun fieldColors() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Brand.InputBg,
        unfocusedContainerColor = Brand.InputBg,
        focusedBorderColor = Brand.Capri,
        unfocusedBorderColor = Brand.Border,
        cursorColor = Brand.Capri,
        focusedTextColor = Brand.TextPrimary,
        unfocusedTextColor = Brand.TextPrimary,
    )

    // ─── State machine ───────────────────────────────────────────────────────

    private sealed class Step {
        data object Loading : Step()
        data object AwaitingBiometric : Step()
        data object NeedPin : Step()
        /**
         * Pick an entry from a filtered list. Reached when Mode.FillExisting
         * but no specific hintEntryId was provided. The picker also offers
         * a "+ Create new" button to bail into the registration flow.
         */
        data class PickEntry(val pin: String) : Step()
        /** Master-pw entry for an already-chosen existing entry. */
        data class EnterMasterFill(val pin: String, val entry: VaultEntry) : Step()
        /** Registration card — creates + saves a new entry. */
        data class CreateNew(val pin: String) : Step()
        data class Error(val message: String) : Step()
    }

    // ─── Result dispatch ─────────────────────────────────────────────────────

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * Build a Dataset that fills:
     *   - username field with entry.username (fallback to entry.email if username blank)
     *   - email field with entry.email (fallback to entry.username if email null)
     *   - every password field with the same derived password
     */
    private fun completeAndFinish(entry: VaultEntry, derivedPassword: String) {
        val caption = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, entry.site)
        }
        val entryUsername = entry.username.takeIf { it.isNotBlank() }
        val entryEmail = entry.email?.takeIf { it.isNotBlank() }
        val hasBothFields = usernameAfId != null && emailAfId != null

        // Fill policy:
        //   - Form has BOTH fields (registration / dual-identifier login):
        //     fill each strictly with its matching value. Don't cross-fill —
        //     putting the username into the email box trips Rails-style
        //     "must be a valid email" validators and confuses the user.
        //   - Form has only ONE identifier field: fill it with the matching
        //     value if present, else fall back to the other identifier so
        //     the user gets *something* useful.
        val (uVal, eVal) = if (hasBothFields) {
            entryUsername to entryEmail
        } else {
            (entryUsername ?: entryEmail) to (entryEmail ?: entryUsername)
        }
        android.util.Log.i(
            TAG,
            "completeAndFinish: site=${entry.site} bothFields=$hasBothFields " +
                "entryU='${entryUsername?.take(3)}…' entryE='${entryEmail?.take(3)}…' " +
                "fillU='${uVal?.take(3)}…' fillE='${eVal?.take(3)}…' " +
                "fields: u=$usernameAfId e=$emailAfId pws=${passwordAfIds.size}",
        )
        val dataset = Dataset.Builder().apply {
            val uId = usernameAfId
            val eId = emailAfId
            if (uId != null && !uVal.isNullOrBlank()) setValue(uId, AutofillValue.forText(uVal), caption)
            if (eId != null && !eVal.isNullOrBlank()) setValue(eId, AutofillValue.forText(eVal), caption)
            passwordAfIds.forEach { setValue(it, AutofillValue.forText(derivedPassword), caption) }
        }.build()
        val data = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    companion object {
        private const val TAG = "SqAutofillAct"
        private const val EXTRA_MODE = "sq.autofill.mode"
        private const val EXTRA_ENTRY_ID = "sq.autofill.entry_id"
        private const val EXTRA_USERNAME_ID = "sq.autofill.username_id"
        private const val EXTRA_EMAIL_ID = "sq.autofill.email_id"
        private const val EXTRA_PASSWORD_IDS = "sq.autofill.password_ids"
        private const val EXTRA_HINT_SITE = "sq.autofill.hint_site"

        /**
         * Build the launch intent the autofill service wraps in a PendingIntent.
         * Carries the AutofillIds we need for the post-auth fill plus the
         * site / entry / mode hints.
         */
        fun intent(
            packageContext: Context,
            mode: Mode,
            entryId: UUID?,
            ctx: AutofillContext,
            hintSite: String? = null,
        ): Intent = Intent(packageContext, AutofillUnlockActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            entryId?.let { putExtra(EXTRA_ENTRY_ID, it.toString()) }
            ctx.usernameId?.let { putExtra(EXTRA_USERNAME_ID, it) }
            ctx.emailId?.let { putExtra(EXTRA_EMAIL_ID, it) }
            if (ctx.passwordIds.isNotEmpty()) {
                putParcelableArrayListExtra(EXTRA_PASSWORD_IDS, ArrayList(ctx.passwordIds))
            }
            hintSite?.let { putExtra(EXTRA_HINT_SITE, it) }
        }
    }
}
