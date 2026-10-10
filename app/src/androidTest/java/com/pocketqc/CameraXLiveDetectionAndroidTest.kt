package com.pocketqc

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketqc.batch.DefaultInspectionSession
import com.pocketqc.batch.MockBatchManager
import com.pocketqc.batch.SessionLifecycle
import com.pocketqc.camera.CameraCoordinateTransformer
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.YoloInferenceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraXLiveDetectionAndroidTest {

    @Test
    fun testYoloEngineInitializationOnAndroidDevice() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YoloInferenceEngine(appContext)

        val initResult = engine.initialize()
        assertTrue("YoloInferenceEngine should initialize cleanly", initResult.isSuccess)
        assertTrue("Engine must report isInitialized = true", engine.isInitialized)

        // Run inference on a test 640x480 bitmap simulating a live camera frame
        val testFrameBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val benchmark = engine.detectBitmapWithBenchmark(testFrameBitmap)

        println("=== CAMERAX LIVE INFERENCE EMULATION BENCHMARK ===")
        println("Preprocess latency: ${benchmark.preprocessTimeMs} ms")
        println("ONNX inference latency: ${benchmark.inferenceTimeMs} ms")
        println("Postprocess latency: ${benchmark.postprocessTimeMs} ms")
        println("Total latency: ${benchmark.totalTimeMs} ms")
        println("Detections count: ${benchmark.detections.size}")

        assertTrue("Inference latency should be positive", benchmark.inferenceTimeMs >= 0)
        assertTrue("Preprocess latency should be positive", benchmark.preprocessTimeMs >= 0)
        testFrameBitmap.recycle()
        engine.release()
    }

    @Test
    fun testLiveSessionStateTransitionsWithRealDetections() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val batchManager = MockBatchManager()
        val engine = YoloInferenceEngine(appContext)
        engine.initialize()

        val session = DefaultInspectionSession(batchManager, engine)
        session.startSession()
        session.setRealCameraActive(true)

        assertEquals(SessionLifecycle.READY, session.state.value.lifecycle)
        assertTrue(session.state.value.isRealCameraActive)

        // Simulate incoming live telemetry from CameraX analyzer
        val simulatedDetections = listOf(
            Detection(
                className = "Fracture",
                confidence = 0.89f,
                boundingBox = CameraCoordinateTransformer.transformToPreview(
                    box = BoundingBox(0.2f, 0.2f, 0.6f, 0.6f),
                    camWidth = 640,
                    camHeight = 480,
                    viewWidth = 1080f,
                    viewHeight = 1440f
                )
            )
        )

        session.updateLiveDetections(
            detections = simulatedDetections,
            fps = 15.2f,
            latencyMs = 34L
        )

        assertEquals(1, session.state.value.liveDetections.size)
        assertEquals(15.2f, session.state.value.inferenceFps, 0.01f)
        assertEquals(34L, session.state.value.inferenceLatencyMs)

        // Capture sample from live stream
        val captured = session.captureSample()
        assertTrue("Sample capture should succeed", captured)
        assertEquals(1, session.state.value.totalInspected)
        assertEquals(1, session.state.value.defectiveCount)
        assertEquals(0, session.state.value.passedCount)

        // Complete inspection
        val result = session.completeInspection()
        assertNotNull(result)
        assertEquals(1, result?.totalInspected)
        assertEquals(1, result?.failed)
        assertEquals(SessionLifecycle.COMPLETED, session.state.value.lifecycle)

        engine.release()
    }
}
