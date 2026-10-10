package com.pocketqc

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.tracking.CentroidDetectionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CentroidDetectionTrackerTest {

    private lateinit var tracker: CentroidDetectionTracker

    @Before
    fun setUp() {
        tracker = CentroidDetectionTracker(
            maxCentroidDistance = 0.20f,
            disappearanceThresholdMs = 1500L,
            minIouThreshold = 0.25f
        )
    }

    @Test
    fun testInitialState() {
        assertFalse(tracker.isRunning)
        assertEquals(0, tracker.totalInspected)
        assertEquals(0, tracker.passedCount)
        assertEquals(0, tracker.defectiveCount)
        assertEquals(0, tracker.brokenCount)
        assertEquals(0, tracker.fractureCount)
        assertEquals(0, tracker.intactCount)
        assertEquals(0f, tracker.defectRate, 0.001f)
        assertEquals(0f, tracker.passRate, 0.001f)
    }

    @Test
    fun testIgnoreDetectionsWhenNotRunning() {
        val detection = Detection(
            className = "Intact",
            confidence = 0.95f,
            boundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f)
        )
        val newlyCounted = tracker.processFrame(listOf(detection), currentTimeMs = 1000L)
        assertTrue(newlyCounted.isEmpty())
        assertEquals(0, tracker.totalInspected)
    }

    @Test
    fun testStartAndStopBatch() {
        tracker.startBatch()
        assertTrue(tracker.isRunning)

        tracker.stopBatch()
        assertFalse(tracker.isRunning)
    }

    @Test
    fun testDebounceSameObjectAcrossConsecutiveFrames() {
        tracker.startBatch()

        // Frame 1 at t=100ms: Biscuit enters conveyor at center
        val frame1 = listOf(
            Detection("Broken", 0.92f, BoundingBox(0.40f, 0.40f, 0.60f, 0.60f))
        )
        val counted1 = tracker.processFrame(frame1, currentTimeMs = 100L)
        assertEquals(1, counted1.size)
        assertEquals(1, tracker.totalInspected)
        assertEquals(1, tracker.defectiveCount)
        assertEquals(1, tracker.brokenCount)

        // Frame 2 at t=150ms: Slightly moved along conveyor (dx = +0.02, dy = 0)
        val frame2 = listOf(
            Detection("Broken", 0.93f, BoundingBox(0.42f, 0.40f, 0.62f, 0.60f))
        )
        val counted2 = tracker.processFrame(frame2, currentTimeMs = 150L)
        assertEquals(0, counted2.size)
        assertEquals(1, tracker.totalInspected) // STILL 1 (debounced!)

        // Frame 3 at t=200ms: Moved a bit further
        val frame3 = listOf(
            Detection("Broken", 0.91f, BoundingBox(0.45f, 0.40f, 0.65f, 0.60f))
        )
        val counted3 = tracker.processFrame(frame3, currentTimeMs = 200L)
        assertEquals(0, counted3.size)
        assertEquals(1, tracker.totalInspected) // STILL 1

        // Frame 4 at t=250ms
        val frame4 = listOf(
            Detection("Broken", 0.94f, BoundingBox(0.48f, 0.40f, 0.68f, 0.60f))
        )
        val counted4 = tracker.processFrame(frame4, currentTimeMs = 250L)
        assertEquals(0, counted4.size)
        assertEquals(1, tracker.totalInspected) // STILL 1
    }

    @Test
    fun testMultipleDistinctItemsCountedIndividually() {
        tracker.startBatch()

        // Frame 1: Two separate biscuits in the field of view
        val frame1 = listOf(
            Detection("Intact", 0.96f, BoundingBox(0.10f, 0.10f, 0.30f, 0.30f)),
            Detection("Broken", 0.88f, BoundingBox(0.70f, 0.70f, 0.90f, 0.90f))
        )
        val counted1 = tracker.processFrame(frame1, currentTimeMs = 1000L)
        assertEquals(2, counted1.size)
        assertEquals(2, tracker.totalInspected)
        assertEquals(1, tracker.passedCount)
        assertEquals(1, tracker.defectiveCount)
        assertEquals(1, tracker.brokenCount)

        // Frame 2: Same two biscuits still in view slightly displaced
        val frame2 = listOf(
            Detection("Intact", 0.96f, BoundingBox(0.12f, 0.10f, 0.32f, 0.30f)),
            Detection("Broken", 0.89f, BoundingBox(0.72f, 0.70f, 0.92f, 0.90f))
        )
        val counted2 = tracker.processFrame(frame2, currentTimeMs = 1050L)
        assertEquals(0, counted2.size)
        assertEquals(2, tracker.totalInspected) // No double counting

        // Frame 3: Third biscuit arrives at bottom left
        val frame3 = listOf(
            Detection("Intact", 0.96f, BoundingBox(0.14f, 0.10f, 0.34f, 0.30f)),
            Detection("Broken", 0.89f, BoundingBox(0.74f, 0.70f, 0.94f, 0.90f)),
            Detection("Fracture", 0.85f, BoundingBox(0.10f, 0.70f, 0.30f, 0.90f))
        )
        val counted3 = tracker.processFrame(frame3, currentTimeMs = 1100L)
        assertEquals(1, counted3.size) // Only the new fracture biscuit is counted
        assertEquals(3, tracker.totalInspected)
        assertEquals(1, tracker.passedCount)
        assertEquals(2, tracker.defectiveCount)
        assertEquals(1, tracker.brokenCount)
        assertEquals(1, tracker.fractureCount)
    }

    @Test
    fun testClassificationBreakdownAndDefectRateCalculation() {
        tracker.startBatch()

        // 1 Broken, 1 Fracture, 2 Intact
        val d1 = Detection("Broken", 0.90f, BoundingBox(0.0f, 0.0f, 0.1f, 0.1f))
        val d2 = Detection("Fracture", 0.88f, BoundingBox(0.3f, 0.0f, 0.4f, 0.1f))
        val d3 = Detection("Intact", 0.95f, BoundingBox(0.6f, 0.0f, 0.7f, 0.1f))
        val d4 = Detection("Intact", 0.92f, BoundingBox(0.9f, 0.0f, 1.0f, 0.1f))

        tracker.processFrame(listOf(d1, d2, d3, d4), currentTimeMs = 1000L)

        assertEquals(4, tracker.totalInspected)
        assertEquals(2, tracker.passedCount)
        assertEquals(2, tracker.defectiveCount)
        assertEquals(1, tracker.brokenCount)
        assertEquals(1, tracker.fractureCount)
        assertEquals(2, tracker.intactCount)
        assertEquals(50.0f, tracker.defectRate, 0.001f)
        assertEquals(50.0f, tracker.passRate, 0.001f)
    }

    @Test
    fun testDisappearancePruningAllowsReusingLocationAfterTimeout() {
        tracker.startBatch()

        // Object 1 at center at t=1000ms
        val d1 = Detection("Intact", 0.95f, BoundingBox(0.4f, 0.4f, 0.6f, 0.6f))
        tracker.processFrame(listOf(d1), currentTimeMs = 1000L)
        assertEquals(1, tracker.totalInspected)

        // Nothing seen for 2000ms (> 1500ms disappearance threshold)
        tracker.processFrame(emptyList(), currentTimeMs = 3000L)

        // At t=3500ms, a new item arrives at the center
        val d2 = Detection("Broken", 0.91f, BoundingBox(0.4f, 0.4f, 0.6f, 0.6f))
        val newlyCounted = tracker.processFrame(listOf(d2), currentTimeMs = 3500L)
        assertEquals(1, newlyCounted.size)
        assertEquals(2, tracker.totalInspected)
        assertEquals(1, tracker.brokenCount)
    }
}
