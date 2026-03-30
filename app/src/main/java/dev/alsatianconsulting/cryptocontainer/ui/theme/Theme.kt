package dev.alsatianconsulting.cryptocontainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    outline = BrandOrangeSoft
)

private val LightColors = lightColorScheme(
    primary = BrandOrange,
    onPrimary = WarmInk,
    primaryContainer = BrandOrangePale,
    onPrimaryContainer = WarmInk,
    secondary = BrandOrangeDeep,
    onSecondary = Color.White,
    secondaryContainer = WarmSurfaceVariantLight,
    onSecondaryContainer = WarmInkSoft,
    tertiary = BrandOrangeSoft,
    onTertiary = WarmInk,
    background = WarmSurfaceLight,
    onBackground = WarmInk,
    surface = Color.White,
    onSurface = WarmInk,
    surfaceVariant = WarmSurfaceVariantLight,
    onSurfaceVariant = WarmInkSoft,
    outline = BrandOrangeDeep,
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
