package com.pocketqc.inference

/**
 * Execution backend for on-device neural network inference on the target platform.
 *
 * - CPU: Verified ONNX Runtime CPU path with preallocated direct buffers. Baseline.
 * - QNN_NPU: Requests Qualcomm AI Engine Direct (QNN) Hexagon Tensor Processor (HTP) NPU backend.
 *            Fails explicitly if QNN HTP is unavailable or cannot compile the graph.
 * - AUTO: Attempts QNN_NPU first; falls back to CPU with logged telemetry and explicit reporting.
 */
enum class HardwareBackend {
    CPU,
    QNN_NPU,
    AUTO;

    companion object {
        @Deprecated("Use QNN_NPU", ReplaceWith("QNN_NPU"))
        val HEXAGON_NPU_QNN: HardwareBackend get() = QNN_NPU

        @Deprecated("Use AUTO or CPU", ReplaceWith("AUTO"))
        val GPU: HardwareBackend get() = AUTO
    }
}
