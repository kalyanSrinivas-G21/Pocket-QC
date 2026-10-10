package com.pocketqc.inference

import com.pocketqc.camera.CameraFrame
import com.pocketqc.core.model.Detection

/**
 * Mock inference engine providing deterministic food quality defect detections
 * for testing and UI development without hardware NPU dependencies.
 */
class MockInferenceEngine(
    override val targetBackend: HardwareBackend = HardwareBackend.CPU
) : InferenceEngine {

    private var initialized = false
    override val isInitialized: Boolean
        get() = initialized

    override suspend fun initialize(): Result<Unit> {
        initialized = true
        return Result.success(Unit)
    }

    override suspend fun detect(frame: CameraFrame): List<Detection> {
        val sampleIndex = (frame.frameId.toInt() - 1).coerceIn(0, MockInspectionScenario.TOTAL_SAMPLES - 1)
        return detectSample(sampleIndex)
    }

    fun detectSample(sampleIndex: Int): List<Detection> {
        if (sampleIndex in 0 until MockInspectionScenario.TOTAL_SAMPLES) {
            return MockInspectionScenario.getSample(sampleIndex).detections
        }
        return emptyList()
    }

    override fun release() {
        initialized = false
    }
}
