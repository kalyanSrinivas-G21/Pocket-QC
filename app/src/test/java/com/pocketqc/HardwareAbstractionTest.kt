package com.pocketqc

import com.pocketqc.camera.CameraFrame
import com.pocketqc.camera.CameraState
import com.pocketqc.camera.MockCameraSource
import com.pocketqc.cv.BoundingBoxUtils
import com.pocketqc.cv.DefaultImagePreprocessor
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.MockInferenceEngine
import com.pocketqc.sensors.MockSensorProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareAbstractionTest {

    @Test
    fun testMockCameraSourceLifecycle() = runBlocking {
        val camera = MockCameraSource()
        assertEquals(CameraState.Idle, camera.cameraState.value)

        camera.start()
        assertEquals(CameraState.Streaming, camera.cameraState.value)
        assertNotNull(camera.frameFlow.value)
        assertEquals(1920, camera.frameFlow.value?.width)
        assertEquals(1080, camera.frameFlow.value?.height)

        camera.stop()
        assertEquals(CameraState.Idle, camera.cameraState.value)
    }

    @Test
    fun testMockInferenceEngineProducesDetections() = runBlocking {
        val engine = MockInferenceEngine(HardwareBackend.CPU)
        assertTrue(engine.initialize().isSuccess)
        assertTrue(engine.isInitialized)

        // Frame 1 corresponds to Scenario Sample 1 (clean pass)
        val passFrame = CameraFrame(1L, 1920, 1080, System.nanoTime())
        assertTrue(engine.detect(passFrame).isEmpty())

        // Frame 5 corresponds to Scenario Sample 5 (first defect: Broken Edge)
        val defectFrame = CameraFrame(5L, 1920, 1080, System.nanoTime())
        val detections = engine.detect(defectFrame)

        assertTrue(detections.isNotEmpty())
        assertEquals("Broken Edge", detections[0].className)
        assertTrue(detections[0].confidence > 0.9f)
    }

    @Test
    fun testMockSensorProviderStabilityAndLighting() {
        val sensors = MockSensorProvider()
        assertTrue(sensors.stabilityState.value.isStable)
        assertTrue(sensors.lightingState.value.isAdequate)
        assertEquals(520f, sensors.lightingState.value.illuminanceLux, 0.1f)

        sensors.updateStability(isStable = false, jitter = 0.5f)
        assertEquals(false, sensors.stabilityState.value.isStable)
    }

    @Test
    fun testBoundingBoxIoUCalculation() {
        val boxA = BoundingBox(0f, 0f, 10f, 10f)
        val boxB = BoundingBox(0f, 0f, 10f, 10f)
        val iouIdentical = BoundingBoxUtils.computeIoU(boxA, boxB)
        assertEquals(1.0f, iouIdentical, 0.001f)

        val boxC = BoundingBox(20f, 20f, 30f, 30f)
        val iouDisjoint = BoundingBoxUtils.computeIoU(boxA, boxC)
        assertEquals(0.0f, iouDisjoint, 0.001f)
    }

    @Test
    fun testImagePreprocessorNormalize() {
        val preprocessor = DefaultImagePreprocessor()
        val raw = byteArrayOf(0, 127.toByte(), 255.toByte())
        val normalized = preprocessor.normalize(raw)

        assertEquals(3, normalized.size)
        assertEquals(0f, normalized[0], 0.001f)
        assertEquals(1.0f, normalized[2], 0.01f)
    }
}
