package com.pocketqc.volume3d

/**
 * Inspection record for an individual container/bottle sample.
 */
data class BottleRecord(
    val bottleNumber: Int,
    val requiredFillPercent: Float,
    val detectedFillPercent: Float,
    val differencePercent: Float,
    val isPass: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MeasurementStatus = if (isPass) MeasurementStatus.PASS else MeasurementStatus.OUT_OF_TOLERANCE,
    val measuredHeightMm: Float? = null,
    val estimatedVolumeMl: Float? = null,
    val diagnostics: String = ""
)

enum class Volume3dPhase {
    IDLE,
    STARTING,
    ANALYZING_BOTTLE,
    BOTTLE_RESULT,
    WAITING_NEXT_BOTTLE,
    STOPPING,
    COMPLETED
}

/**
 * Live state model for the container fill-level inspection pipeline.
 */
data class Volume3dInspectionState(
    val phase: Volume3dPhase = Volume3dPhase.IDLE,
    val bottleIndex: Int = 0,
    val requiredFillPercent: Float = 80.0f,
    val tolerancePercent: Float = 3.0f,
    val latestResult: VolumeAnalysisResult? = null,
    val remainingWaitSeconds: Int = 0,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val failedCount: Int = 0,
    val inconclusiveCount: Int = 0,
    val records: List<BottleRecord> = emptyList(),
    val batchId: String = "",
    val startTime: Long = 0L,
    val selectedProfile: ContainerProfile = ContainerProfile.CYLINDER_500ML,
    val calibrationRoi: CalibrationRoi = CalibrationRoi.DEFAULT
) {
    val averageDetectedFillPercent: Float
        get() = if (records.isNotEmpty()) {
            val valid = records.filter { it.status == MeasurementStatus.PASS || it.status == MeasurementStatus.OUT_OF_TOLERANCE }
            if (valid.isNotEmpty()) valid.map { it.detectedFillPercent }.average().toFloat() else 0f
        } else {
            0f
        }

    val passRate: Float
        get() = if (totalInspected > 0) (passedCount.toFloat() / totalInspected.toFloat()) * 100f else 0f
}
