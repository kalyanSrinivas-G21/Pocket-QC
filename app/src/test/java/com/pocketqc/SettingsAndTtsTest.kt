package com.pocketqc

import android.content.SharedPreferences
import com.pocketqc.settings.AppThemeMode
import com.pocketqc.settings.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsAndTtsTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        settingsManager = SettingsManager(fakePrefs)
    }

    @Test
    fun testDefaultSettings() {
        val s = settingsManager.settings.value
        assertEquals(AppThemeMode.DARK, s.themeMode)
        assertEquals("en", s.appLanguage)
        assertTrue(s.ttsEnabled)
        assertEquals("en", s.ttsLanguage)
        assertTrue(s.vibrationEnabled)
        assertEquals(0.25f, s.confidenceThreshold, 0.001f)
        assertEquals(15, s.sampleIntervalSeconds)
        assertEquals(3, s.bottleIntervalSeconds)
    }

    @Test
    fun testUpdateTheme() {
        settingsManager.updateTheme(AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, settingsManager.settings.value.themeMode)
        assertEquals("LIGHT", fakePrefs.getString("theme_mode", null))

        settingsManager.updateTheme(AppThemeMode.SYSTEM)
        assertEquals(AppThemeMode.SYSTEM, settingsManager.settings.value.themeMode)
        assertEquals("SYSTEM", fakePrefs.getString("theme_mode", null))
    }

    @Test
    fun testUpdateLanguage() {
        settingsManager.updateAppLanguage("te")
        assertEquals("te", settingsManager.settings.value.appLanguage)
        assertEquals("te", fakePrefs.getString("app_language", null))

        settingsManager.updateTtsLanguage("hi")
        assertEquals("hi", settingsManager.settings.value.ttsLanguage)
        assertEquals("hi", fakePrefs.getString("tts_language", null))
    }

    @Test
    fun testToggleTtsAndVibration() {
        settingsManager.updateTtsEnabled(false)
        assertFalse(settingsManager.settings.value.ttsEnabled)
        assertFalse(fakePrefs.getBoolean("tts_enabled", true))

        settingsManager.updateVibrationEnabled(false)
        assertFalse(settingsManager.settings.value.vibrationEnabled)
        assertFalse(fakePrefs.getBoolean("vibration_enabled", true))
    }

    @Test
    fun testConfidenceThresholdClamping() {
        settingsManager.updateConfidenceThreshold(0.05f) // Below min (0.1)
        assertEquals(0.1f, settingsManager.settings.value.confidenceThreshold, 0.001f)

        settingsManager.updateConfidenceThreshold(0.95f) // Above max (0.9)
        assertEquals(0.9f, settingsManager.settings.value.confidenceThreshold, 0.001f)

        settingsManager.updateConfidenceThreshold(0.40f)
        assertEquals(0.40f, settingsManager.settings.value.confidenceThreshold, 0.001f)
    }

    @Test
    fun testBottleIntervalClamping() {
        settingsManager.updateBottleInterval(0) // Below min (1)
        assertEquals(1, settingsManager.settings.value.bottleIntervalSeconds)

        settingsManager.updateBottleInterval(15) // Above max (10)
        assertEquals(10, settingsManager.settings.value.bottleIntervalSeconds)

        settingsManager.updateBottleInterval(5)
        assertEquals(5, settingsManager.settings.value.bottleIntervalSeconds)
    }

    @Test
    fun testSettingsSurviveNewInstanceOverSamePreferences() {
        settingsManager.updateTheme(AppThemeMode.LIGHT)
        settingsManager.updateAppLanguage("kn")
        settingsManager.updateConfidenceThreshold(0.5f)

        // Instantiate a second SettingsManager using the same prefs
        val reloadedManager = SettingsManager(fakePrefs)
        assertEquals(AppThemeMode.LIGHT, reloadedManager.settings.value.themeMode)
        assertEquals("kn", reloadedManager.settings.value.appLanguage)
        assertEquals(0.5f, reloadedManager.settings.value.confidenceThreshold, 0.001f)
    }
}

/**
 * Lightweight, in-memory fake implementation of SharedPreferences for JVM testing.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val parentData: MutableMap<String, Any>) : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { if (value != null) staging[it] = value else staging[it] = null }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { if (values != null) staging[it] = values else staging[it] = null }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { staging[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { staging[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { staging[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { staging[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { staging[it] = null }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearRequested) {
                parentData.clear()
            }
            staging.forEach { (k, v) ->
                if (v == null) parentData.remove(k) else parentData[k] = v
            }
        }
    }
}
