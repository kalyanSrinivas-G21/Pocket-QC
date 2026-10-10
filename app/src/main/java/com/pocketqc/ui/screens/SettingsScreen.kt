package com.pocketqc.ui.screens

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.settings.AppThemeMode
import com.pocketqc.settings.HapticManager
import com.pocketqc.settings.SettingsManager
import com.pocketqc.settings.TtsManager
import com.pocketqc.ui.theme.BrandBlueContainer
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite
import java.util.Locale

/**
 * Settings Screen:
 * Provides operator options for Appearance (Light/Dark/System), Language (English/Telugu/Hindi/Kannada),
 * Text-to-Speech announcements, Vibration haptic feedback, and detection parameters.
 */
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    ttsManager: TtsManager? = null,
    hapticManager: HapticManager? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by settingsManager.settings.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("Operator ") }
            withStyle(SpanStyle(color = BrandBluePrimary)) { append("Settings") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Operator Settings",
            annotatedTitle = annotatedTitle,
            subtitle = "Appearance, Voice Announcements & Parameters",
            onNavigateBack = onNavigateBack
        )

        // Section: Appearance Theme
        SettingsSectionCard(title = "APPEARANCE") {
            Text(text = "Theme Selection", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = settings.themeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) BrandBlueContainer else CardSurfaceDark)
                            .border(1.dp, if (isSelected) BrandBluePrimary else CardSurfaceBorder, RoundedCornerShape(8.dp))
                            .clickable { settingsManager.updateTheme(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (mode) {
                                AppThemeMode.SYSTEM -> "System"
                                AppThemeMode.LIGHT -> "Light"
                                AppThemeMode.DARK -> "Dark"
                            },
                            color = if (isSelected) BrandBlueLight else TextGrey,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // Section: Application Language
        SettingsSectionCard(title = "APPLICATION LANGUAGE") {
            val languages = listOf(
                "en" to "English",
                "te" to "తెలుగు (Telugu)",
                "hi" to "हिन्दी (Hindi)",
                "kn" to "ಕನ್ನಡ (Kannada)"
            )
            languages.forEach { (code, name) ->
                val isSelected = settings.appLanguage == code
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) BrandBlueContainer else Color.Transparent)
                        .clickable {
                            settingsManager.updateAppLanguage(code)
                            settingsManager.updateTtsLanguage(code)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching {
                                    val localeManager = context.getSystemService(LocaleManager::class.java)
                                    localeManager?.applicationLocales = LocaleList.forLanguageTags(code)
                                }
                            }
                            ttsManager?.announceLanguageChanged(code)
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        color = if (isSelected) BrandBlueLight else TextWhite,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (isSelected) {
                        Text(text = "✓", color = BrandBluePrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section: Text-to-Speech (TTS)
        SettingsSectionCard(title = "VOICE ANNOUNCEMENTS (TTS)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(text = "Text-to-Speech Announcements", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Speaks batch start, defect, and complete events", color = TextGrey, style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = settings.ttsEnabled,
                    onCheckedChange = {
                        settingsManager.updateTtsEnabled(it)
                        if (it) {
                            ttsManager?.announceTtsEnabled()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = BrandBluePrimary
                    )
                )
            }

            if (settings.ttsEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "TTS Voice Language", color = TextWhite, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en" to "EN", "te" to "TE", "hi" to "HI", "kn" to "KN").forEach { (code, label) ->
                        val isSelected = settings.ttsLanguage == code
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) BrandBlueContainer else CardSurfaceDark)
                                .border(1.dp, if (isSelected) BrandBluePrimary else CardSurfaceBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                settingsManager.updateTtsLanguage(code)
                                ttsManager?.announceLanguageChanged(code)
                            }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = label, color = if (isSelected) BrandBlueLight else TextGrey, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Section: Inspection Parameters
        SettingsSectionCard(title = "INSPECTION PARAMETERS") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(text = "Haptic Vibration Feedback", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Vibrates upon defect classification", color = TextGrey, style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = settings.vibrationEnabled,
                    onCheckedChange = {
                        settingsManager.updateVibrationEnabled(it)
                        if (it) {
                            hapticManager?.testPulse()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextWhite,
                        checkedTrackColor = BrandBluePrimary
                    )
                )
            }

            if (settings.vibrationEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandBlueContainer)
                        .border(1.dp, BrandBluePrimary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { hapticManager?.testPulse() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Test Haptic Pulse",
                        color = BrandBlueLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Parameter 1: Detection Confidence Threshold
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Detection Confidence Threshold", color = TextWhite, style = MaterialTheme.typography.bodySmall)
                    Text(text = String.format(Locale.US, "%.2f", settings.confidenceThreshold), color = BrandBlueLight, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = settings.confidenceThreshold,
                    onValueChange = { settingsManager.updateConfidenceThreshold(it) },
                    valueRange = 0.15f..0.85f,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandBluePrimary,
                        activeTrackColor = BrandBluePrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Parameter 2: 3D Bottle Inspection Interval
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "3D Bottle Inspection Interval", color = TextWhite, style = MaterialTheme.typography.bodySmall)
                    Text(text = "${settings.bottleIntervalSeconds} sec", color = BrandBlueLight, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = settings.bottleIntervalSeconds.toFloat(),
                    onValueChange = { settingsManager.updateBottleInterval(it.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandBluePrimary,
                        activeTrackColor = BrandBluePrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Parameter 3: UV Inter-Sample Interval
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "UV Inter-Sample Interval", color = TextWhite, style = MaterialTheme.typography.bodySmall)
                    Text(text = "${settings.sampleIntervalSeconds} sec", color = BrandBlueLight, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = settings.sampleIntervalSeconds.toFloat(),
                    onValueChange = { settingsManager.updateSampleInterval(it.toInt()) },
                    valueRange = 5f..30f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandBluePrimary,
                        activeTrackColor = BrandBluePrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlueLight,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}
