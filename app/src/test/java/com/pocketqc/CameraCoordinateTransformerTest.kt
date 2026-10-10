package com.pocketqc

import com.pocketqc.camera.CameraCoordinateTransformer
import com.pocketqc.core.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCoordinateTransformerTest {

    @Test
    fun testSameAspectRatioIdentityMapping() {
        // Camera 4:3 (640x480) to Viewport 4:3 (960x720)
        val originalBox = BoundingBox(left = 0.2f, top = 0.3f, right = 0.7f, bottom = 0.8f)
        val transformed = CameraCoordinateTransformer.transformToPreview(
            box = originalBox,
            camWidth = 640,
            camHeight = 480,
            viewWidth = 960f,
            viewHeight = 720f
        )

        assertEquals(originalBox.left, transformed.left, 0.001f)
        assertEquals(originalBox.top, transformed.top, 0.001f)
        assertEquals(originalBox.right, transformed.right, 0.001f)
        assertEquals(originalBox.bottom, transformed.bottom, 0.001f)
    }

    @Test
    fun testNarrowViewportCropsHorizontally() {
        // Camera 4:3 (640x480) mapped to tall/narrow phone viewport (480x960)
        // Cam aspect: 640/480 = 1.33. View aspect: 480/960 = 0.5.
        // Scale is determined by viewHeight / camHeight = 960 / 480 = 2.0.
        // Scaled cam width = 640 * 2.0 = 1280.
        // dx = (480 - 1280) / 2 = -400px.
        val centerBox = BoundingBox(left = 0.45f, top = 0.4f, right = 0.55f, bottom = 0.6f)
        val transformed = CameraCoordinateTransformer.transformToPreview(
            box = centerBox,
            camWidth = 640,
            camHeight = 480,
            viewWidth = 480f,
            viewHeight = 960f
        )

        // Center x should remain centered around 0.5 in view space
        val centerTransformedX = (transformed.left + transformed.right) / 2f
        assertEquals(0.5f, centerTransformedX, 0.05f)
        // Y coordinates should scale directly without vertical shift
        assertEquals(0.4f, transformed.top, 0.001f)
        assertEquals(0.6f, transformed.bottom, 0.001f)
    }

    @Test
    fun testClampingWithinNormalizedBounds() {
        // Extreme coordinates outside view bounds due to center crop should be clamped to [0.0, 1.0]
        val extremeBox = BoundingBox(left = 0.01f, top = 0.01f, right = 0.1f, bottom = 0.2f)
        val transformed = CameraCoordinateTransformer.transformToPreview(
            box = extremeBox,
            camWidth = 640,
            camHeight = 480,
            viewWidth = 360f,
            viewHeight = 900f
        )

        assertTrue(transformed.left in 0f..1f)
        assertTrue(transformed.top in 0f..1f)
        assertTrue(transformed.right in 0f..1f)
        assertTrue(transformed.bottom in 0f..1f)
    }

    @Test
    fun testDegenerateInputsReturnOriginalBox() {
        val original = BoundingBox(0.1f, 0.2f, 0.8f, 0.9f)
        val zeroWidth = CameraCoordinateTransformer.transformToPreview(original, 0, 480, 1080f, 1920f)
        assertEquals(original, zeroWidth)

        val zeroView = CameraCoordinateTransformer.transformToPreview(original, 640, 480, 0f, 0f)
        assertEquals(original, zeroView)
    }
}
