package com.pocketqc.uv

import com.pocketqc.core.model.Detection

enum class UvSampleResult {
    PASS,
    FLAGGED
}

data class UvSampleRecord(
    val sampleNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val result: UvSampleResult,
    val fluorescenceScore: Float = 0f,
    val exposureTimeMs: Long = 1200L,
    val spotsDetected: Int = 0,
    val confidence: Float = 0f,
    val detections: List<Detection> = emptyList(),
    val modelVersion: String = "aflatoxin_v1"
)

enum class UvInspectionPhase {
    IDLE,
    STARTING,
    UV_ON,
    SCANNING,
    ANALYZING,
    UV_OFF,
    RECORD_RESULT,
    WAITING_15_SECONDS,
    STOPPING,
    COMPLETED
}

data class UvInspectionState(
    val phase: UvInspectionPhase = UvInspectionPhase.IDLE,
    val sampleIndex: Int = 0,
    val currentSampleResult: UvSampleResult? = null,
    val currentSpotsCount: Int = 0,
    val currentConfidence: Float = 0f,
    val currentFluorescenceScore: Float = 0f,
    val remainingWaitSeconds: Int = 0,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val flaggedCount: Int = 0,
    val sampleRecords: List<UvSampleRecord> = emptyList(),
    val batchId: String = "",
    val startTime: Long = 0L,
    val isLightOn: Boolean = false,
    val hardwareStatus: UvHardwareStatus = UvHardwareStatus.NotConnected(),
    val liveDetections: List<Detection> = emptyList(),
    val inferenceFps: Float = 0f,
    val inferenceLatencyMs: Long = 0L,
    val isCameraActive: Boolean = false,
    val errorMessage: String? = null
) {
    val flagRate: Float
        get() = if (totalInspected > 0) (flaggedCount.toFloat() / totalInspected.toFloat()) * 100f else 0f
}

