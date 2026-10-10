package com.pocketqc.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class PocketQcSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val appLanguage: String = "en", // "en", "te", "hi", "kn"
    val ttsEnabled: Boolean = true,
    val ttsLanguage: String = "en", // "en", "te", "hi", "kn"
    val vibrationEnabled: Boolean = true,
    val confidenceThreshold: Float = 0.25f,
    val sampleIntervalSeconds: Int = 15,
    val bottleIntervalSeconds: Int = 3
)

class SettingsManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences("pocketqc_settings", Context.MODE_PRIVATE)
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<PocketQcSettings> = _settings.asStateFlow()

    private fun loadSettings(): PocketQcSettings {
        val themeStr = prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        val theme = runCatching { AppThemeMode.valueOf(themeStr) }.getOrDefault(AppThemeMode.DARK)

        return PocketQcSettings(
            themeMode = theme,
            appLanguage = prefs.getString("app_language", "en") ?: "en",
            ttsEnabled = prefs.getBoolean("tts_enabled", true),
            ttsLanguage = prefs.getString("tts_language", "en") ?: "en",
            vibrationEnabled = prefs.getBoolean("vibration_enabled", true),
            confidenceThreshold = prefs.getFloat("confidence_threshold", 0.25f),
            sampleIntervalSeconds = prefs.getInt("sample_interval_sec", 15),
            bottleIntervalSeconds = prefs.getInt("bottle_interval_sec", 3)
        )
    }

    fun updateTheme(themeMode: AppThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun updateAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _settings.value = _settings.value.copy(appLanguage = lang)
    }

    fun updateTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_enabled", enabled).apply()
        _settings.value = _settings.value.copy(ttsEnabled = enabled)
    }

    fun updateTtsLanguage(lang: String) {
        prefs.edit().putString("tts_language", lang).apply()
        _settings.value = _settings.value.copy(ttsLanguage = lang)
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vibration_enabled", enabled).apply()
        _settings.value = _settings.value.copy(vibrationEnabled = enabled)
    }

    fun updateConfidenceThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.1f, 0.9f)
        prefs.edit().putFloat("confidence_threshold", clamped).apply()
        _settings.value = _settings.value.copy(confidenceThreshold = clamped)
    }

    fun updateBottleInterval(seconds: Int) {
        val clamped = seconds.coerceIn(1, 10)
        prefs.edit().putInt("bottle_interval_sec", clamped).apply()
        _settings.value = _settings.value.copy(bottleIntervalSeconds = clamped)
    }

    fun updateSampleInterval(seconds: Int) {
        val clamped = seconds.coerceIn(3, 60)
        prefs.edit().putInt("sample_interval_sec", clamped).apply()
        _settings.value = _settings.value.copy(sampleIntervalSeconds = clamped)
    }
}
