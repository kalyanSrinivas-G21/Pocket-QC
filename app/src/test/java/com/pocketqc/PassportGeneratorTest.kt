package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.passport.MockPassportGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportGeneratorTest {

    @Test
    fun testPassportGenerationDeterministicSignature() {
        val generator = MockPassportGenerator()
        val batch = Batch(
            batchId = "B-2026-001",
            productName = "Premium Cashew",
            inspectionCount = 100,
            passCount = 96,
            failCount = 4,
            defectRate = 0.04f,
            timestamp = 1700000000000L,
            status = BatchStatus.APPROVED
        )

        val passport1 = generator.generate(batch, "Unit #01")
        val passport2 = generator.generate(batch, "Unit #01")

        assertEquals(passport1.verificationHash, passport2.verificationHash)
        assertEquals(passport1.qrPayload, passport2.qrPayload)
        assertTrue(passport1.verificationHash.isNotEmpty())
        assertTrue(passport1.qrPayload.contains("SIG:"))
        assertEquals("B-2026-001", passport1.batchId)
    }

    @Test
    fun testBatchManagerLifecycle() {
        val manager = MockBatchManager()
        val recent = manager.getRecentBatches()
        assertTrue(recent.isNotEmpty())

        val newBatch = manager.startNewBatch("Pepper Grade 1", "B-999")
        assertEquals(0, newBatch.inspectionCount)

        val updated = manager.recordInspection(
            InspectionResult(
                productName = "Pepper Grade 1",
                totalInspected = 20,
                passed = 19,
                failed = 1,
                defectRate = 0.05f,
                timestamp = System.currentTimeMillis(),
                status = InspectionStatus.PASSED
            )
        )

        assertEquals(20, updated.inspectionCount)
        assertEquals(19, updated.passCount)
        assertEquals(1, updated.failCount)

        val completed = manager.completeCurrentBatch()
        assertNotNull(completed)
        assertEquals(BatchStatus.APPROVED, completed?.status)
    }
}
