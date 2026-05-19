package com.sequred.identity.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * SeQured Material 3 theme. Always dark and always brand-coloured — the iOS
 * app doesn't track the system appearance either, so we keep the look
 * consistent across platforms.
 */
private val SeQuredColors = darkColorScheme(
    primary = Brand.Capri,
    onPrimary = Color.Black,
    secondary = Brand.Congo,
    onSecondary = Color.White,
    background = Brand.Background,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.Panel,
    onSurfaceVariant = Brand.TextSecondary,
    outline = Brand.Border,
    error = Brand.Danger,
    onError = Color.White,
)

private val SeQuredTypography = Typography(
    displayLarge = BrandType.display(48),
    displayMedium = BrandType.display(36),
    displaySmall = BrandType.display(28),
    headlineLarge = BrandType.display(28),
    headlineMedium = BrandType.display(24),
    headlineSmall = BrandType.display(20),
    titleLarge = BrandType.display(20),
    titleMedium = BrandType.display(16),
    titleSmall = BrandType.body(14, androidx.compose.ui.text.font.FontWeight.Medium),
    bodyLarge = BrandType.body(16),
    bodyMedium = BrandType.body(14),
    bodySmall = BrandType.body(12),
    labelLarge = BrandType.body(14, androidx.compose.ui.text.font.FontWeight.Medium),
    labelMedium = BrandType.body(12, androidx.compose.ui.text.font.FontWeight.Medium),
    labelSmall = BrandType.sectionLabel(),
)

@Composable
fun SeQuredTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SeQuredColors,
        typography = SeQuredTypography,
        content = content,
    )
}
