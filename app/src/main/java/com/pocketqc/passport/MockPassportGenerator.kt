package com.pocketqc.passport

import com.pocketqc.core.model.Batch
import java.security.MessageDigest

/**
 * Generates offline verifiable batch passport payloads with SHA-256 tamper-evident checksums.
 * Requires zero network connectivity.
 */
class MockPassportGenerator : PassportGenerator {

    override fun generate(batch: Batch, facilityName: String): BatchPassport {
        val payloadRaw = buildString {
            append("POCKET-QC-V1|")
            append("BATCH:${batch.batchId}|")
            append("PROD:${batch.productName}|")
            append("FACILITY:$facilityName|")
            append("COUNT:${batch.inspectionCount}|")
            append("PASS:${batch.passCount}|")
            append("FAIL:${batch.failCount}|")
            append("RATE:${batch.defectRate}|")
            append("TS:${batch.timestamp}")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(payloadRaw.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }.take(16).uppercase()

        val passportId = "PASS-${batch.batchId}-$hashHex"
        val qrPayload = "$payloadRaw|SIG:$hashHex"

        return BatchPassport(
            passportId = passportId,
            batchId = batch.batchId,
            productName = batch.productName,
            facilityName = facilityName,
            inspectionCount = batch.inspectionCount,
            passRate = batch.passRate,
            defectRate = batch.defectRate,
            timestamp = batch.timestamp,
            verificationHash = hashHex,
            qrPayload = qrPayload
        )
    }
}
