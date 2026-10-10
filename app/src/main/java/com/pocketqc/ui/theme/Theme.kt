package com.pocketqc.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.pocketqc.settings.AppThemeMode

private val PocketQcDarkColorScheme = darkColorScheme(
    primary = BrandBluePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12284C),
    onPrimaryContainer = Color.White,
    secondary = BrandBlueLight,
    onSecondary = Color.White,
    background = Color(0xFF101214),
    onBackground = Color.White,
    surface = Color(0xFF232327),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFF9EACB9),
    outline = Color(0xFF2E3238),
    outlineVariant = Color(0xFF2E3238)
)

private val PocketQcLightColorScheme = lightColorScheme(
    primary = BrandBluePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFE),
    onPrimaryContainer = BrandBluePrimary,
    secondary = BrandBlueLight,
    onSecondary = Color.White,
    background = Color(0xFFF1F4F8),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFDDE3EA),
    outlineVariant = Color(0xFFDDE3EA)
)

@Composable
fun PocketQcTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemDark
    }

    val pocketQcColors = if (isDark) DarkPocketQcColors else LightPocketQcColors
    val colorScheme = if (isDark) PocketQcDarkColorScheme else PocketQcLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        @Suppress("DEPRECATION")
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = pocketQcColors.background.toArgb()
                window.navigationBarColor = pocketQcColors.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(LocalPocketQcColors provides pocketQcColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
