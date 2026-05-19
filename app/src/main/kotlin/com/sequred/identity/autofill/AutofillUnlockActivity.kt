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
 * Transparent overlay activity that handles the prompt-every-time flow:
 *
 *   1. If the user is currently unlocked AND a specific entry was selected,
 *      we just need the master password (PIN is already in the session).
 *      Otherwise:
 *   2. Biometric prompt → decrypts the wrapped PIN from Keystore.
 *      If biometric isn't enrolled / fails, fall back to typed PIN.
 *   3. Master password text field. If no specific entry was passed, show
 *      a filtered list of entries and let the user pick.
 *   4. Derive the site password via CoreBridge for the chosen entry, build
 *      a Dataset and setResult so the service can complete the fill.
 *   5. Activity finishes immediately. Master + PIN never leave the local
 *      stack.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutofillUnlockActivity : FragmentActivity() {

    private lateinit var app: SeQuredApp
    private var hintEntryId: VaultUuid? = null
    private var usernameAfId: AutofillId? = null
    private var passwordAfId: AutofillId? = null
    private var hintSite: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE so a passer-by can't screenshot the master entry.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        app = applicationContext as SeQuredApp
        hintEntryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }
            ?.let { VaultUuid(UUID.fromString(it)) }
        usernameAfId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
        passwordAfId = intent.getParcelableExtra(EXTRA_PASSWORD_ID, AutofillId::class.java)
        hintSite = intent.getStringExtra(EXTRA_HINT_SITE)
        android.util.Log.i(TAG, "onCreate: hintEntry=${hintEntryId?.value} u=$usernameAfId p=$passwordAfId site=$hintSite")

        setContent { SeQuredTheme { UnlockSheet() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun UnlockSheet() {
        var step by remember { mutableStateOf<Step>(Step.Loading) }

        // Resolve initial step based on session state + biometric availability.
        LaunchedEffect(Unit) {
            val live = app.session.state.value
            step = when {
                live is VaultSession.State.Unlocked -> Step.NeedMaster(live.pin)
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
                                onAuthenticated = { pin -> step = Step.NeedMaster(pin) },
                                onError = { msg -> step = Step.Error(msg) },
                            )

                            Step.NeedPin -> PinCard(
                                onSubmit = { pin ->
                                    lifecycleScope.launch {
                                        val res = withContext(Dispatchers.Default) { app.pinStore.verifyPin(pin) }
                                        step = if (res is PinVerifyResult.NoMatch)
                                            Step.Error("Wrong PIN. Open the app to retry.")
                                        else Step.NeedMaster(pin)
                                    }
                                },
                                onCancel = ::cancelAndFinish,
                            )

                            is Step.NeedMaster -> MasterCard(
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
        // Auto-launch the system prompt once.
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
                SqPrimaryButton(
                    label = "Unlock",
                    onClick = { if (pin.isNotEmpty()) onSubmit(pin) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MasterCard(
        pin: String,
        onCancel: () -> Unit,
        onComplete: (VaultEntry, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var master by remember { mutableStateOf("") }
        var deriving by remember { mutableStateOf(false) }
        var inlineError by remember { mutableStateOf<String?>(null) }
        var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
        var selected by remember { mutableStateOf<VaultEntry?>(null) }

        // Resolve entries once we have the PIN. Use the live unlocked session
        // when available; otherwise decrypt the on-disk vault on a worker.
        LaunchedEffect(pin) {
            val live = app.session.state.value as? VaultSession.State.Unlocked
            val payload = live?.payload ?: runCatching {
                withContext(Dispatchers.IO) { app.vaultRepo.load(pin) }
            }.getOrNull()
            if (payload == null) {
                onError("Couldn't open vault — wrong PIN?")
                return@LaunchedEffect
            }
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
            hintEntryId?.let { id -> selected = entries.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id } }
        }

        val currentSelected = selected
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Master password", currentSelected?.site ?: hintSite)
            if (currentSelected == null) {
                Text("Pick the credential to fill:", color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall)
                EntryPickList(entries, onPick = { selected = it })
            } else {
                Text(
                    "${currentSelected.site} — ${currentSelected.username}",
                    color = Brand.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            val canSubmit = !deriving && master.isNotEmpty() && currentSelected != null
            OutlinedTextField(
                value = master,
                onValueChange = { master = it; inlineError = null },
                label = { Text("Master password", color = Brand.TextSecondary) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (canSubmit) {
                        deriving = true
                        derive(pin, master, currentSelected!!) { result ->
                            deriving = false
                            result.fold(
                                onSuccess = { derived -> onComplete(currentSelected, derived) },
                                onFailure = { e -> inlineError = e.message ?: "Derivation failed." },
                            )
                        }
                    }
                }),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            inlineError?.let { Text(it, color = Brand.Danger, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                SqPrimaryButton(
                    label = if (deriving) "Deriving…" else "Fill",
                    enabled = canSubmit,
                    onClick = {
                        deriving = true
                        derive(pin, master, currentSelected!!) { result ->
                            deriving = false
                            result.fold(
                                onSuccess = { derived -> onComplete(currentSelected, derived) },
                                onFailure = { e -> inlineError = e.message ?: "Derivation failed." },
                            )
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
                        Text(e.username, color = Brand.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        data class NeedMaster(val pin: String) : Step()
        data class Error(val message: String) : Step()
    }

    // ─── Result dispatch ─────────────────────────────────────────────────────

    private fun cancelAndFinish() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun completeAndFinish(entry: VaultEntry, derivedPassword: String) {
        val uId = usernameAfId
        val pId = passwordAfId
        android.util.Log.i(TAG, "completeAndFinish: entry=${entry.site} u=${uId} p=${pId} pwlen=${derivedPassword.length}")
        if (uId == null && pId == null) {
            android.util.Log.e(TAG, "Both AutofillIds null — extras failed to survive PendingIntent. Returning canceled.")
            cancelAndFinish()
            return
        }
        val captionView = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, entry.site)
        }
        val dataset = try {
            Dataset.Builder().apply {
                if (uId != null) setValue(uId, AutofillValue.forText(entry.username), captionView)
                if (pId != null) setValue(pId, AutofillValue.forText(derivedPassword), captionView)
            }.build()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Dataset.Builder.build() failed", t)
            cancelAndFinish()
            return
        }
        val data = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        }
        android.util.Log.i(TAG, "Returning RESULT_OK with dataset")
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    companion object {
        private const val TAG = "SqAutofillAct"
        private const val EXTRA_ENTRY_ID = "sq.autofill.entry_id"
        private const val EXTRA_USERNAME_ID = "sq.autofill.username_id"
        private const val EXTRA_PASSWORD_ID = "sq.autofill.password_id"
        private const val EXTRA_HINT_SITE = "sq.autofill.hint_site"

        fun intent(
            ctx: Context,
            entryId: UUID?,
            usernameId: AutofillId?,
            passwordId: AutofillId?,
            hintSite: String? = null,
        ): Intent = Intent(ctx, AutofillUnlockActivity::class.java).apply {
            entryId?.let { putExtra(EXTRA_ENTRY_ID, it.toString()) }
            usernameId?.let { putExtra(EXTRA_USERNAME_ID, it) }
            passwordId?.let { putExtra(EXTRA_PASSWORD_ID, it) }
            hintSite?.let { putExtra(EXTRA_HINT_SITE, it) }
            // Deliberately no FLAG_ACTIVITY_NEW_TASK — it forks the activity
            // out of the requesting task, which breaks setResult delivery
            // back through the autofill framework.
        }
    }
}
