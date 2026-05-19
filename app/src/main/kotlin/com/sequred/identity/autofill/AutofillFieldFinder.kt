package com.sequred.identity.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.RequiresApi

/**
 * What we found in the requesting app's view hierarchy.
 *
 *  - usernameId / passwordId: AutofillIds to bind values into via Dataset.
 *  - webDomain: the page's host if the form lives in a WebView; else null.
 *  - packageName: the requesting app's package — used as a fallback site key.
 */
data class AutofillContext(
    val usernameId: AutofillId? = null,
    val passwordId: AutofillId? = null,
    val webDomain: String? = null,
    val packageName: String? = null,
) {
    val isUsable: Boolean get() = usernameId != null || passwordId != null
}

@RequiresApi(Build.VERSION_CODES.O)
object AutofillFieldFinder {

    /**
     * Walk the AssistStructure across every window + node looking for a
     * username + password pair. Prefers explicit autofill hints set by the
     * requesting app; falls back to HTML attributes (name/type), then to
     * InputType heuristics.
     *
     * Stops at the first plausible login form per request — handling multi-
     * form pages (e.g. a navbar login overlay above a search form) is a
     * follow-up.
     */
    fun find(structure: AssistStructure): AutofillContext {
        var username: AutofillId? = null
        var password: AutofillId? = null
        var webDomain: String? = null

        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            walk(root) { node ->
                if (webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { webDomain = it }
                when (classify(node)) {
                    FieldKind.Username -> if (username == null) username = node.autofillId
                    FieldKind.Password -> if (password == null) password = node.autofillId
                    FieldKind.None -> {}
                }
                // Stop the walk early if we've found both — small speedup
                // on large hierarchies (gmail web ~ 4000 nodes).
                username != null && password != null
            }
            if (username != null && password != null) break
        }

        return AutofillContext(
            usernameId = username,
            passwordId = password,
            webDomain = webDomain,
        )
    }

    /** Depth-first traversal. `visit` returns true to short-circuit. */
    private fun walk(node: AssistStructure.ViewNode, visit: (AssistStructure.ViewNode) -> Boolean): Boolean {
        if (visit(node)) return true
        for (i in 0 until node.childCount) {
            if (walk(node.getChildAt(i), visit)) return true
        }
        return false
    }

    private enum class FieldKind { Username, Password, None }

    private fun classify(node: AssistStructure.ViewNode): FieldKind {
        // 1) Explicit autofill hints (the requesting app told us).
        val hints = node.autofillHints
        if (hints != null) {
            for (h in hints) {
                when (h) {
                    View.AUTOFILL_HINT_PASSWORD -> return FieldKind.Password
                    View.AUTOFILL_HINT_USERNAME,
                    View.AUTOFILL_HINT_EMAIL_ADDRESS -> return FieldKind.Username
                }
            }
        }

        // 2) HTML attributes for WebView nodes.
        node.htmlInfo?.let { info ->
            if (info.tag.equals("input", ignoreCase = true)) {
                val type = info.attributes?.firstOrNull { it.first.equals("type", ignoreCase = true) }?.second
                val name = info.attributes?.firstOrNull { it.first.equals("name", ignoreCase = true) }?.second
                    ?: info.attributes?.firstOrNull { it.first.equals("id", ignoreCase = true) }?.second
                if (type.equals("password", ignoreCase = true)) return FieldKind.Password
                if (type.equals("email", ignoreCase = true)) return FieldKind.Username
                if (type.equals("text", ignoreCase = true) || type.equals("tel", ignoreCase = true) || type == null) {
                    if (name != null && looksLikeUsername(name)) return FieldKind.Username
                }
            }
        }

        // 3) Native EditText with password input type.
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
                    return FieldKind.Username
                }
            }
        }

        // 4) Hint/idEntry heuristic. Last resort, low confidence — only
        // promote to username if password not yet found, so we don't
        // mis-classify random text fields.
        val hintText = node.hint?.toString().orEmpty()
        val idEntry = node.idEntry.orEmpty()
        if (looksLikeUsername(hintText) || looksLikeUsername(idEntry)) {
            return FieldKind.Username
        }

        return FieldKind.None
    }

    private val USERNAME_HINTS = listOf(
        "username", "user_name", "user name", "userid", "user id",
        "email", "e-mail", "login", "loginid", "account",
    )

    private fun looksLikeUsername(s: String): Boolean {
        if (s.isBlank()) return false
        val lower = s.lowercase()
        return USERNAME_HINTS.any { lower.contains(it) }
    }
}
