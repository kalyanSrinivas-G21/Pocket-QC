package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.batch.PersistentBatchManager
import com.pocketqc.camera.CameraState
import com.pocketqc.camera.MockCameraSource
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.YoloInferenceEngine
import com.pocketqc.tracking.CentroidDetectionTracker
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.viewmodel.Inspection2DViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase2LifecycleAndStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyPersistenceNoBatchesNoCrash() {
        val dir = tempFolder.newFolder("empty_storage_test")
        val manager = PersistentBatchManager(baseDir = dir, fileName = "batches.json")

        // 1. Must legitimately contain 0 batches on clean installation
        val batches = manager.getRecentBatches()
        assertEquals(0, batches.size)

        // 2. Current batch must be null without throwing exception
        assertNull(manager.currentBatch.value)

        // 3. Querying non-existent batch must return null cleanly
        assertNull(manager.getBatchById("PQ-DOES-NOT-EXIST"))

        // 4. Completing batch when none is active returns null cleanly
        assertNull(manager.completeCurrentBatch())
    }

    @Test
    fun testBatchLifecycleStateMachineTransitions() {
        val batchManager = MockBatchManager()
        val viewModel = Inspection2DViewModel(batchManager)

        // STOPPED state initially
        assertFalse(viewModel.uiState.value.isBatchRunning)
        assertNull(viewModel.uiState.value.completedBatch)

        // STOPPED -> RUNNING
        val startResult = viewModel.startBatch()
        assertTrue(startResult)
        assertTrue(viewModel.uiState.value.isBatchRunning)
        assertTrue(viewModel.uiState.value.activeBatchId.isNotBlank())
        assertEquals(0, viewModel.uiState.value.totalInspected)

        // Ingest telemetry with detections
        val mockDetections = listOf(
            Detection(className = "Intact", confidence = 0.95f, boundingBox = BoundingBox(0.2f, 0.2f, 0.5f, 0.5f))
        )
        viewModel.onLiveTelemetryUpdated(mockDetections, fps = 15.0f, latencyMs = 65L)

        assertEquals(1, viewModel.uiState.value.totalInspected)
        assertEquals(1, viewModel.uiState.value.passedCount)
        assertEquals(0, viewModel.uiState.value.defectiveCount)

        // RUNNING -> STOPPED (COMPLETED)
        val completed = viewModel.stopBatch()
        assertNotNull(completed)
        assertFalse(viewModel.uiState.value.isBatchRunning)
        assertEquals(completed, viewModel.uiState.value.completedBatch)
        assertEquals(1, completed?.inspectionCount)
        assertEquals(BatchStatus.APPROVED, completed?.status)

        // Saved to BatchManager
        val saved = batchManager.getBatchById(completed?.batchId ?: "")
        assertNotNull(saved)
        assertEquals(1, saved?.inspectionCount)
    }

    @Test
    fun testInvalidStateTransitionsRejected() {
        val batchManager = MockBatchManager()
        val viewModel = Inspection2DViewModel(batchManager)

        // Invalid: STOPPED -> STOP
        val invalidStop = viewModel.stopBatch()
        assertNull("Stopping an idle session must be rejected safely", invalidStop)
        assertFalse(viewModel.uiState.value.isBatchRunning)

        // Transition to RUNNING
        assertTrue(viewModel.startBatch())
        val firstBatchId = viewModel.uiState.value.activeBatchId

        // Invalid: RUNNING -> START
        val secondStart = viewModel.startBatch()
        assertFalse("Starting an already running session must be rejected", secondStart)
        assertEquals("Active batch ID must not change on rejected start", firstBatchId, viewModel.uiState.value.activeBatchId)
    }

    @Test
    fun testZeroItemBatchHonestRejection() {
        val batchManager = MockBatchManager()
        val viewModel = Inspection2DViewModel(batchManager)

        viewModel.startBatch()
        // Stop batch without any items detected
        val completed = viewModel.stopBatch()

        assertNotNull(completed)
        assertEquals(0, completed?.inspectionCount)
        assertEquals("Zero-item batch must NEVER be approved", BatchStatus.REJECTED, completed?.status)
    }

    @Test
    fun testViewModelStateSurvivesExternalReconnection() {
        val batchManager = MockBatchManager()
        val tracker = CentroidDetectionTracker()
        val viewModel = Inspection2DViewModel(batchManager, tracker)

        viewModel.startBatch()
        val det = listOf(
            Detection(className = "Fracture", confidence = 0.88f, boundingBox = BoundingBox(0.1f, 0.1f, 0.4f, 0.4f))
        )
        viewModel.onLiveTelemetryUpdated(det, 12f, 80L)

        // State holder retains count and state
        val snapshot = viewModel.uiState.value
        assertEquals(1, snapshot.totalInspected)
        assertEquals(1, snapshot.defectiveCount)
        assertEquals(1, snapshot.fractureCount)
        assertTrue(snapshot.isBatchRunning)

        // Simulating second observer reading state flow (e.g. after recomposition)
        val readAgain = viewModel.uiState.value
        assertEquals(snapshot.activeBatchId, readAgain.activeBatchId)
        assertEquals(1, readAgain.totalInspected)
    }

    @Test
    fun testCameraLifecycleStartStopStartCycles() = runBlocking {
        val cameraSource = MockCameraSource()

        assertEquals(CameraState.Idle, cameraSource.cameraState.value)

        cameraSource.start()
        assertEquals(CameraState.Streaming, cameraSource.cameraState.value)

        cameraSource.stop()
        assertEquals(CameraState.Idle, cameraSource.cameraState.value)

        cameraSource.start()
        assertEquals(CameraState.Streaming, cameraSource.cameraState.value)
    }

    @Test
    fun testPersistenceSaveLoadClearLifecycle() {
        val dir = tempFolder.newFolder("persistence_lifecycle_test")
        val manager = PersistentBatchManager(baseDir = dir, fileName = "batches.json")

        assertEquals(0, manager.getRecentBatches().size)

        val batch = Batch(
            batchId = "PQ-PHASE2-001",
            productName = "Crackers",
            inspectionCount = 10,
            passCount = 9,
            failCount = 1,
            defectRate = 0.10f,
            timestamp = System.currentTimeMillis(),
            status = BatchStatus.APPROVED,
            mode = InspectionMode.DEFECT_SORT_2D
        )
        manager.saveBatch(batch)

        assertEquals(1, manager.getRecentBatches().size)
        assertEquals("Crackers", manager.getBatchById("PQ-PHASE2-001")?.productName)

        // Reload from disk with second instance
        val reloaded = PersistentBatchManager(baseDir = dir, fileName = "batches.json")
        assertEquals(1, reloaded.getRecentBatches().size)
        assertEquals("Crackers", reloaded.getBatchById("PQ-PHASE2-001")?.productName)

        // Clear all
        reloaded.clearAllBatches()
        assertEquals(0, reloaded.getRecentBatches().size)
        assertNull(reloaded.currentBatch.value)
    }

    @Test
    fun testModelInitializationFailureProducesErrorState() = runBlocking {
        val corruptBytes = byteArrayOf(0x00, 0x01, 0x02)
        val engine = YoloInferenceEngine(corruptBytes)

        val initResult = engine.initialize()
        assertTrue("Initializing with corrupt bytes must fail cleanly", initResult.isFailure)
        assertFalse("Engine must not report initialized on failure", engine.isInitialized)

        val batchManager = MockBatchManager()
        val viewModel = Inspection2DViewModel(batchManager)
        viewModel.setError(initResult.exceptionOrNull()?.message ?: "Model load failed")

        assertNotNull(viewModel.uiState.value.error)
        assertEquals(0, viewModel.uiState.value.totalInspected)
        assertTrue(viewModel.uiState.value.liveDetections.isEmpty())
    }
}
