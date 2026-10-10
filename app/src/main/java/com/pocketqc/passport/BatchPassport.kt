package com.pocketqc.passport

/**
 * Digital Quality Passport representing an offline-verifiable quality certificate
 * for food processing MSMEs. Contains summary statistics and a verifiable QR payload.
 */
data class BatchPassport(
    val passportId: String,
    val batchId: String,
    val productName: String,
    val facilityName: String,
    val inspectionCount: Int,
    val passRate: Float,
    val defectRate: Float,
    val timestamp: Long,
    val verificationHash: String,
    val qrPayload: String
)
