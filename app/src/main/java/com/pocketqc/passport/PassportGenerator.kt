package com.pocketqc.passport

import com.pocketqc.core.model.Batch

/**
 * Architectural abstraction for generating offline-verifiable digital QR Batch Passports.
 */
interface PassportGenerator {
    fun generate(batch: Batch, facilityName: String = "Agri-QC Processing Unit #12"): BatchPassport
}
