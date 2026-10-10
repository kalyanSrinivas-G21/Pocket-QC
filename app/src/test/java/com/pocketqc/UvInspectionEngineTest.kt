package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.uv.MockUvLightController
import com.pocketqc.uv.UvHardwareStatus
import com.pocketqc.uv.UvInspectionEngine
import com.pocketqc.uv.UvInspectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvInspectionEngineTest {

    @Test
    fun testInitialState() {
        val mockController = MockUvLightController()
        val mockBatchManager = MockBatchManager()
        val engine = UvInspectionEngine(mockController, mockBatchManager)

        val state = engine.state.value
        assertEquals(UvInspectionPhase.IDLE, state.phase)
        assertEquals(0, state.totalInspected)
        assertEquals(0, state.passedCount)
        assertEquals(0, state.flaggedCount)
        assertFalse(state.isLightOn)
        assertTrue(state.hardwareStatus is UvHardwareStatus.NotConnected)
    }

    @Test
    fun testStartBatchGeneratesBatchIdAndInitiatesCycle() = runBlocking {
        val mockController = MockUvLightController()
        val mockBatchManager = MockBatchManager()
        val engine = UvInspectionEngine(
            uvController = mockController,
            batchManager = mockBatchManager,
            scope = CoroutineScope(Dispatchers.Default),
            interSampleWaitSeconds = 0,
            analysisDurationMs = 50L
        )

        val batchId = engine.startBatch(productName = "Guntur Sannam Chilli Lot 4")
        assertTrue(batchId.startsWith("PQ-"))
        assertEquals(batchId, engine.state.value.batchId)

        // Wait brief period for cycle to analyze sample 1
        var waited = 0
        while (engine.state.value.totalInspected == 0 && waited < 4000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }

        val stateAfter = engine.state.value
        assertTrue("Expected at least 1 inspected sample", stateAfter.totalInspected >= 1)
        assertEquals(stateAfter.totalInspected, stateAfter.passedCount + stateAfter.flaggedCount)
        assertTrue(stateAfter.sampleRecords.isNotEmpty())

        val record = stateAfter.sampleRecords.first()
        assertEquals(1, record.sampleNumber)
        assertNotNull(record.result)
        assertTrue(record.fluorescenceScore >= 0f)

        // Stop batch
        val completedBatch = engine.stopBatch()
        assertEquals(UvInspectionPhase.COMPLETED, engine.state.value.phase)
        assertFalse(engine.state.value.isLightOn)
        assertFalse(mockController.isLightOn)

        assertEquals(completedBatch.batchId, batchId)
        assertEquals(InspectionMode.UV_AFLATOXIN, completedBatch.mode)
        assertTrue(completedBatch.qrPayload.contains("\"type\":\"UV\""))
        assertTrue(completedBatch.qrPayload.contains(batchId))
    }

    @Test
    fun testStopBatchProducesApprovedOrRejectedBatch() = runBlocking {
        val mockController = MockUvLightController()
        val mockBatchManager = MockBatchManager()
        val engine = UvInspectionEngine(
            uvController = mockController,
            batchManager = mockBatchManager,
            scope = CoroutineScope(Dispatchers.Default),
            interSampleWaitSeconds = 0,
            analysisDurationMs = 20L
        )

        val batchId = engine.startBatch()
        var waited = 0
        while (engine.state.value.totalInspected == 0 && waited < 3000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }

        val batch = engine.stopBatch()
        if (batch.defectRate <= 0.10f && batch.inspectionCount > 0) {
            assertEquals(BatchStatus.APPROVED, batch.status)
        } else if (batch.inspectionCount > 0) {
            assertEquals(BatchStatus.REJECTED, batch.status)
        }
    }

    @Test
    fun testResetRestoresInitialState() {
        val mockController = MockUvLightController()
        val mockBatchManager = MockBatchManager()
        val engine = UvInspectionEngine(mockController, mockBatchManager)

        engine.startBatch()
        engine.reset()

        val state = engine.state.value
        assertEquals(UvInspectionPhase.IDLE, state.phase)
        assertEquals(0, state.totalInspected)
        assertFalse(state.isLightOn)
    }
}
