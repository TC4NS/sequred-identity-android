package com.sequred.identity.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.sequred.identity.SeQuredApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Centralized "copy a derived secret" entry point. Sets the IS_SENSITIVE flag
 * so Android 12+ blurs the toast and the system clipboard preview, then
 * schedules a wipe 60 seconds later on the application-lifetime coroutine
 * scope so it survives screen rotation and tab switches.
 *
 * Before wiping we compare the current clipboard contents to what we wrote —
 * if the user has since copied something else, we leave their copy alone.
 *
 * On Android 13+ the system already auto-clears sensitive clip data after a
 * device-defined window, but the helper still queues our own 60s timer as a
 * floor.
 */
object ClipboardGuard {

    private const val CLEAR_AFTER_MS = 60_000L

    /** Active wipe job — cancelled and replaced when a new sensitive copy lands. */
    @Volatile private var pendingWipe: Job? = null

    fun copySensitive(ctx: Context, label: String, secret: String) {
        val app = ctx.applicationContext as SeQuredApp
        val scope = app.appScope
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)

        pendingWipe?.cancel()
        pendingWipe = scope.launch(Dispatchers.Default) {
            delay(CLEAR_AFTER_MS)
            withContext(Dispatchers.Main) {
                val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
                if (current == secret) {
                    // Replace rather than clearPrimaryClip() — the latter is
                    // restricted on API 28+ when the app isn't foreground.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runCatching { cm.clearPrimaryClip() }
                            .onFailure {
                                cm.setPrimaryClip(ClipData.newPlainText("", ""))
                            }
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
        }
    }
}
