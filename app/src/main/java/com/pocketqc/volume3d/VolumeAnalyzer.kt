package com.pocketqc.volume3d

import android.graphics.Bitmap

/**
 * Inspection verdict status adhering to strict QC specification criteria.
 */
enum class MeasurementStatus {
    /** Valid measurement within allowed tolerance: |M - T| <= Delta */
    PASS,

    /** Valid measurement outside allowed tolerance: |M - T| > Delta */
    OUT_OF_TOLERANCE,

    /** Low contrast, ambiguous meniscus transition, or occluded lens */
    INCONCLUSIVE,

    /** Frame missing, corrupted, or dimensions invalid */
    ERROR
}

/**
 * Granular inspection measurement result.
 */
data class VolumeAnalysisResult(
    val detectedFillPercent: Float,
    val requiredFillPercent: Float,
    val differencePercent: Float,
    val isPass: Boolean,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MeasurementStatus = if (isPass) MeasurementStatus.PASS else MeasurementStatus.OUT_OF_TOLERANCE,
    val measuredHeightMm: Float? = null,
    val estimatedVolumeMl: Float? = null,
    val meniscusYNormalized: Float = 0f,
    val diagnostics: String = "",
    val containerProfile: ContainerProfile? = null
)

/**
 * Abstraction for container fill-level inspection.
 */
interface VolumeAnalyzer {
    suspend fun analyze(
        topFrame: Bitmap? = null,
        sideFrame: Bitmap? = null,
        requiredFillPercent: Float
    ): VolumeAnalysisResult
}
