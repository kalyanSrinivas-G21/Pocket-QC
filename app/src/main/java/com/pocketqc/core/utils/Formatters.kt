package com.pocketqc.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun formatPercent(value: Float): String {
        val percentage = (value * 100f).coerceIn(0f, 100f)
        return String.format(Locale.US, "%.1f%%", percentage)
    }

    fun formatConfidence(confidence: Float): String {
        val percentage = (confidence * 100f).coerceIn(0f, 100f)
        return String.format(Locale.US, "%.0f%%", percentage)
    }

    fun formatTimestamp(epochMillis: Long, pattern: String = "yyyy-MM-dd HH:mm"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(epochMillis))
    }
}
