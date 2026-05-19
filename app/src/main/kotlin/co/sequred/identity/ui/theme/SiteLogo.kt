package co.sequred.identity.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Resolves a credential's `site` string to its bundled brand logo, falling
 * back to a Capri→Congo gradient avatar with the site's first letter.
 *
 * Matches iOS `SiteLogoView` so the same credential renders the same brand
 * mark on every device: lowercase, strip protocol / path / port / `www.`,
 * take the first segment before `.` — `https://www.GitHub.com/me` → `github`.
 */
@Composable
fun SiteLogo(site: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val key = domainKey(site)
    val res = SiteLogos.byDomain[key]
    val corner = size * 0.22f
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Color.White),
        )
    } else {
        InitialAvatar(key.ifEmpty { site }, size = size, modifier = modifier)
    }
}

@Composable
private fun InitialAvatar(text: String, size: Dp, modifier: Modifier = Modifier) {
    val initial = (text.firstOrNull() ?: '?').uppercase()
    val corner = size * 0.22f
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(deterministicGradient(text)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/**
 * Per-letter hash → a brush in the brand family. Keeps avatars stable across
 * sessions and avoids the rainbow-soup look of pure HSV.
 */
private fun deterministicGradient(text: String): Brush {
    var h = 5381L
    for (b in text.lowercase().toByteArray()) h = h * 33 + b
    // Anchor on Capri/Congo and vary the second stop slightly so different
    // sites don't look identical without becoming garish.
    val shift = ((h % 60) - 30).toInt()
    val secondary = Color(
        red = ((0x86 + shift).coerceIn(0, 255)) / 255f,
        green = ((0xCF + shift).coerceIn(0, 255)) / 255f,
        blue = 1f,
    )
    return Brush.linearGradient(listOf(Brand.Capri, secondary))
}

private fun domainKey(site: String): String {
    var s = site.trim().lowercase()
    val proto = s.indexOf("://")
    if (proto >= 0) s = s.substring(proto + 3)
    val slash = s.indexOf('/')
    if (slash >= 0) s = s.substring(0, slash)
    val colon = s.lastIndexOf(':')
    if (colon >= 0) s = s.substring(0, colon)
    if (s.startsWith("www.")) s = s.substring(4)
    return s.substringBefore('.', s)
}
