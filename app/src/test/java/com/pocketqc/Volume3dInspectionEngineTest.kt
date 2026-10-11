package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.volume3d.MockVolumeAnalyzer
import com.pocketqc.volume3d.Volume3dInspectionEngine
import com.pocketqc.volume3d.Volume3dPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Volume3dInspectionEngineTest {

    @Test
    fun testInitialStateAndSettingFillLevel() {
        val mockManager = MockBatchManager()
        val engine = Volume3dInspectionEngine(
            volumeAnalyzer = MockVolumeAnalyzer(),
            batchManager = mockManager
        )

        val initial = engine.state.value
        assertEquals(Volume3dPhase.IDLE, initial.phase)
        assertEquals(80f, initial.requiredFillPercent, 0.001f)
        assertEquals(0, initial.totalInspected)

        // Set to 85%
        engine.setRequiredFillLevel(85f)
        assertEquals(85f, engine.state.value.requiredFillPercent, 0.001f)

        // Should clamp to 10..100
        engine.setRequiredFillLevel(5f)
        assertEquals(10f, engine.state.value.requiredFillPercent, 0.001f)
        engine.setRequiredFillLevel(120f)
        assertEquals(100f, engine.state.value.requiredFillPercent, 0.001f)
    }

    @Test
    fun testStartBatchAndAutomatedBottleCycle() = runBlocking {
        val mockManager = MockBatchManager()
        val engine = Volume3dInspectionEngine(
            volumeAnalyzer = MockVolumeAnalyzer(tolerancePercent = 3.0f),
            batchManager = mockManager,
            scope = CoroutineScope(Dispatchers.Default),
            interBottleWaitSeconds = 0
        )

        val batchId = engine.startBatch(requiredFillPercent = 80f, productName = "Bottled Juice 350ml")
        assertTrue(batchId.startsWith("PQ-"))
        assertEquals(batchId, engine.state.value.batchId)

        // Wait briefly for bottle analysis cycle
        var waited = 0
        while (engine.state.value.totalInspected == 0 && waited < 4000) {
            kotlinx.coroutines.delay(100)
            waited += 100
        }

        val state = engine.state.value
        assertTrue("Expected at least 1 bottle inspected", state.totalInspected >= 1)
        assertEquals(state.totalInspected, state.passedCount + state.failedCount)
        assertTrue(state.records.isNotEmpty())

        val firstRecord = state.records.first()
        assertEquals(1, firstRecord.bottleNumber)
        assertEquals(80f, firstRecord.requiredFillPercent, 0.001f)
        assertTrue(firstRecord.detectedFillPercent > 0f)

        // Stop batch
        val completedBatch = engine.stopBatch()
        assertEquals(Volume3dPhase.COMPLETED, engine.state.value.phase)
        assertEquals(batchId, completedBatch.batchId)
        assertEquals(InspectionMode.VOLUME_CHECK_3D, completedBatch.mode)
        assertEquals(80f, completedBatch.requiredFillPercent, 0.001f)
        assertTrue(completedBatch.averageDetectedFillPercent > 0f)
        assertTrue(completedBatch.qrPayload.contains("\"type\":\"3D\""))
        assertTrue(completedBatch.qrPayload.contains(batchId))
    }

    @Test
    fun testMockVolumeAnalyzerTolerance() = runBlocking {
        val analyzer = MockVolumeAnalyzer(tolerancePercent = 3.0f)
        val result = analyzer.analyze(requiredFillPercent = 80f)

        assertEquals(80f, result.requiredFillPercent, 0.001f)
        assertTrue(result.detectedFillPercent in 65f..95f)
        assertNotNull(result.differencePercent)

        // Tolerance check: diff <= 3.0f should match isPass
        val expectedPass = Math.abs(result.differencePercent) <= 3.0f
        assertEquals(expectedPass, result.isPass)
    }

    @Test
    fun testReset() {
        val mockManager = MockBatchManager()
        val engine = Volume3dInspectionEngine(
            volumeAnalyzer = MockVolumeAnalyzer(),
            batchManager = mockManager
        )

        engine.startBatch()
        engine.reset()

        assertEquals(Volume3dPhase.IDLE, engine.state.value.phase)
        assertEquals(0, engine.state.value.totalInspected)
    }

    @Test
    fun testSettingFillLevelAfterBatchCompleted() = runBlocking {
        val mockManager = MockBatchManager()
        val engine = Volume3dInspectionEngine(
            volumeAnalyzer = MockVolumeAnalyzer(),
            batchManager = mockManager,
            scope = CoroutineScope(Dispatchers.Default),
            interBottleWaitSeconds = 0
        )

        engine.startBatch(requiredFillPercent = 80f)
        val completed = engine.stopBatch()
        assertEquals(Volume3dPhase.COMPLETED, engine.state.value.phase)

        // Setting required fill level after batch completed should work and reset phase to IDLE
        engine.setRequiredFillLevel(70f)
        assertEquals(70f, engine.state.value.requiredFillPercent, 0.001f)
        assertEquals(Volume3dPhase.IDLE, engine.state.value.phase)

        // Adjusting container width after batch completed should also work
        engine.setContainerWidthPercent(65f)
        assertEquals(65f, engine.state.value.selectedProfile.containerWidthPercent, 0.001f)
        assertEquals(Volume3dPhase.IDLE, engine.state.value.phase)
    }
}
