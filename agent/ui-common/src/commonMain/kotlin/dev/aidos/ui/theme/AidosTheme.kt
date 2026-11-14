package dev.aidos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AidosDarkColorScheme: ColorScheme = darkColorScheme(
    primary = ColorTokens.ActorAgent,
    onPrimary = Color.Black,
    secondary = ColorTokens.ActorHuman,
    onSecondary = Color.Black,
    tertiary = ColorTokens.ActorHybrid,
    onTertiary = Color.White,
    background = ColorTokens.SurfaceDark,
    onBackground = Color(0xFFF1F5F9),
    surface = ColorTokens.SurfaceCardDark,
    onSurface = Color(0xFFF8FAFC),
    error = ColorTokens.StatusBlocked,
    onError = Color.White,
)

val AidosOledColorScheme: ColorScheme = darkColorScheme(
    primary = ColorTokens.ActorAgent,
    onPrimary = Color.Black,
    secondary = ColorTokens.ActorHuman,
    onSecondary = Color.Black,
    tertiary = ColorTokens.ActorHybrid,
    onTertiary = Color.White,
    background = ColorTokens.OledBlack,
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0F1D),
    onSurface = Color(0xFFFFFFFF),
    error = ColorTokens.StatusBlocked,
    onError = Color.White,
)

val AidosLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    secondary = Color(0xFFD97706),
    onSecondary = Color.White,
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    background = ColorTokens.SurfaceLight,
    onBackground = Color(0xFF0F172A),
    surface = ColorTokens.SurfaceCardLight,
    onSurface = Color(0xFF1E293B),
    error = ColorTokens.StatusBlocked,
    onError = Color.White,
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    OLED,
}

@Composable
fun AidosTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val colorScheme = when (mode) {
        ThemeMode.SYSTEM -> if (systemInDark) AidosDarkColorScheme else AidosLightColorScheme
        ThemeMode.LIGHT -> AidosLightColorScheme
        ThemeMode.DARK -> AidosDarkColorScheme
        ThemeMode.OLED -> AidosOledColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AidosTypography.baseTypography,
        content = content,
    )
}
