package com.sequred.identity.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.sequred.identity.R
import com.sequred.identity.SeQuredApp
import com.sequred.identity.data.VaultEntry

/**
 * Android Autofill Framework entry point. Bound by the system when SeQured
 * Identity is the user's selected autofill service.
 *
 * Flow (prompt-every-time):
 *
 *   1. System calls [onFillRequest] with an AssistStructure for the
 *      requesting app's window.
 *   2. We parse it (see [AutofillFieldFinder]) to locate a username +
 *      password field pair and the requesting package name / web domain.
 *   3. We look up matching VaultEntries by (package, domain) and surface
 *      one Dataset per match. Each Dataset is a placeholder whose actual
 *      values are provided by [AutofillUnlockActivity] when the user
 *      taps it — that activity does the biometric + master prompt and
 *      derives the password before returning.
 *
 * Master password and PIN are never cached; every fill requires a fresh
 * authentication. That's the explicit security model picked by the user;
 * the iOS-style session cache lives elsewhere if we ever decide to add it.
 */
@RequiresApi(Build.VERSION_CODES.O)
class SeQuredAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // Most recent context is the user-visible one (back history is older).
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)
        val ctx = AutofillFieldFinder.find(structure)
        if (!ctx.isUsable) return callback.onSuccess(null)

        val requestingPackage = structure.activityComponent?.packageName
            ?: applicationContext.packageName
        val candidates = AutofillMatcher.candidates(requestingPackage, ctx.webDomain)
        android.util.Log.i("SqAutofillSvc", "onFillRequest: pkg=$requestingPackage web=${ctx.webDomain} u=${ctx.usernameId} p=${ctx.passwordId} candidates=${candidates.size}")

        val app = applicationContext as SeQuredApp
        // Try to pull entries from the live session if the user is currently
        // unlocked in the main app. Otherwise we'll show a single "Unlock
        // SeQured to fill" entry that bounces through the unlock activity.
        val unlockedNow = (app.session.state.value as? com.sequred.identity.data.VaultSession.State.Unlocked)
        val candidateEntries: List<VaultEntry> =
            unlockedNow?.let { AutofillMatcher.matchEntries(it.payload.entries, candidates) } ?: emptyList()

        val responseBuilder = FillResponse.Builder()
        var addedAny = false

        if (candidateEntries.isNotEmpty()) {
            // We know what to fill — surface one Dataset per matched entry.
            // Each is gated by AutofillUnlockActivity (biometric + master).
            for (entry in candidateEntries) {
                val intent = AutofillUnlockActivity.intent(
                    applicationContext,
                    entryId = entry.id.value,
                    usernameId = ctx.usernameId,
                    passwordId = ctx.passwordId,
                )
                val pi = PendingIntent.getActivity(
                    applicationContext,
                    entry.id.value.hashCode(),
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                val auth = pi.intentSender
                val view = RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
                    setTextViewText(android.R.id.text1, "${entry.site} — Tap to unlock & fill")
                    setTextViewText(android.R.id.text2, entry.username)
                }
                val dataset = android.service.autofill.Dataset.Builder().apply {
                    setAuthentication(auth)
                    ctx.usernameId?.let { setValue(it, null, view) }
                    ctx.passwordId?.let { setValue(it, null, view) }
                }.build()
                responseBuilder.addDataset(dataset)
                addedAny = true
            }
        } else {
            // Unknown site (or session locked) — offer a single "Search vault"
            // entry that opens our unlock activity in browse-and-pick mode.
            val intent = AutofillUnlockActivity.intent(
                applicationContext,
                entryId = null,
                usernameId = ctx.usernameId,
                passwordId = ctx.passwordId,
                hintSite = candidates.firstOrNull()?.site,
            )
            val pi = PendingIntent.getActivity(
                applicationContext,
                requestingPackage.hashCode(),
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val view = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "SeQured Identity — Choose entry…")
            }
            val dataset = android.service.autofill.Dataset.Builder().apply {
                setAuthentication(pi.intentSender)
                ctx.usernameId?.let { setValue(it, null, view) }
                ctx.passwordId?.let { setValue(it, null, view) }
            }.build()
            responseBuilder.addDataset(dataset)
            addedAny = true
        }

        // Save-on-submit: declared so the system asks "Save with SeQured?"
        // after the user submits the form. We don't act on it yet (storing
        // a raw password defeats the derivation model), but declaring it
        // suppresses the "your autofill service can't save passwords"
        // warning some browsers show.
        val saveTargets = listOfNotNull(ctx.usernameId, ctx.passwordId).toTypedArray()
        if (saveTargets.isNotEmpty()) {
            responseBuilder.setSaveInfo(
                SaveInfo.Builder(
                    SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
                    saveTargets,
                ).build()
            )
        }

        callback.onSuccess(if (addedAny) responseBuilder.build() else null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // No-op for now. Capturing raw submitted passwords from other apps
        // doesn't fit the stateless-derivation model — every entry is meant
        // to be derived, not stored verbatim.  We tell the system OK so the
        // toast doesn't say "couldn't save".
        callback.onSuccess()
    }
}
