package com.pocketqc

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.inference.LetterboxInfo
import com.pocketqc.inference.YoloConfig
import com.pocketqc.inference.YoloPostprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloInferenceUnitTest {

    private val numAnchors = YoloConfig.NUM_ANCHORS
    private val numClasses = YoloConfig.NUM_CLASSES
    private val totalElements = (4 + numClasses) * numAnchors

    private fun createEmptyOutput(): FloatArray {
        return FloatArray(totalElements)
    }

    private fun setCandidate(
        array: FloatArray,
        anchorIdx: Int,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        scoreBroken: Float,
        scoreFracture: Float,
        scoreIntact: Float
    ) {
        array[0 * numAnchors + anchorIdx] = cx
        array[1 * numAnchors + anchorIdx] = cy
        array[2 * numAnchors + anchorIdx] = w
        array[3 * numAnchors + anchorIdx] = h
        array[4 * numAnchors + anchorIdx] = scoreBroken
        array[5 * numAnchors + anchorIdx] = scoreFracture
        array[6 * numAnchors + anchorIdx] = scoreIntact
    }

    @Test
    fun testOutputParsingBrokenDetection() {
        val output = createEmptyOutput()
        setCandidate(
            output,
            anchorIdx = 100,
            cx = 320f,
            cy = 320f,
            w = 200f,
            h = 200f,
            scoreBroken = 0.92f,
            scoreFracture = 0.05f,
            scoreIntact = 0.03f
        )

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = output,
            letterboxInfo = letterbox,
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        assertEquals(1, detections.size)
        val det = detections[0]
        assertEquals("Broken", det.className)
        assertEquals(0.92f, det.confidence, 0.001f)

        // Bounding box: [320 - 100, 320 - 100, 320 + 100, 320 + 100] = [220, 220, 420, 420]
        // Normalized: [220/640, 220/640, 420/640, 420/640]
        assertEquals(220f / 640f, det.boundingBox.left, 0.005f)
        assertEquals(220f / 640f, det.boundingBox.top, 0.005f)
        assertEquals(420f / 640f, det.boundingBox.right, 0.005f)
        assertEquals(420f / 640f, det.boundingBox.bottom, 0.005f)
    }

    @Test
    fun testOutputParsingFractureAndIntactClassification() {
        val output = createEmptyOutput()
        // Anchor 50: Fracture
        setCandidate(
            output,
            anchorIdx = 50,
            cx = 160f,
            cy = 160f,
            w = 80f,
            h = 80f,
            scoreBroken = 0.10f,
            scoreFracture = 0.88f,
            scoreIntact = 0.02f
        )

        // Anchor 200: Intact
        setCandidate(
            output,
            anchorIdx = 200,
            cx = 480f,
            cy = 480f,
            w = 120f,
            h = 120f,
            scoreBroken = 0.01f,
            scoreFracture = 0.03f,
            scoreIntact = 0.95f
        )

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = output,
            letterboxInfo = letterbox,
            confidenceThreshold = 0.25f
        )

        assertEquals(2, detections.size)
        val fractureDet = detections.firstOrNull { it.className == "Fracture" }
        val intactDet = detections.firstOrNull { it.className == "Intact" }

        assertTrue(fractureDet != null)
        assertEquals(0.88f, fractureDet!!.confidence, 0.001f)

        assertTrue(intactDet != null)
        assertEquals(0.95f, intactDet!!.confidence, 0.001f)
    }

    @Test
    fun testConfidenceFilteringDiscardsLowConfidence() {
        val output = createEmptyOutput()
        setCandidate(
            output,
            anchorIdx = 10,
            cx = 320f,
            cy = 320f,
            w = 100f,
            h = 100f,
            scoreBroken = 0.20f, // Below 0.25 threshold
            scoreFracture = 0.15f,
            scoreIntact = 0.10f
        )

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = output,
            letterboxInfo = letterbox,
            confidenceThreshold = 0.25f
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun testNonMaximumSuppressionSuppressesOverlappingBoxesSameClass() {
        val output = createEmptyOutput()

        // Candidate 1: Broken (0.90) at [220, 220, 420, 420]
        setCandidate(
            output,
            anchorIdx = 1,
            cx = 320f,
            cy = 320f,
            w = 200f,
            h = 200f,
            scoreBroken = 0.90f,
            scoreFracture = 0.01f,
            scoreIntact = 0.01f
        )

        // Candidate 2: Broken (0.80) slightly shifted, high IoU > 0.8
        setCandidate(
            output,
            anchorIdx = 2,
            cx = 325f,
            cy = 325f,
            w = 200f,
            h = 200f,
            scoreBroken = 0.80f,
            scoreFracture = 0.01f,
            scoreIntact = 0.01f
        )

        // Candidate 3: Intact (0.85) at same position (different class!)
        setCandidate(
            output,
            anchorIdx = 3,
            cx = 325f,
            cy = 325f,
            w = 200f,
            h = 200f,
            scoreBroken = 0.01f,
            scoreFracture = 0.01f,
            scoreIntact = 0.85f
        )

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = output,
            letterboxInfo = letterbox,
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        // Candidate 2 (Broken 0.80) should be suppressed by Candidate 1 (Broken 0.90).
        // Candidate 3 (Intact 0.85) should NOT be suppressed because it is a different class.
        assertEquals(2, detections.size)

        val brokenDet = detections.firstOrNull { it.className == "Broken" }
        val intactDet = detections.firstOrNull { it.className == "Intact" }

        assertTrue(brokenDet != null)
        assertEquals(0.90f, brokenDet!!.confidence, 0.001f)

        assertTrue(intactDet != null)
        assertEquals(0.85f, intactDet!!.confidence, 0.001f)
    }

    @Test
    fun testLetterboxCoordinateReversal() {
        // Original image 1280x720 (16:9).
        // Letterbox scale = 640 / 1280 = 0.5.
        // Scaled height = 720 * 0.5 = 360.
        // padX = 0f, padY = (640 - 360) / 2 = 140f.
        val letterbox = LetterboxInfo(
            scale = 0.5f,
            padX = 0f,
            padY = 140f,
            origW = 1280,
            origH = 720
        )

        // A point in letterbox space at (320, 320)
        val origX = letterbox.invertX(320f)
        val origY = letterbox.invertY(320f)

        // origX = (320 - 0) / 0.5 = 640
        // origY = (320 - 140) / 0.5 = 360
        assertEquals(640f, origX, 0.1f)
        assertEquals(360f, origY, 0.1f)

        // Test boundary clamping
        val clampedY = letterbox.invertY(50f) // Below padY (140)
        assertEquals(0f, clampedY, 0.1f)
    }
}
