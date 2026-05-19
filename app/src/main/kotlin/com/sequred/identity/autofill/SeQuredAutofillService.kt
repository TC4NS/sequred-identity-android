package com.sequred.identity.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.sequred.identity.SeQuredApp
import com.sequred.identity.data.VaultEntry
import com.sequred.identity.data.VaultSession

/**
 * Android Autofill Framework entry point. Bound by the system when SeQured
 * Identity is the user's selected autofill service.
 *
 * For each FillRequest:
 *   1. Parse the AssistStructure for username / email / one-or-more password
 *      fields, and detect registration vs login.
 *   2. Look up matching VaultEntries by package + WEB_DOMAIN.
 *   3. Surface a Dataset per match (gated by AutofillUnlockActivity).
 *   4. If the form looks like a registration (multi-password / NEW_PASSWORD
 *      hint), ALSO surface a "Create new SeQured credential" dataset.
 *   5. If no entries match the site at all, surface "Choose entry…" + (if
 *      registration) the "Create new" option.
 *
 * Master + PIN never live in the service. The unlock activity owns the
 * prompt and never persists secrets between fills.
 */
@RequiresApi(Build.VERSION_CODES.O)
class SeQuredAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)
        val ctx = AutofillFieldFinder.find(structure)
        if (!ctx.isUsable) return callback.onSuccess(null)

        val requestingPackage = structure.activityComponent?.packageName
            ?: applicationContext.packageName
        val candidates = AutofillMatcher.candidates(requestingPackage, ctx.webDomain)
        val hintSite = candidates.firstOrNull()?.site

        val app = applicationContext as SeQuredApp
        val unlockedNow = (app.session.state.value as? VaultSession.State.Unlocked)
        val matchedEntries: List<VaultEntry> =
            unlockedNow?.let { AutofillMatcher.matchEntries(it.payload.entries, candidates) } ?: emptyList()

        android.util.Log.i(
            "SqAutofillSvc",
            "onFillRequest: pkg=$requestingPackage web=${ctx.webDomain} u=${ctx.usernameId} e=${ctx.emailId} " +
                "pws=${ctx.passwordIds.size} reg=${ctx.looksLikeRegistration} matches=${matchedEntries.size}",
        )

        val responseBuilder = FillResponse.Builder()
        var datasetCount = 0

        // Existing-entry datasets — one per matching credential.
        for (entry in matchedEntries) {
            responseBuilder.addDataset(
                buildAuthDataset(
                    ctx = ctx,
                    requestCode = entry.id.value.hashCode(),
                    presentation = "${entry.site} — Tap to unlock & fill",
                    subtitle = entry.displayId,
                    intent = AutofillUnlockActivity.intent(
                        applicationContext,
                        mode = AutofillUnlockActivity.Mode.FillExisting,
                        entryId = entry.id.value,
                        ctx = ctx,
                        hintSite = hintSite,
                    ),
                )
            )
            datasetCount++
        }

        // "Choose entry…" fallback when nothing matched but we still have a
        // session — lets the user pick from their full vault.
        if (matchedEntries.isEmpty()) {
            responseBuilder.addDataset(
                buildAuthDataset(
                    ctx = ctx,
                    requestCode = ("pick:$requestingPackage").hashCode(),
                    presentation = "SeQured Identity — Choose entry…",
                    subtitle = null,
                    intent = AutofillUnlockActivity.intent(
                        applicationContext,
                        mode = AutofillUnlockActivity.Mode.FillExisting,
                        entryId = null,
                        ctx = ctx,
                        hintSite = hintSite,
                    ),
                )
            )
            datasetCount++
        }

        // "Create new credential" — always offered when a password field is
        // present. Lets the user spin up a fresh entry from any form (login
        // or registration), useful for a second account on the same site or
        // when matching missed the entry by name.
        if (ctx.firstPasswordId != null) {
            val label = when {
                ctx.looksLikeRegistration -> "SeQured Identity — Create new credential"
                matchedEntries.isEmpty()  -> "SeQured Identity — Add this site"
                else                       -> "SeQured Identity — New credential here"
            }
            android.util.Log.i("SqAutofillSvc", "Adding create-new dataset: '$label' (registration=${ctx.looksLikeRegistration}, matches=${matchedEntries.size})")
            responseBuilder.addDataset(
                buildAuthDataset(
                    ctx = ctx,
                    requestCode = ("create:$requestingPackage:${System.nanoTime()}".hashCode()),
                    presentation = label,
                    subtitle = hintSite,
                    intent = AutofillUnlockActivity.intent(
                        applicationContext,
                        mode = AutofillUnlockActivity.Mode.CreateNew,
                        entryId = null,
                        ctx = ctx,
                        hintSite = hintSite,
                    ),
                )
            )
            datasetCount++
        }

        // SaveInfo so the system suppresses "couldn't save" warnings.
        val saveTargets = listOfNotNull(ctx.usernameId, ctx.emailId, ctx.firstPasswordId).toTypedArray()
        if (saveTargets.isNotEmpty()) {
            val saveType = SaveInfo.SAVE_DATA_TYPE_PASSWORD or
                SaveInfo.SAVE_DATA_TYPE_USERNAME or
                SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS
            responseBuilder.setSaveInfo(SaveInfo.Builder(saveType, saveTargets).build())
        }

        callback.onSuccess(if (datasetCount > 0) responseBuilder.build() else null)
    }

    /**
     * Build a single Dataset whose values come from an authenticated activity.
     * We pre-set placeholder presentations on every field so the system
     * shows the chooser correctly; the actual values land via the
     * activity's EXTRA_AUTHENTICATION_RESULT dataset.
     */
    private fun buildAuthDataset(
        ctx: AutofillContext,
        requestCode: Int,
        presentation: String,
        subtitle: String?,
        intent: Intent,
    ): Dataset {
        val pi = PendingIntent.getActivity(
            applicationContext, requestCode, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val view = RemoteViews(packageName,
            if (subtitle != null) android.R.layout.simple_list_item_2 else android.R.layout.simple_list_item_1)
        view.setTextViewText(android.R.id.text1, presentation)
        if (subtitle != null) view.setTextViewText(android.R.id.text2, subtitle)
        return Dataset.Builder().apply {
            setAuthentication(pi.intentSender)
            ctx.usernameId?.let { setValue(it, null, view) }
            ctx.emailId?.let    { setValue(it, null, view) }
            ctx.passwordIds.forEach { setValue(it, null, view) }
        }.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // No-op for now. Capturing raw submitted passwords from other apps
        // doesn't fit the stateless-derivation model.
        callback.onSuccess()
    }
}
