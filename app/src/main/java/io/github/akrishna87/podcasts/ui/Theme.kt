package io.github.akrishna87.podcasts.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The accent colour (buttons, highlights): its colour in the dark and light themes, and a second one for gradients and time links. */
enum class AccentColor(val label: String, val dark: Color, val dark2: Color, val light: Color, val light2: Color) {
    VIOLET("Violet", Color(0xFFA78BFA), Color(0xFFFF7A6B), Color(0xFF6A47E6), Color(0xFFD9483A)),
    ROSE("Rose", Color(0xFFFF5C9A), Color(0xFFA06BFF), Color(0xFFD6336C), Color(0xFF7B47E6)),
    CORAL("Coral", Color(0xFFFF7A6B), Color(0xFFA78BFA), Color(0xFFD9483A), Color(0xFF6A47E6)),
    AMBER("Amber", Color(0xFFFFB020), Color(0xFFFF7A6B), Color(0xFFB86E00), Color(0xFFD9483A)),
    GREEN("Green", Color(0xFF5AD19A), Color(0xFF2EC4B6), Color(0xFF1F8F55), Color(0xFF0E8F84)),
    TEAL("Teal", Color(0xFF2EC4B6), Color(0xFF5B8CFF), Color(0xFF0B8378), Color(0xFF3F6FE0)),
    BLUE("Blue", Color(0xFF5B9DFF), Color(0xFFA78BFA), Color(0xFF1E66D0), Color(0xFF5B47E6)),
    RED("Red", Color(0xFFFF5A5A), Color(0xFFFF9A5A), Color(0xFFD32F2F), Color(0xFFE65A19)),
}

/** Dark, light, or follow the phone's setting. */
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Match phone") }

/** One set of the app's colours. */
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val elevated: Color,
    val elevated2: Color,
    val highlight: Color,
    val text: Color,
    val subText: Color,
    val faint: Color,
    /** The accent (Material's primary). */
    val violet: Color,
    /** The second accent (Material's secondary): time links, "new" dots and badges. */
    val coral: Color,
    val gold: Color,
    /** Favourites: the pink of the app icon's wave. */
    val heart: Color,
    val green: Color,
    /** Text and icons on top of the accent colour. */
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val onSecond: Color,
    val secondContainer: Color,
    val onSecondContainer: Color,
)

/** Near-black with a violet accent and a warm coral second colour, like the launcher icon. */
val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF0C0B10),
    elevated = Color(0xFF17151D),
    elevated2 = Color(0xFF221F2A),
    highlight = Color(0xFF2D2937),
    text = Color(0xFFFFFFFF),
    subText = Color(0xFFA9A5B4),
    faint = Color(0xFF706C7C),
    violet = Color(0xFFA78BFA),
    coral = Color(0xFFFF7A6B),
    gold = Color(0xFFFFD27A),
    heart = Color(0xFFFF4D8D),
    green = Color(0xFF5AD19A),
    onAccent = Color(0xFF1E1240),
    accentContainer = Color(0xFF3D2C78),
    onAccentContainer = Color(0xFFE6DDFF),
    onSecond = Color(0xFF3A0B05),
    secondContainer = Color(0xFF5E2219),
    onSecondContainer = Color(0xFFFFDAD4),
)

/** Near-white with a faint violet cast, and deeper accents so they read on it. */
val LightAppColors = AppColors(
    isDark = false,
    background = Color(0xFFF9F8FC),
    elevated = Color(0xFFEFEDF5),
    elevated2 = Color(0xFFE5E2EE),
    highlight = Color(0xFFD9D5E4),
    text = Color(0xFF16141C),
    subText = Color(0xFF5C5869),
    faint = Color(0xFF8F8A9C),
    violet = Color(0xFF6A47E6),
    coral = Color(0xFFD9483A),
    gold = Color(0xFF9A6500),
    heart = Color(0xFFD6336C),
    green = Color(0xFF1F8F55),
    onAccent = Color.White,
    accentContainer = Color(0xFFE6DDFF),
    onAccentContainer = Color(0xFF240B6B),
    onSecond = Color.White,
    secondContainer = Color(0xFFFFDAD4),
    onSecondContainer = Color(0xFF410A04),
)

