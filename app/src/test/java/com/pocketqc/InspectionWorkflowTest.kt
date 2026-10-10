package com.pocketqc

import com.pocketqc.batch.DefaultInspectionSession
import com.pocketqc.batch.MockBatchManager
import com.pocketqc.batch.SessionLifecycle
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.core.utils.Formatters
import com.pocketqc.inference.MockInferenceEngine
import com.pocketqc.inference.MockInspectionScenario
import com.pocketqc.passport.MockPassportGenerator
import com.pocketqc.ui.components.InspectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InspectionWorkflowTest {

    private lateinit var batchManager: MockBatchManager
    private lateinit var inferenceEngine: MockInferenceEngine
    private lateinit var passportGenerator: MockPassportGenerator
    private lateinit var session: DefaultInspectionSession

    @Before
    fun setup() {
        batchManager = MockBatchManager()
        inferenceEngine = MockInferenceEngine()
        passportGenerator = MockPassportGenerator()
        session = DefaultInspectionSession(batchManager, inferenceEngine)
    }

    @Test
    fun testStartSessionBecomesActiveAndReady() {
        assertEquals(SessionLifecycle.IDLE, session.state.value.lifecycle)
        assertFalse(session.state.value.isReady)

        session.startSession(mode = InspectionMode.DEFECT_SORT_2D)

        assertEquals(SessionLifecycle.READY, session.state.value.lifecycle)
        assertTrue(session.state.value.isReady)
        assertEquals(0, session.state.value.totalInspected)
        assertEquals(0, session.state.value.passedCount)
        assertEquals(0, session.state.value.defectiveCount)
        assertEquals(InspectionMode.DEFECT_SORT_2D, session.state.value.mode)
        assertEquals("Biscuit", session.state.value.productName)
    }

    @Test
    fun testStartSessionWithAlternativeModes() {
        session.startSession(mode = InspectionMode.UV_AFLATOXIN)
        assertEquals(InspectionMode.UV_AFLATOXIN, session.state.value.mode)
        assertEquals("Peanut & Corn Batch", session.state.value.productName)

        session.startSession(mode = InspectionMode.VOLUME_CHECK_3D)
        assertEquals(InspectionMode.VOLUME_CHECK_3D, session.state.value.mode)
        assertEquals("Grain Silo Sample", session.state.value.productName)
    }

    @Test
    fun testSampleProcessingIncrementsTotal() {
        session.startSession()

        // Capture first sample (Sample 1: PASS)
        val captured = session.captureSample()
        assertTrue(captured)
        assertEquals(1, session.state.value.totalInspected)
        assertEquals(1, session.state.value.passedCount)
        assertEquals(0, session.state.value.defectiveCount)
        assertTrue(session.state.value.currentDetections.isEmpty())
        assertEquals(SessionLifecycle.INSPECTING, session.state.value.lifecycle)
    }

    @Test
    fun testPassingSampleIncrementsPassedCount() {
        session.startSession()

        // Samples 1, 2, 3, 4 are PASS
        repeat(4) {
            session.captureSample()
        }

        assertEquals(4, session.state.value.totalInspected)
        assertEquals(4, session.state.value.passedCount)
        assertEquals(0, session.state.value.defectiveCount)
        assertTrue(session.state.value.currentDetections.isEmpty())
    }

    @Test
    fun testDefectiveSampleIncrementsDefectCountAndEmitsDetection() {
        session.startSession()

        // Capture 4 PASS samples
        repeat(4) { session.captureSample() }

        // Capture 5th sample: DEFECT (Broken Edge)
        val captured5 = session.captureSample()
        assertTrue(captured5)
        assertEquals(5, session.state.value.totalInspected)
        assertEquals(4, session.state.value.passedCount)
        assertEquals(1, session.state.value.defectiveCount)

        // Viewport detections updated with defect
        assertEquals(1, session.state.value.currentDetections.size)
        assertEquals("Broken Edge", session.state.value.currentDetections[0].className)
        assertEquals(0.94f, session.state.value.currentDetections[0].confidence, 0.001f)
    }

    @Test
    fun testComplete24SampleInspectionScenarioCalculations() {
        session.startSession()

        // Execute all 24 samples
        repeat(MockInspectionScenario.TOTAL_SAMPLES) {
            session.captureSample()
        }

        assertEquals(24, session.state.value.totalInspected)
        assertEquals(21, session.state.value.passedCount)
        assertEquals(3, session.state.value.defectiveCount)
        assertEquals(0.125f, session.state.value.defectRate, 0.001f)
        assertEquals(0.875f, session.state.value.passRate, 0.001f)

        // Cannot capture past max quota of 24
        val extraCapture = session.captureSample()
        assertFalse(extraCapture)
        assertEquals(24, session.state.value.totalInspected)

        // Complete the inspection
        val result = session.completeInspection()
        assertNotNull(result)
        requireNotNull(result)

        assertEquals("Biscuit", result.productName)
        assertEquals(24, result.totalInspected)
        assertEquals(21, result.passed)
        assertEquals(3, result.failed)
        assertEquals(0.125f, result.defectRate, 0.001f)
        assertEquals(InspectionStatus.PASSED, result.status)
        assertEquals("12.5%", Formatters.formatPercent(result.defectRate))
        assertEquals(SessionLifecycle.COMPLETED, session.state.value.lifecycle)
    }

    @Test
    fun testDefectClassificationCounts() {
        session.startSession()

        repeat(MockInspectionScenario.TOTAL_SAMPLES) {
            session.captureSample()
        }

        val result = session.completeInspection()
        assertNotNull(result)
        requireNotNull(result)

        assertEquals(3, result.detections.size)

        val groups = result.detections.groupBy { it.className }
        assertEquals(2, groups["Broken Edge"]?.size)
        assertEquals(1, groups["Burnt Surface"]?.size)

        // Calculate defect percentages
        val brokenPercent = (groups["Broken Edge"]!!.size.toFloat() / result.totalInspected.toFloat()) * 100f
        val burntPercent = (groups["Burnt Surface"]!!.size.toFloat() / result.totalInspected.toFloat()) * 100f

        assertEquals(8.33f, brokenPercent, 0.1f)
        assertEquals(4.16f, burntPercent, 0.1f)
    }

    @Test
    fun testPassportGenerationFromFinalizedInspectionResult() {
        session.startSession()

        repeat(MockInspectionScenario.TOTAL_SAMPLES) {
            session.captureSample()
        }

        val result = session.completeInspection()
        assertNotNull(result)

        val completedBatch = batchManager.currentBatch.value
        assertNotNull(completedBatch)
        requireNotNull(completedBatch)

        val passport = passportGenerator.generate(completedBatch)
        assertEquals("B-2026-024", passport.batchId)
        assertEquals("Biscuit", passport.productName)
        assertEquals(24, passport.inspectionCount)
        assertEquals(0.875f, passport.passRate, 0.001f)
        assertEquals(0.125f, passport.defectRate, 0.001f)
        assertTrue(passport.verificationHash.isNotEmpty())
        assertTrue(passport.qrPayload.contains("SIG:${passport.verificationHash}"))
    }

    @Test
    fun testDefensiveHandlingEmptyInspectionReturnsNull() {
        session.startSession()

        // Attempting to complete with 0 samples
        val result = session.completeInspection()
        assertNull("Completing inspection with zero samples must return null", result)
        assertEquals(0, session.state.value.totalInspected)
        assertEquals(SessionLifecycle.READY, session.state.value.lifecycle)
    }

    @Test
    fun testRepeatedCompleteInspectionReturnsSameResult() {
        session.startSession()
        repeat(5) { session.captureSample() }

        val result1 = session.completeInspection()
        assertNotNull(result1)

        val result2 = session.completeInspection()
        assertEquals(result1, result2)
    }
}
