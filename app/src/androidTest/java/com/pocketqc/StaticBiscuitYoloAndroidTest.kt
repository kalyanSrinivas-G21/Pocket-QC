package com.pocketqc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.StaticModelDiagnostic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticBiscuitYoloAndroidTest {

    @Test
    fun testRealBiscuitStaticInferenceOnQnnNpu() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StaticModelDiagnostic.runDiagnostic(
            context = appContext,
            modelConfig = ModelConfig.BISCUITS_FINAL,
            targetBackend = HardwareBackend.QNN_NPU
        )

        println("=== REAL BISCUIT STATIC INFERENCE ON QNN HTP NPU ===")
        println("Model: ${report.modelName}")
        println("Total tested: ${report.totalSamplesTested}")
        println("Success count: ${report.successfulInferences}")
        println("Average inference: ${report.averageInferenceMs} ms")

        for (r in report.results) {
            println("Sample: ${r.assetFileName} | Expected: ${r.expectedClass} | Success: ${r.isSuccess}")
            println("  Latency: Total=${r.benchmark.totalTimeMs}ms (Pre=${r.benchmark.preprocessTimeMs}ms, Infer=${r.benchmark.inferenceTimeMs}ms, Post=${r.benchmark.postprocessTimeMs}ms) [Backend=${r.benchmark.backend}]")
            for (d in r.detections) {
                println("  Detection: ${d.className} (conf: ${d.confidence}), box: ${d.boundingBox}")
            }
        }

        assertEquals(3, report.totalSamplesTested)
        assertTrue("Inference must successfully detect samples on NPU", report.successfulInferences >= 2)
        assertTrue(report.results.all { it.benchmark.backend == HardwareBackend.QNN_NPU })
        assertTrue(report.results.all { it.detections.isNotEmpty() })
        assertTrue("Inference must be accelerated on HTP NPU (<35ms)", report.averageInferenceMs < 35)
    }
}
