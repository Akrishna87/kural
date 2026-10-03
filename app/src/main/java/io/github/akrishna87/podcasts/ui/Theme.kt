package io.github.akrishna87.podcasts.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Near-black with a violet accent and a warm coral second colour, like the launcher icon. */
object Palette {
    val Background = Color(0xFF0C0B10)
    val Elevated = Color(0xFF17151D)
    val Elevated2 = Color(0xFF221F2A)
    val Highlight = Color(0xFF2D2937)
    val Text = Color(0xFFFFFFFF)
    val SubText = Color(0xFFA9A5B4)
    val Faint = Color(0xFF706C7C)
    val Violet = Color(0xFFA78BFA)
    val Coral = Color(0xFFFF7A6B)
    val Gold = Color(0xFFFFD27A)
    val Green = Color(0xFF5AD19A)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Color(0xFF1E1240),
    primaryContainer = Color(0xFF3D2C78),
    onPrimaryContainer = Color(0xFFE6DDFF),
    secondary = Palette.Coral,
    onSecondary = Color(0xFF3A0B05),
    secondaryContainer = Color(0xFF5E2219),
    onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Palette.Gold,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Elevated2,
    onSurfaceVariant = Palette.SubText,
    surfaceContainerLowest = Color(0xFF060509),
    surfaceContainerLow = Color(0xFF121017),
    surfaceContainer = Palette.Elevated,
    surfaceContainerHigh = Palette.Elevated2,
    surfaceContainerHighest = Palette.Highlight,
    outline = Color(0xFF4C4757),
    outlineVariant = Color(0xFF2C2934),
    inverseSurface = Color(0xFFF3F1F7),
    inverseOnSurface = Color(0xFF17151D),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    )
}

val BrandGradient = Brush.linearGradient(listOf(Color(0xFF6D4AE0), Palette.Coral))

/** A darker shade for backgrounds, keeping white text readable. */
fun Color.deep(amount: Float = 0.45f): Color = lerp(this, Color.Black, amount)

@Composable
fun KuralTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography) {
        // Text and icons default to black outside a Surface; on this dark app they should be white.
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