/**
 * The app's colours. They change with the theme setting; reading one from a screen redraws it
 * when the theme changes.
 */
object Palette {
    var colors by mutableStateOf(DarkAppColors)
    /** The dark colours in the same accent, for the always-dark player. */
    var darkColors by mutableStateOf(DarkAppColors)

    val isDark get() = colors.isDark
    val Background get() = colors.background
    val Elevated get() = colors.elevated
    val Elevated2 get() = colors.elevated2
    val Highlight get() = colors.highlight
    val Text get() = colors.text
    val SubText get() = colors.subText
    val Faint get() = colors.faint
    val Violet get() = colors.violet
    val Coral get() = colors.coral
    val Gold get() = colors.gold
    /** Favourites: the pink of the app icon's wave. */
    val Heart get() = colors.heart
    val Green get() = colors.green
}

/**
 * The colours in use where you are: the app's theme, except inside [AlwaysDark] (the player),
 * where they're the dark ones. Shared pieces that also appear in the player read these.
 */
val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/**
 * The colours for a theme, in the accent colour you picked, or with accents taken from the
 * wallpaper when [wallpaper] is on (Android 12+).
 */
fun appColors(context: Context, dark: Boolean, wallpaper: Boolean, accent: AccentColor = AccentColor.VIOLET): AppColors {
    val plain = if (dark) DarkAppColors else LightAppColors
    if (!wallpaper || Build.VERSION.SDK_INT < 31) return plain.accented(accent)
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return plain.copy(
        violet = scheme.primary,
        coral = scheme.tertiary,
        onAccent = scheme.onPrimary,
        accentContainer = scheme.primaryContainer,
        onAccentContainer = scheme.onPrimaryContainer,
        onSecond = scheme.onTertiary,
        secondContainer = scheme.tertiaryContainer,
        onSecondContainer = scheme.onTertiaryContainer,
    )
}

/** These colours in another accent. */
private fun AppColors.accented(accent: AccentColor): AppColors {
    // The original look, exactly, for the default accent.
    if (accent == AccentColor.VIOLET) return this
    val dark = isDark
    val plain = this
    val a = if (dark) accent.dark else accent.light
    val b = if (dark) accent.dark2 else accent.light2
    return if (dark) {
        plain.copy(
            violet = a,
            coral = b,
            onAccent = lerp(a, Color.Black, 0.82f),
            accentContainer = lerp(a, Color.Black, 0.6f),
            onAccentContainer = lerp(a, Color.White, 0.78f),
            onSecond = lerp(b, Color.Black, 0.82f),
            secondContainer = lerp(b, Color.Black, 0.65f),
            onSecondContainer = lerp(b, Color.White, 0.78f),
        )
    } else {
        plain.copy(
            violet = a,
            coral = b,
            onAccent = Color.White,
            accentContainer = lerp(a, Color.White, 0.82f),
            onAccentContainer = lerp(a, Color.Black, 0.6f),
            onSecond = Color.White,
            secondContainer = lerp(b, Color.White, 0.82f),
            onSecondContainer = lerp(b, Color.Black, 0.6f),
        )
    }
}

