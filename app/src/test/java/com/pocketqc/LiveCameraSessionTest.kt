package com.pocketqc

import com.pocketqc.batch.DefaultInspectionSession
import com.pocketqc.batch.MockBatchManager
import com.pocketqc.batch.SessionLifecycle
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.inference.MockInferenceEngine
import com.pocketqc.ui.components.InspectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveCameraSessionTest {

    private lateinit var batchManager: MockBatchManager
    private lateinit var session: DefaultInspectionSession

    @Before
    fun setup() {
        batchManager = MockBatchManager()
        session = DefaultInspectionSession(batchManager, MockInferenceEngine())
    }

    @Test
    fun testUpdateLiveDetectionsAndTelemetry() {
        session.startSession(mode = InspectionMode.DEFECT_SORT_2D)
        session.setRealCameraActive(true)

        val liveDetections = listOf(
            Detection(
                className = "Intact",
                confidence = 0.95f,
                boundingBox = BoundingBox(0.2f, 0.2f, 0.6f, 0.6f)
            )
        )

        session.updateLiveDetections(liveDetections, fps = 14.5f, latencyMs = 28L)

        val state = session.state.value
        assertTrue(state.isRealCameraActive)
        assertEquals(1, state.liveDetections.size)
        assertEquals("Intact", state.liveDetections[0].className)
        assertEquals(14.5f, state.inferenceFps, 0.01f)
        assertEquals(28L, state.inferenceLatencyMs)
    }

    @Test
    fun testCaptureSampleWithRealDetectionsIntactPasses() {
        session.startSession()
        session.setRealCameraActive(true)

        val intactDetections = listOf(
            Detection("Intact", 0.92f, BoundingBox(0.1f, 0.1f, 0.5f, 0.5f))
        )
        session.updateLiveDetections(intactDetections, 12f, 30L)

        val captured = session.captureSample()
        assertTrue(captured)

        val state = session.state.value
        assertEquals(1, state.totalInspected)
        assertEquals(1, state.passedCount)
        assertEquals(0, state.defectiveCount)
        assertEquals(0f, state.defectRate, 0.001f)
    }

    @Test
    fun testCaptureSampleWithRealDetectionsBrokenFails() {
        session.startSession()
        session.setRealCameraActive(true)

        val brokenDetections = listOf(
            Detection("Broken", 0.88f, BoundingBox(0.1f, 0.1f, 0.5f, 0.5f))
        )
        session.updateLiveDetections(brokenDetections, 10f, 32L)

        val captured = session.captureSample()
        assertTrue(captured)

        val state = session.state.value
        assertEquals(1, state.totalInspected)
        assertEquals(0, state.passedCount)
        assertEquals(1, state.defectiveCount)
        assertEquals(1.0f, state.defectRate, 0.001f)
    }

    @Test
    fun testCompleteInspectionGeneratesResultWithLiveDetections() {
        session.startSession()
        session.setRealCameraActive(true)

        // Capture 1 Intact, 1 Broken
        session.captureSample(listOf(Detection("Intact", 0.95f, BoundingBox(0.1f, 0.1f, 0.5f, 0.5f))))
        session.captureSample(listOf(Detection("Broken", 0.85f, BoundingBox(0.2f, 0.2f, 0.7f, 0.7f))))

        val result = session.completeInspection()
        assertNotNull(result)
        assertEquals(2, result?.totalInspected)
        assertEquals(1, result?.passed)
        assertEquals(1, result?.failed)
        assertEquals(0.5f, result?.defectRate ?: 0f, 0.001f)
        assertEquals(InspectionStatus.FAILED, result?.status)
        assertEquals(2, result?.detections?.size)
        assertEquals(SessionLifecycle.COMPLETED, session.state.value.lifecycle)
    }
}
