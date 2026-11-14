package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = LuminousBlue,
    onPrimary = Color(0xFF002138),
    primaryContainer = Color(0xFF0A3459),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = LuminousIndigo,
    onSecondary = Color(0xFF071A38),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = CyanSignal,
    background = ConsoleBackground,
    onBackground = ConsoleTextPrimary,
    surface = ConsoleSurface,
    onSurface = ConsoleTextPrimary,
    surfaceVariant = ConsoleSurfaceVariant,
    onSurfaceVariant = ConsoleTextSecondary,
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val LightColorScheme = lightColorScheme(
    primary = BlueprintBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = BlueprintBlueDark,
    secondary = CyanSignal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = SlateInk,
    background = PaperCanvas,
    onBackground = SlateInk,
    surface = CardWhite,
    onSurface = SlateInk,
    surfaceVariant = Color(0xFFEFF3F9),
    onSurfaceVariant = SlateMuted,
    outline = SoftGridBorder,
    error = CrimsonError,
    onError = Color.White
)

@Composable
fun PrintForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PrintForgeTypography,
        content = content
    )
}
