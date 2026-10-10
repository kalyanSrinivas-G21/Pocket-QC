package com.pocketqc.inference

import com.pocketqc.camera.CameraFrame
import com.pocketqc.core.model.Detection

/**
 * Hardware abstraction for machine learning inference on incoming inspection frames.
 * Shields the application layer from Qualcomm QNN, Hexagon NPU, TFLite, or ONNX runtimes.
 */
interface InferenceEngine {
    val isInitialized: Boolean
    val targetBackend: HardwareBackend

    suspend fun initialize(): Result<Unit>
    suspend fun detect(frame: CameraFrame): List<Detection>
    fun release()
}
