package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    secondary = GreenSecondary,
    tertiary = PurpleTertiary,
    background = CosmicBackground,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = CosmicBackground,
    onSecondary = CosmicBackground,
    onTertiary = CosmicBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = SlatePrimaryLight,
    secondary = SlateSecondaryLight,
    tertiary = PurpleTertiary,
    background = SlateBackgroundLight,
    surface = SlateSurfaceLight,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = SlateSurfaceLight,
    onSecondary = SlateSurfaceLight,
    onBackground = CosmicBackground,
    onSurface = CosmicBackground
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep standard theme support but prioritize high contrast dark theme
    dynamicColor: Boolean = false, // Disable dynamic colors by default so our terminal style isn't overridden by system wallpapers!
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // Terminal is naturally stunning in dark theme, so we use DarkColorScheme as default for that true hacker aesthetic!
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
