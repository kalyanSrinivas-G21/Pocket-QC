package com.pocketqc

import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.InferenceBackendManager
import com.pocketqc.inference.YoloBenchmarkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HardwareBackendSelectionTest {

    // Dummy class simulating an OrtSession.SessionOptions WITH addQnn method
    private class DummyOptionsWithQnn {
        @Suppress("unused")
        fun addQnn(options: Map<String, String>) {}
    }

    // Dummy class simulating an OrtSession.SessionOptions WITHOUT addQnn method (like onnxruntime-android:1.19.2)
    private class DummyOptionsWithoutQnn {
        @Suppress("unused")
        fun addNnapi() {}
    }

    @Test
    fun testBackendEnumValuesAndAliases() {
        assertEquals(HardwareBackend.QNN_NPU, HardwareBackend.HEXAGON_NPU_QNN)
        assertEquals(HardwareBackend.AUTO, HardwareBackend.GPU)
        val values = HardwareBackend.values()
        assertTrue(values.contains(HardwareBackend.CPU))
        assertTrue(values.contains(HardwareBackend.QNN_NPU))
        assertTrue(values.contains(HardwareBackend.AUTO))
    }

    @Test
    fun testQnnProviderAvailabilityDetection() {
        assertTrue(
            "Dummy with addQnn must report QNN provider available",
            InferenceBackendManager.isQnnExecutionProviderAvailable(DummyOptionsWithQnn::class.java)
        )
        assertFalse(
            "Dummy without addQnn must report QNN provider unavailable",
            InferenceBackendManager.isQnnExecutionProviderAvailable(DummyOptionsWithoutQnn::class.java)
        )
    }

    @Test
    fun testQualcommPlatformDetection() {
        assertTrue(InferenceBackendManager.isQualcommPlatform(hardware = "qcom", board = "sun"))
        assertTrue(InferenceBackendManager.isQualcommPlatform(hardware = "snapdragon", board = "sm8750"))
        assertTrue(InferenceBackendManager.isQualcommPlatform(hardware = "qualcomm", board = "pineapple"))
        assertFalse(InferenceBackendManager.isQualcommPlatform(hardware = "goldfish", board = "ranchu"))
        assertFalse(InferenceBackendManager.isQualcommPlatform(hardware = "exynos", board = "universal9925"))
    }

    @Test
    fun testCpuBackendDirectResolution() {
        val result = InferenceBackendManager.resolveBackend(
            requested = HardwareBackend.CPU,
            sessionOptionsClass = DummyOptionsWithoutQnn::class.java,
            hardware = "qcom",
            board = "sm8750"
        )
        assertEquals(HardwareBackend.CPU, result.backend)
        assertFalse(result.isAccelerator)
        assertTrue(result.description.contains("CPU"))
    }

    @Test
    fun testStrictQnnNpuFailureWhenProviderUnavailable() {
        try {
            InferenceBackendManager.resolveBackend(
                requested = HardwareBackend.QNN_NPU,
                sessionOptionsClass = DummyOptionsWithoutQnn::class.java,
                hardware = "qcom",
                board = "sm8750"
            )
            fail("Strict QNN_NPU must throw IllegalStateException when QNN EP is absent")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message must explicitly mention missing QNN Execution Provider or addQnn",
                e.message?.contains("QNN Execution Provider is not present") == true
            )
        }
    }

    @Test
    fun testStrictQnnNpuFailureOnNonQualcommHardware() {
        try {
            InferenceBackendManager.resolveBackend(
                requested = HardwareBackend.QNN_NPU,
                sessionOptionsClass = DummyOptionsWithQnn::class.java,
                hardware = "goldfish",
                board = "ranchu"
            )
            fail("Strict QNN_NPU must throw IllegalStateException on non-Qualcomm hardware")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message must explicitly explain unsupported SoC",
                e.message?.contains("not a supported Qualcomm Snapdragon SoC") == true
            )
        }
    }

    @Test
    fun testStrictQnnNpuSuccessWhenProviderAndHardwarePresent() {
        val result = InferenceBackendManager.resolveBackend(
            requested = HardwareBackend.QNN_NPU,
            sessionOptionsClass = DummyOptionsWithQnn::class.java,
            hardware = "qcom",
            board = "sun"
        )
        assertEquals(HardwareBackend.QNN_NPU, result.backend)
        assertTrue(result.isAccelerator)
        assertTrue(result.qnnProviderAvailable)
        assertTrue(result.isQualcommSoc)
    }

    @Test
    fun testAutoFallbackToCpuWhenQnnUnavailable() {
        val result = InferenceBackendManager.resolveBackend(
            requested = HardwareBackend.AUTO,
            sessionOptionsClass = DummyOptionsWithoutQnn::class.java,
            hardware = "qcom",
            board = "sm8750"
        )
        assertEquals(HardwareBackend.CPU, result.backend)
        assertFalse(result.isAccelerator)
        assertTrue("AUTO fallback description must document reason", result.description.contains("AUTO fallback to CPU"))
    }

    @Test
    fun testAutoSelectsQnnWhenProviderAndHardwarePresent() {
        val result = InferenceBackendManager.resolveBackend(
            requested = HardwareBackend.AUTO,
            sessionOptionsClass = DummyOptionsWithQnn::class.java,
            hardware = "qcom",
            board = "sun"
        )
        assertEquals(HardwareBackend.QNN_NPU, result.backend)
        assertTrue(result.isAccelerator)
    }

    @Test
    fun testQnnProviderOptionsCreation() {
        val options = InferenceBackendManager.createQnnProviderOptions()
        assertEquals("libQnnHtp.so", options["backend_path"])
        assertEquals("1", options["enable_htp_fp16_precision"])
        assertEquals("burst", options["htp_performance_mode"])
    }

    @Test
    fun testBenchmarkResultCarriesActualBackend() {
        val benchmarkResult = YoloBenchmarkResult(
            detections = emptyList(),
            preprocessTimeMs = 4L,
            inferenceTimeMs = 18L,
            postprocessTimeMs = 2L,
            totalTimeMs = 24L,
            backend = HardwareBackend.CPU
        )
        assertEquals(HardwareBackend.CPU, benchmarkResult.backend)
        assertEquals(24L, benchmarkResult.totalTimeMs)
    }
}