private fun AppColors.scheme(): ColorScheme =
    if (isDark) {
        darkColorScheme(
            primary = violet,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = coral,
            onSecondary = onSecond,
            secondaryContainer = secondContainer,
            onSecondaryContainer = onSecondContainer,
            tertiary = gold,
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = elevated2,
            onSurfaceVariant = subText,
            surfaceContainerLowest = Color(0xFF060509),
            surfaceContainerLow = Color(0xFF121017),
            surfaceContainer = elevated,
            surfaceContainerHigh = elevated2,
            surfaceContainerHighest = highlight,
            outline = Color(0xFF4C4757),
            outlineVariant = Color(0xFF2C2934),
            inverseSurface = Color(0xFFF3F1F7),
            inverseOnSurface = Color(0xFF17151D),
        )
    } else {
        lightColorScheme(
            primary = violet,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = coral,
            onSecondary = onSecond,
            secondaryContainer = secondContainer,
            onSecondaryContainer = onSecondContainer,
            tertiary = gold,
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = elevated2,
            onSurfaceVariant = subText,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF4F2F8),
            surfaceContainer = elevated,
            surfaceContainerHigh = elevated2,
            surfaceContainerHighest = highlight,
            outline = Color(0xFF8A8598),
            outlineVariant = Color(0xFFCBC6D6),
            inverseSurface = Color(0xFF2A2731),
            inverseOnSurface = Color(0xFFF3F1F7),
        )
    }

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

val BrandGradient = Brush.linearGradient(listOf(Color(0xFF6D4AE0), Color(0xFFFF7A6B)))

/** A darker shade for backgrounds, keeping white text readable. */
fun Color.deep(amount: Float = 0.45f): Color = lerp(this, Color.Black, amount)

/**
 * A tint for page headers that fade into the page: darker in the dark theme (as [deep]), a pale
 * wash of it in the light one, so the theme's text stays readable on it.
 */
fun Color.wash(amount: Float = 0.45f): Color =
    if (Palette.isDark) deep(amount) else lerp(this, Palette.Background, 0.6f + amount * 0.4f)

@Composable
fun PodwaveTheme(
    mode: ThemeMode = ThemeMode.DARK,
    wallpaperColors: Boolean = false,
    accent: AccentColor = AccentColor.VIOLET,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = remember(dark, wallpaperColors, accent) { appColors(context, dark, wallpaperColors, accent) }
    val darkColors = remember(dark, wallpaperColors, accent) { if (dark) colors else appColors(context, true, wallpaperColors, accent) }
    SideEffect {
        if (Palette.colors != colors) Palette.colors = colors
        if (Palette.darkColors != darkColors) Palette.darkColors = darkColors
    }
    val scheme = remember(colors) { colors.scheme() }
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        // Text and icons default to black outside a Surface; use the theme's text colour instead.
        CompositionLocalProvider(LocalAppColors provides colors, LocalContentColor provides colors.text, content = content)
    }
}

/**
 * Keeps the player dark in every theme, tinted by the artwork, like the players in other apps:
 * dark Material colours, white text, and [LocalAppColors] set to the dark colours.
 */
@Composable
fun AlwaysDark(content: @Composable () -> Unit) {
    val dark = Palette.darkColors
    val scheme = remember(dark) { dark.scheme() }
    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        CompositionLocalProvider(LocalAppColors provides dark, LocalContentColor provides Color.White, content = content)
    }
}

/** The theme choice, saved on the phone and read before the first frame (so there's no flash). */
object ThemeSettings {
    var mode by mutableStateOf(ThemeMode.DARK)
        private set
    var wallpaperColors by mutableStateOf(false)
        private set
    var accent by mutableStateOf(AccentColor.VIOLET)
        private set
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        mode = ThemeMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: ThemeMode.DARK
        wallpaperColors = p.getBoolean("wallpaperColors", false)
        accent = AccentColor.entries.firstOrNull { it.name == p.getString("accent", null) } ?: AccentColor.VIOLET
        // Set the colours now, so the first frame is already in the right theme.
        val dark = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        Palette.colors = appColors(context, dark, wallpaperColors, accent)
        Palette.darkColors = if (dark) Palette.colors else appColors(context, true, wallpaperColors, accent)
    }

    fun setAccent(context: Context, color: AccentColor) {
        accent = color
        prefs(context).edit().putString("accent", color.name).apply()
    }

    fun update(context: Context, mode: ThemeMode = this.mode, wallpaperColors: Boolean = this.wallpaperColors) {
        this.mode = mode
        this.wallpaperColors = wallpaperColors
        prefs(context).edit().putString("mode", mode.name).putBoolean("wallpaperColors", wallpaperColors).apply()
    }
}
