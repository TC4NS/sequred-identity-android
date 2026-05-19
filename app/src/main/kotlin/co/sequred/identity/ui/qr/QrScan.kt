package co.sequred.identity.ui.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import co.sequred.identity.SeQuredApp

private const val TAG = "QrScan"

/**
 * Single-shot QR scanner. Requests the runtime CAMERA permission before
 * launching ZXing — without that the scanner silently no-ops on Android 6+
 * and especially on GrapheneOS where sensor permissions default to deny.
 * Any unexpected error surfaces as a Toast so the user gets feedback instead
 * of the button apparently doing nothing.
 */
@Composable
fun rememberQrLauncher(
    prompt: String,
    onResult: (ScanIntentResult) -> Unit,
): () -> Unit {
    val ctx = LocalContext.current
    val session = remember(ctx) { (ctx.applicationContext as SeQuredApp).session }
    val options = remember(prompt) { defaultOptions(prompt) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r ->
        // Release the auto-lock hold no matter what came back.
        session.resumeAutoLock()
        try {
            onResult(r)
        } catch (t: Throwable) {
            // Don't surface t.message — an exception parsing a malformed
            // pasted URI could echo a secret fragment. Diagnostics stay in
            // Log.e only (and Log.e itself is stripped in release builds).
            Log.e(TAG, "Scan result handler failed", t)
            Toast.makeText(ctx, "QR scan failed.", Toast.LENGTH_LONG).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The permission prompt is done — release the hold we took to cover it.
        session.resumeAutoLock()
        if (granted) {
            // Re-suspend across the scan itself; scanLauncher callback releases.
            session.suspendAutoLock()
            launchScanSafely(ctx, scanLauncher, options) { session.resumeAutoLock() }
        } else {
            Toast.makeText(ctx, "Camera permission denied — can't scan QR.", Toast.LENGTH_LONG).show()
        }
    }
    return remember(permissionLauncher) {
        {
            if (hasCameraPermission(ctx)) {
                session.suspendAutoLock()
                launchScanSafely(ctx, scanLauncher, options) { session.resumeAutoLock() }
            } else {
                session.suspendAutoLock()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

private fun launchScanSafely(
    ctx: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<ScanOptions>,
    options: ScanOptions,
    onLaunchFailure: () -> Unit = {},
) {
    try {
        launcher.launch(options)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to launch ZXing CaptureActivity", t)
        Toast.makeText(ctx, "Scanner unavailable.", Toast.LENGTH_LONG).show()
        onLaunchFailure()
    }
}

private fun defaultOptions(prompt: String): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt(prompt)
    setOrientationLocked(false)
    setBeepEnabled(false)
}

private fun hasCameraPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

data class OtpAuth(val secret: String, val issuer: String?, val account: String?)

fun parseOtpAuth(uri: String): OtpAuth? {
    if (!uri.startsWith("otpauth://", ignoreCase = true)) return null
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
    if (!parsed.host.equals("totp", ignoreCase = true)) return null
    val secret = parsed.getQueryParameter("secret")?.replace(" ", "")?.uppercase() ?: return null
    val label = parsed.path?.trimStart('/')?.let { Uri.decode(it) }
    val labelIssuer: String?; val account: String?
    if (label != null && label.contains(":")) {
        val parts = label.split(":", limit = 2)
        labelIssuer = parts[0].trim().ifEmpty { null }
        account = parts.getOrNull(1)?.trim()?.ifEmpty { null }
    } else { labelIssuer = null; account = label?.trim()?.ifEmpty { null } }
    val issuer = parsed.getQueryParameter("issuer")?.trim()?.ifEmpty { null } ?: labelIssuer
    return OtpAuth(secret = secret, issuer = issuer, account = account)
}
