package co.sequred.identity.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import co.sequred.identity.R

/**
 * Window size class — single signal the screens use to adapt padding,
 * column counts, and form factor. Derived from current configuration so it
 * stays correct across orientation changes and across a foldable's outer /
 * inner display swap.
 */
enum class WindowSize { Compact, Medium, Expanded }

val LocalWindowSize = compositionLocalOf { WindowSize.Compact }

@Composable
fun rememberWindowSize(): WindowSize {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 840 -> WindowSize.Expanded   // foldable inner display, tablet
        w >= 600 -> WindowSize.Medium     // large phone landscape, small tablet
        else -> WindowSize.Compact
    }
}

/**
 * Drop the ghosted brand mark behind everything plus a soft radial wash
 * from Capri to black. Use as the outermost wrapper on every screen so the
 * branding stays continuous as the user navigates.
 */
@Composable
fun SeQuredBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brand.Background)
            .background(
                Brush.radialGradient(
                    colors = listOf(Brand.Congo.copy(alpha = 0.18f), Brand.Background),
                    center = Offset(x = Float.POSITIVE_INFINITY * 0.5f, y = 0f),
                    radius = 1400f,
                ),
            ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Real ghost mark — large, centred, very faint.
            Image(
                painter = painterResource(R.drawable.sequred_icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.05f,
                modifier = Modifier.fillMaxSize(0.85f),
            )
        }
        content()
    }
}

/**
 * Convenience: install the WindowSize composition local once at the app root.
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowSize provides rememberWindowSize(), content = content)
}
