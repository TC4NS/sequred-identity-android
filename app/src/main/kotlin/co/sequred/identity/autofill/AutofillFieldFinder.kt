package co.sequred.identity.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi

/**
 * What we found in the requesting app's view hierarchy.
 *
 *  - usernameId / emailId: separate so we can fill the right field on forms
 *    that ask for both. usernameId is the catch-all generic identifier
 *    field; emailId is set only when we positively classify a field as an
 *    email input (HINT_EMAIL_ADDRESS, type=email, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS).
 *  - passwordIds: ALL password fields, in document order. Confirm-password
 *    forms get both fields filled with the same derived value. Registration
 *    forms (typically two password fields) are detected by passwordIds.size >= 2.
 *  - hasNewPasswordHint: true if any field carries the NEW_PASSWORD autofill
 *    hint — strong signal we're on a registration form.
 *  - webDomain: the page's host if the form lives in a WebView; else null.
 */
data class AutofillContext(
    val usernameId: AutofillId? = null,
    val emailId: AutofillId? = null,
    val passwordIds: List<AutofillId> = emptyList(),
    val hasNewPasswordHint: Boolean = false,
    val webDomain: String? = null,
) {
    val isUsable: Boolean
        get() = usernameId != null || emailId != null || passwordIds.isNotEmpty()
    /** True when the form looks like a sign-up / registration (multiple
     *  password fields, OR an explicit NEW_PASSWORD hint). */
    val looksLikeRegistration: Boolean
        get() = hasNewPasswordHint || passwordIds.size >= 2
    /** First password field — convenience for the common login case. */
    val firstPasswordId: AutofillId? get() = passwordIds.firstOrNull()
}

@RequiresApi(Build.VERSION_CODES.O)
object AutofillFieldFinder {

    /**
     * Walk every window node, classify each, accumulate results. Unlike the
     * previous implementation we keep walking after finding the first
     * password field so we can pick up confirm-password / signup forms.
     */
    fun find(structure: AssistStructure): AutofillContext {
        var username: AutofillId? = null
        var email: AutofillId? = null
        val passwords = mutableListOf<AutofillId>()
        var newPasswordHint = false
        var webDomain: String? = null

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            walk(root) { node ->
                if (webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { webDomain = it }
                val kind = classify(node)
                if (kind != FieldKind.None) {
                    // Diagnostic crumb so we can debug specific sites.
                    android.util.Log.d(
                        "SqFieldFind",
                        "$kind: id=${node.autofillId} hints=${node.autofillHints?.toList()} " +
                            "html=${node.htmlInfo?.let { "${it.tag}/${it.attributes?.toList()?.take(6)}" }} " +
                            "hint='${node.hint}' idEntry='${node.idEntry}' inputType=${node.inputType}",
                    )
                }
                when (kind) {
                    FieldKind.Username -> if (username == null) username = node.autofillId
                    FieldKind.Email    -> if (email == null) email = node.autofillId
                    FieldKind.Password -> node.autofillId?.let { passwords += it }
                    FieldKind.NewPassword -> {
                        node.autofillId?.let { passwords += it }
                        newPasswordHint = true
                    }
                    FieldKind.None -> {}
                }
            }
        }

        return AutofillContext(
            usernameId = username,
            emailId = email,
            passwordIds = passwords.distinct(),
            hasNewPasswordHint = newPasswordHint,
            webDomain = webDomain,
        )
    }

