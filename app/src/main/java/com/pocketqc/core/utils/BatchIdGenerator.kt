package com.pocketqc.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generates collision-safe industrial batch identifiers in format:
 * PQ-YYYYMMDD-XXXX (e.g., PQ-20261007-0001)
 */
object BatchIdGenerator {

    private val counter = AtomicInteger(1)

    fun generate(prefix: String = "PQ", timestamp: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        val datePart = sdf.format(Date(timestamp))
        val countPart = String.format(Locale.US, "%04d", counter.getAndIncrement())
        return "$prefix-$datePart-$countPart"
    }

    fun resetCounter(initialValue: Int = 1) {
        counter.set(initialValue)
    }
}
