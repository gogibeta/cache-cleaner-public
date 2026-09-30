package com.cachecleaner.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FD),
    onPrimaryContainer = Color(0xFF0B2F6B),
    secondary = Color(0xFF4A6FA5),
    tertiary = Color(0xFF2E7D32),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B2F6B),
    primaryContainer = Color(0xFF0B2F6B),
    onPrimaryContainer = Color(0xFFDCE8FD),
    secondary = Color(0xFF9DB8DD),
    tertiary = Color(0xFF81C784),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101418),
    surface = Color(0xFF1A2029),
    surfaceVariant = Color(0xFF232B36),
)

@Composable
fun CacheCleanerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Static palette by default: the dynamic scheme performs a synchronous
    // WallpaperManager binder IPC on the main thread during first
    // composition, which stalls startup on slow/wedged devices (the CI
    // emulator ANR'd during launch, run 36647707296). The static schemes
    // above are the app's full palette anyway.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = colorScheme.background.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