    private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChildAt(i), visit)
    }

    private enum class FieldKind { Username, Email, Password, NewPassword, None }

    private fun classify(node: AssistStructure.ViewNode): FieldKind {
        // 1) Explicit autofill hints (the requesting app told us). These are
        // authoritative — if a node carries an autofill hint we trust it
        // even if it's not classified as a text input (some hosts attach
        // hints to wrappers).
        val hints = node.autofillHints
        if (hints != null) {
            for (h in hints) {
                when (h) {
                    View.AUTOFILL_HINT_PASSWORD -> return FieldKind.Password
                    // "newPassword" is API-30+ string; literal works pre-API-30.
                    "newPassword" -> return FieldKind.NewPassword
                    View.AUTOFILL_HINT_EMAIL_ADDRESS -> return FieldKind.Email
                    View.AUTOFILL_HINT_USERNAME -> return FieldKind.Username
                }
            }
        }

        // For everything past this point we need an actual fillable text
        // node. Without this guard, non-input wrappers like Chrome's
        // `custom_tabs_handle_view_stub` (which has no autofill hints, no
        // html, no input type, but DOES have an idEntry matching "handle")
        // get mis-classified as usernames, then the real username field
        // gets ignored because we already captured something.
        val isFillableText = node.autofillType == View.AUTOFILL_TYPE_TEXT
        if (!isFillableText && node.htmlInfo == null) return FieldKind.None

        // 2) HTML attributes for WebView nodes.
        node.htmlInfo?.let { info ->
            if (info.tag.equals("input", ignoreCase = true)) {
                val attrs = info.attributes
                val type = attrs?.firstOrNull { it.first.equals("type", ignoreCase = true) }?.second
                val name = attrs?.firstOrNull { it.first.equals("name", ignoreCase = true) }?.second
                    ?: attrs?.firstOrNull { it.first.equals("id", ignoreCase = true) }?.second
                val autocomplete = attrs?.firstOrNull { it.first.equals("autocomplete", ignoreCase = true) }?.second
                when {
                    autocomplete.equals("new-password", ignoreCase = true) -> return FieldKind.NewPassword
                    type.equals("password", ignoreCase = true) -> {
                        return if (name != null && looksLikeNewPassword(name)) FieldKind.NewPassword
                        else FieldKind.Password
                    }
                    type.equals("email", ignoreCase = true) -> return FieldKind.Email
                    autocomplete.equals("email", ignoreCase = true) -> return FieldKind.Email
                    autocomplete.equals("username", ignoreCase = true) -> return FieldKind.Username
                    type.equals("text", ignoreCase = true) || type.equals("tel", ignoreCase = true) || type == null -> {
                        if (name != null && looksLikeEmail(name)) return FieldKind.Email
                        if (name != null && looksLikeUsername(name)) return FieldKind.Username
                    }
                }
            }
        }

        // 3) Native EditText with password / email input type.
        val inputType = node.inputType
        if (inputType != 0 && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val cls = inputType and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_TEXT) {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                    return FieldKind.Password
                }
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
                    return FieldKind.Email
                }
            }
        }

        // 4) Hint / id heuristic — low confidence. Email gets priority over
        //    username so a field named "email" doesn't end up as Username.
        val hintText = node.hint?.toString().orEmpty()
        val idEntry = node.idEntry.orEmpty()
        if (looksLikeEmail(hintText) || looksLikeEmail(idEntry)) return FieldKind.Email
        if (looksLikeUsername(hintText) || looksLikeUsername(idEntry)) return FieldKind.Username

        return FieldKind.None
    }

    private val USERNAME_HINTS = listOf(
        "username", "user_name", "user name", "userid", "user id",
        "login", "loginid", "account", "handle",
    )
    private val EMAIL_HINTS = listOf("email", "e-mail", "e_mail", "emailaddress", "email_address")
    private val NEW_PASSWORD_HINTS = listOf(
        "new", "newpass", "new_password", "new-password", "confirm",
        "confirmpass", "confirm_password", "confirm-password",
        "passwordrepeat", "password_repeat", "repeat",
    )

    private fun looksLikeUsername(s: String): Boolean {
        if (s.isBlank()) return false
        val lower = s.lowercase()
        return USERNAME_HINTS.any { lower.contains(it) }
    }
    private fun looksLikeEmail(s: String): Boolean {
        if (s.isBlank()) return false
        val lower = s.lowercase()
        return EMAIL_HINTS.any { lower.contains(it) }
    }
    private fun looksLikeNewPassword(s: String): Boolean {
        if (s.isBlank()) return false
        val lower = s.lowercase()
        return NEW_PASSWORD_HINTS.any { lower.contains(it) }
    }
}
