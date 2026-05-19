package co.sequred.identity

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import co.sequred.identity.data.VaultSession
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import co.sequred.identity.ui.AppNav
import co.sequred.identity.ui.theme.ProvideWindowSize
import co.sequred.identity.ui.theme.SeQuredBackdrop
import co.sequred.identity.ui.theme.SeQuredTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val session: VaultSession by lazy { (application as SeQuredApp).session }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Block screenshots, screen recording, and Recents thumbnails so a
        // brief shoulder-surf or thief can't capture a derived password, the
        // TOTP entry editor, or the PIN entry. Applied globally because
        // every screen except the splash can surface a secret.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Edge-to-edge with our brand black on both bars so the radial-gradient
        // backdrop runs cleanly under the status / navigation insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SeQuredTheme {
                ProvideWindowSize {
                    SeQuredBackdrop(modifier = Modifier.fillMaxSize()) {
                        AppNav(session = session)
                    }
                }
            }
        }
        startInactivityWatchdog()
    }

    /** Any touch resets the idle timer. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        session.touch()
        return super.dispatchTouchEvent(ev)
    }


    /**
     * Wait for any pending save to flush, then lock. Without the awaitPendingSaves
     * call a freshly-added credential can be lost if the user backgrounds the
     * app mid-Argon2id (which takes ~500 ms on device).
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Don't lock if we're the one who pushed another activity (QR scanner,
        // permission prompt) — the user expects to come back to the same form.
        if (session.isAutoLockSuspended()) return
        lifecycleScope.launch {
            session.awaitPendingSaves()
            session.lock()
        }
    }

    /**
     * Resume gate. If we backgrounded long enough that the idle timer expired
     * — e.g. user grabbed the phone an hour later — lock SYNCHRONOUSLY before
     * any vault content paints. Otherwise the 15s watchdog tick could let the
     * unlocked UI flash for up to a quarter-minute before locking.
     *
     * Only touch() (reset the idle timer) when we came back from our own
     * activity (file picker, QR scanner). The autoLockSuspended flag is set
     * by those launchers; clearing here covers any path that forgot to.
     */
    override fun onResume() {
        super.onResume()
        if (session.isIdleExpired()) {
            session.lock()
        } else {
            session.touch()
        }
    }

    /** Auto-lock background task. Cheap polling; the alternative (a scheduled
     *  alarm) is overkill for an app-foreground timer. */
    private fun startInactivityWatchdog() {
        lifecycleScope.launch {
            while (true) {
                delay(15_000)
                if (session.isIdleExpired()) session.lock()
            }
        }
    }
}
