package com.pocketqc.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

private const val TAG = "HapticManager"

/**
 * Manages tactile haptic feedback pulses for inspection defect warnings and setting verifications.
 * Respects operator's [SettingsManager.settings.vibrationEnabled] preference.
 */
class HapticManager(
    context: Context,
    private val settingsManager: SettingsManager
) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize vibrator service", e)
        null
    }

    /**
     * Short 45ms crisp tick used when the operator toggles vibration feedback in Settings.
     */
    fun testPulse() {
        if (!settingsManager.settings.value.vibrationEnabled || vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Test vibration failed", e)
        }
    }

    /**
     * Pronounced dual-pulse vibration pattern emitted immediately upon detection of an item defect
     * (e.g. broken biscuit, aflatoxin fluorescent spot, or out-of-tolerance fill level).
     */
    fun vibrateDefect() {
        if (!settingsManager.settings.value.vibrationEnabled || vibrator?.hasVibrator() != true) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 80, 50, 110)
                val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 80, 50, 110), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Defect vibration failed", e)
        }
    }
}
