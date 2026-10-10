package com.pocketqc.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "PocketQcTts"

/**
 * Event-driven, debounced Text-to-Speech manager.
 * Supports multilingual announcements (English, Telugu, Hindi, Kannada)
 * with verified fallback to English if regional voice packages are not installed.
 */
class TtsManager(
    context: Context,
    private val settingsManager: SettingsManager
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var lastSpokenEvent: String = ""
    private var lastSpokenTimestamp: Long = 0L

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            applyCurrentLanguage()
            Log.i(TAG, "TextToSpeech successfully initialized")
        } else {
            Log.w(TAG, "TextToSpeech initialization failed with code $status")
        }
    }

    fun applyCurrentLanguage() {
        if (!isInitialized) return
        val langCode = settingsManager.settings.value.ttsLanguage
        val targetLocale = when (langCode) {
            "te" -> Locale.forLanguageTag("te-IN")
            "hi" -> Locale.forLanguageTag("hi-IN")
            "kn" -> Locale.forLanguageTag("kn-IN")
            else -> Locale.US
        }

        val available = tts?.isLanguageAvailable(targetLocale)
        if (available == TextToSpeech.LANG_AVAILABLE ||
            available == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            available == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        ) {
            tts?.language = targetLocale
        } else {
            Log.w(TAG, "Locale $targetLocale not available on device. Falling back to English.")
            tts?.language = Locale.US
        }
    }

    private fun getPhrase(
        en: String,
        hi: String,
        te: String,
        kn: String
    ): String {
        return when (settingsManager.settings.value.ttsLanguage) {
            "hi" -> hi
            "te" -> te
            "kn" -> kn
            else -> en
        }
    }

    /**
     * Announces a critical inspection lifecycle event with debouncing.
     */
    fun speakEvent(eventText: String, debounceMs: Long = 1500L) {
        if (!settingsManager.settings.value.ttsEnabled || !isInitialized) return

        val now = System.currentTimeMillis()
        if (eventText == lastSpokenEvent && (now - lastSpokenTimestamp) < debounceMs) {
            return // Debounced
        }

        lastSpokenEvent = eventText
        lastSpokenTimestamp = now

        tts?.speak(eventText, TextToSpeech.QUEUE_FLUSH, null, "qc_event_${now}")
    }

    fun announceBatchStarted() {
        val phrase = getPhrase(
            en = "Batch started",
            hi = "बैच शुरू हुआ",
            te = "బ్యాచ్ ప్రారంభమైంది",
            kn = "ಬ್ಯಾಚ್ ಪ್ರಾರಂಭವಾಯಿತು"
        )
        speakEvent(phrase)
    }

    fun announceDefectDetected() {
        val phrase = getPhrase(
            en = "Defect detected",
            hi = "दोष पाया गया",
            te = "లోపం గుర్తించబడింది",
            kn = "ದೋಷ ಪತ್ತೆಯಾಗಿದೆ"
        )
        speakEvent(phrase)
    }

    fun announceSamplePassed() {
        val phrase = getPhrase(
            en = "Sample passed",
            hi = "नमूना पास",
            te = "నమూనా పాస్ అయింది",
            kn = "ಮಾದರಿ ಪಾಸ್ ಆಗಿದೆ"
        )
        speakEvent(phrase)
    }

    fun announceSampleFlagged() {
        val phrase = getPhrase(
            en = "Sample flagged",
            hi = "नमूना फ्लैग किया गया",
            te = "నమూనా ఫ్లాగ్ చేయబడింది",
            kn = "ಮಾದರಿ ಫ್ಲ್ಯಾಗ್ ಮಾಡಲಾಗಿದೆ"
        )
        speakEvent(phrase)
    }

    fun announceBatchCompleted(isPass: Boolean) {
        val phrase = when (settingsManager.settings.value.ttsLanguage) {
            "hi" -> "बैच पूरा हुआ। परिणाम: " + if (isPass) "पास" else "अस्वीकृत"
            "te" -> "బ్యాచ్ పూర్తయింది. ఫలితం: " + if (isPass) "పాస్" else "తిరస్కరించబడింది"
            "kn" -> "ಬ್ಯಾಚ್ ಪೂರ್ಣಗೊಂಡಿದೆ. ಫಲಿತಾಂಶ: " + if (isPass) "ಪಾಸ್" else "ತಿರಸ್ಕರಿಸಲಾಗಿದೆ"
            else -> "Batch completed. Result: " + if (isPass) "Pass" else "Reject"
        }
        speakEvent(phrase)
    }

    fun announceLanguageChanged(langCode: String) {
        applyCurrentLanguage()
        val phrase = when (langCode) {
            "hi" -> "हिन्दी चुनी गई"
            "te" -> "తెలుగు ఎంపిక చేయబడింది"
            "kn" -> "ಕನ್ನಡ ಆಯ್ಕೆ ಮಾಡಲಾಗಿದೆ"
            else -> "English selected"
        }
        speakEvent(phrase, debounceMs = 0L)
    }

    fun announceTtsEnabled() {
        val phrase = getPhrase(
            en = "Voice announcements enabled",
            hi = "वॉयस घोषणाएं सक्षम की गईं",
            te = "వాయిస్ ప్రకటనలు ప్రారంభించబడ్డాయి",
            kn = "ಧ್ವನಿ ಪ್ರಕಟಣೆಗಳನ್ನು ಸಕ್ರಿಯಗೊಳಿಸಲಾಗಿದೆ"
        )
        speakEvent(phrase, debounceMs = 0L)
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
            isInitialized = false
        }
    }
}
