package com.pocketqc.core.model

import com.pocketqc.ui.components.InspectionMode

/**
 * Domain entity representing an aggregate quality inspection batch for an MSME processing run.
 */
data class Batch(
    val batchId: String,
    val productName: String,
    val inspectionCount: Int,
    val passCount: Int,
    val failCount: Int,
    val defectRate: Float,
    val timestamp: Long,
    val status: BatchStatus = BatchStatus.IN_PROGRESS,
    val mode: InspectionMode = InspectionMode.DEFECT_SORT_2D,
    val startTime: Long = timestamp,
    val endTime: Long = timestamp,
    // 2D mode specific breakdown
    val brokenCount: Int = 0,
    val fractureCount: Int = 0,
    val intactCount: Int = 0,
    // UV mode specific breakdown
    val flaggedCount: Int = 0,
    // 3D mode specific breakdown
    val requiredFillPercent: Float = 0f,
    val averageDetectedFillPercent: Float = 0f,
    val bottlePassCount: Int = 0,
    val bottleFailCount: Int = 0,
    // Machine-readable QR payload
    val qrPayload: String = ""
) {
    val passRate: Float
        get() = if (inspectionCount > 0) passCount.toFloat() / inspectionCount.toFloat() else 0f
}
