package com.pocketqc.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pocket QC Palette definition for dynamic theme switching (Dark & Light modes).
 */
data class PocketQcColors(
    val background: Color,
    val surface: Color,
    val surfaceBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val brandContainer: Color,
    val isDark: Boolean
)

val DarkPocketQcColors = PocketQcColors(
    background = Color(0xFF101214),
    surface = Color(0xFF232327),
    surfaceBorder = Color(0xFF2E3238),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF9EACB9),
    brandContainer = Color(0xFF12284C),
    isDark = true
)

val LightPocketQcColors = PocketQcColors(
    background = Color(0xFFF1F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceBorder = Color(0xFFDDE3EA),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF64748B),
    brandContainer = Color(0xFFDCEBFE),
    isDark = false
)

val LocalPocketQcColors = staticCompositionLocalOf { DarkPocketQcColors }

// Dynamic theme-aware color properties that automatically reflect active Light/Dark/System theme
val ObsidianBg: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.background

val CardSurfaceDark: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.surface

val CardSurfaceBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.surfaceBorder

val TextWhite: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.textPrimary

val TextGrey: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.textSecondary

val BrandBlueContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalPocketQcColors.current.brandContainer

val CardSelectedBorder = Color(0xFF2072FE) // Selected mode card highlight

// Brand Electric Blue Accent
val BrandBluePrimary = Color(0xFF2072FE)
val BrandBlueLight = Color(0xFF4B8DFF)
val BrandBlueDark = Color(0xFF1358D4)

// Metadata & status colors
val TextMuted = Color(0xFF657382)

// QC Status Verdict Indicators
val StatusPass = Color(0xFF10B981)
val StatusPassBg = Color(0xFF063323)
val StatusPassBorder = Color(0xFF059669)

val StatusFail = Color(0xFFEF4444)
val StatusFailBg = Color(0xFF3B0D0D)
val StatusFailBorder = Color(0xFFDC2626)

val StatusWarning = Color(0xFFF59E0B)
val StatusWarningBg = Color(0xFF381E05)
val StatusWarningBorder = Color(0xFFD97706)
