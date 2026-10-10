package com.pocketqc.core.model

/**
 * Encapsulates the quantitative outcome of an individual inspection session or batch cycle.
 */
data class InspectionResult(
    val productName: String,
    val totalInspected: Int,
    val passed: Int,
    val failed: Int,
    val defectRate: Float,
    val timestamp: Long,
    val status: InspectionStatus,
    val detections: List<Detection> = emptyList()
) {
    init {
        require(totalInspected >= 0) { "totalInspected must not be negative" }
        require(passed >= 0) { "passed must not be negative" }
        require(failed >= 0) { "failed must not be negative" }
        require(passed + failed == totalInspected) {
            "Sum of passed ($passed) and failed ($failed) must equal totalInspected ($totalInspected)"
        }
    }
}
