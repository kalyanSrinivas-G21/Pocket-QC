package com.pocketqc

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloInferenceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticAflatoxinYoloAndroidTest {

    @Test
    fun testRealAflatoxinStaticInferenceOnQnnNpu() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YoloInferenceEngine(
            context = appContext,
            modelConfig = ModelConfig.AFLATOXIN,
            targetBackend = HardwareBackend.QNN_NPU
        )

        val initResult = engine.initialize()
        assertTrue("Aflatoxin engine must initialize successfully on QNN NPU", initResult.isSuccess)
        assertEquals(HardwareBackend.QNN_NPU, engine.actualBackend)

        val positiveBitmap = appContext.assets.open("test_images/uv_sample_positive.jpg").use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        assertNotNull(positiveBitmap)

        val benchmark = engine.detectBitmapWithBenchmark(positiveBitmap!!)
        println("=== REAL AFLATOXIN STATIC INFERENCE ON QNN HTP NPU ===")
        println("Sample: uv_sample_positive.jpg")
        println("Latency: Total=${benchmark.totalTimeMs}ms (Pre=${benchmark.preprocessTimeMs}ms, Infer=${benchmark.inferenceTimeMs}ms, Post=${benchmark.postprocessTimeMs}ms) [Backend=${benchmark.backend}]")
        println("Detections count: ${benchmark.detections.size}")
        for (d in benchmark.detections) {
            println("  Detection: ${d.className} (conf: ${d.confidence}), box: ${d.boundingBox}")
        }

        positiveBitmap.recycle()
        engine.release()

        assertEquals(HardwareBackend.QNN_NPU, benchmark.backend)
        assertTrue("Aflatoxin inference must be accelerated on HTP NPU (<35ms)", benchmark.inferenceTimeMs < 35)
        assertTrue("Aflatoxin model must produce detections on positive sample", benchmark.detections.isNotEmpty())
    }
}
