package co.sequred.identity.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SeQured brand tokens — derived from Sources/SeQured/Theme.swift so the
 * Android app matches the iOS visual language without us inventing a second
 * palette. Any change here should ship to iOS too.
 */
object Brand {
    val Capri = Color(0xFF00C7FF)
    val Congo = Color(0xFF0086CF)

    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0B0F14)
    val Panel = Color(0xFF101820)
    val InputBg = Color(0xFF0A1119)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA8C8E0)

    val Border = Color(0xFF162030)

    val Success = Color(0xFF00E676)
    val Danger = Color(0xFFFF4D4F)
    /** Amber — imported / potentially-unsafe credential flag. */
    val Warning = Color(0xFFFFB300)

    /** Capri → Congo top-leading → bottom-trailing — used on primary CTAs. */
    val Gradient = Brush.linearGradient(colors = listOf(Capri, Congo))
}

/** Display style — rounded semibold, our Conthrax fallback (no font file bundled yet). */
object BrandType {
    fun display(size: Int) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = (-0.2).sp,
    )
    fun body(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
    )
    fun sectionLabel() = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        color = Brand.TextSecondary,
    )
}

/**
 * Primary call-to-action button. Capri→Congo gradient, white text, generous
 * vertical padding — matches the `sqPrimaryButton()` modifier on iOS.
 * The Material `Button` underneath is transparent so the gradient shows
 * through; we drop the Material elevation to keep the look flat.
 */
@Composable
fun SqPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    label: String,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.4f),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Brand.Gradient else Brush.linearGradient(listOf(Brand.Panel, Brand.Panel))),
    ) {
        Text(label, style = BrandType.display(14))
    }
}
