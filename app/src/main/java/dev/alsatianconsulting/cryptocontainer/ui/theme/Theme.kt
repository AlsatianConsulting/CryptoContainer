package dev.alsatianconsulting.cryptocontainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark is the brand default. No dynamic (wallpaper-derived) color —
// the accent is always Alsatian orange, never the system palette.
private val DarkColors = darkColorScheme(
    primary = BrandOrange,
    onPrimary = WarmInk,
    primaryContainer = BrandOrangeDeep,
    onPrimaryContainer = BrandOrangePale,
    secondary = BrandOrangeSoft,
    onSecondary = WarmInk,
    secondaryContainer = WarmSurfaceVariantDark,
    onSecondaryContainer = WarmOnDark,
    tertiary = BrandOrangePale,
    onTertiary = WarmInk,
    background = WarmSurfaceDark,
    onBackground = WarmOnDark,
    surface = WarmSurfaceDarkAlt,
    onSurface = WarmOnDark,
    surfaceVariant = WarmSurfaceVariantDark,
    onSurfaceVariant = BrandOrangePale,
    // Elevated container tiers stay inside the warm-dark family rather
    // than drifting toward Material's default cool greys.
    surfaceContainerLowest = WarmSurfaceDark,
    surfaceContainerLow = WarmSurfaceDarkAlt,
    surfaceContainer = WarmSurfaceDarkAlt,
    surfaceContainerHigh = WarmSurfaceVariantDark,
    surfaceContainerHighest = WarmSurfaceVariantDark,
    outline = BrandOrangeSoft,
    outlineVariant = WarmSurfaceVariantDark,
    error = StateError,
    onError = WarmOnDark,
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// Light mode is warm paper, not pure white. The accent steps deeper
// (orange-600) so white text on the primary clears AA contrast.
private val LightColors = lightColorScheme(
    primary = BrandOrangeDeep,
    onPrimary = Color.White,
    primaryContainer = BrandOrangePale,
    onPrimaryContainer = WarmInk,
    secondary = BrandOrangeOnPaper,
    onSecondary = Color.White,
    secondaryContainer = PaperVariant,
    onSecondaryContainer = WarmInkSoft,
    tertiary = BrandOrange,
    onTertiary = Color.White,
    background = PaperBackground,
    onBackground = PaperInk,
    surface = PaperSurface,
    onSurface = PaperInk,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = PaperInkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = PaperBackground,
    surfaceContainer = PaperSunken,
    surfaceContainerHigh = PaperVariant,
    surfaceContainerHighest = PaperVariant,
    outline = PaperLine,
    outlineVariant = PaperLine,
    error = StateError,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun CryptoContainerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
