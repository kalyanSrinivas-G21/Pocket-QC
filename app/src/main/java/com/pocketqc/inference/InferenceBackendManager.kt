package com.pocketqc.inference

import android.os.Build
import android.util.Log

/**
 * Result of backend resolution describing the active hardware target and policy outcome.
 */
data class ResolvedBackendResult(
    val backend: HardwareBackend,
    val isAccelerator: Boolean,
    val description: String,
    val qnnProviderAvailable: Boolean,
    val isQualcommSoc: Boolean
)

/**
 * Manages detection, configuration, and fallback policies for on-device inference hardware.
 *
 * Distinguishes true Qualcomm Hexagon NPU (QNN HTP) acceleration from CPU execution,
 * enforcing explicit policies so that accelerator failures are never masked as successful NPU runs.
 */
object InferenceBackendManager {

    private const val TAG = "InferenceBackendManager"

    /**
     * Inspects the active ONNX Runtime class definition to determine if QNN EP is present.
     */
    fun isQnnExecutionProviderAvailable(sessionOptionsClass: Class<*>? = null): Boolean {
        val targetClass = sessionOptionsClass ?: try {
            Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
        } catch (_: Throwable) {
            return false
        }

        return try {
            targetClass.getMethod("addQnn", Map::class.java) != null
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Determines whether the current host hardware is a Qualcomm Snapdragon platform.
     */
    fun isQualcommPlatform(
        hardware: String = Build.HARDWARE ?: "",
        board: String = Build.BOARD ?: "",
        socModel: String = ""
    ): Boolean {
        val lowerHw = hardware.lowercase()
        val lowerBoard = board.lowercase()
        val lowerSoc = socModel.lowercase()

        val qcomKeywords = listOf("qcom", "qualcomm", "snapdragon", "sm8", "sm7", "sm6", "lahaina", "taro", "kalama", "pineapple", "sun")
        return qcomKeywords.any { kw ->
            lowerHw.contains(kw) || lowerBoard.contains(kw) || lowerSoc.contains(kw)
        }
    }

    /**
     * Resolves the execution backend based on user request, runtime capabilities, and hardware platform.
     */
    fun resolveBackend(
        requested: HardwareBackend,
        sessionOptionsClass: Class<*>? = null,
        hardware: String = Build.HARDWARE ?: "",
        board: String = Build.BOARD ?: "",
        socModel: String = ""
    ): ResolvedBackendResult {
        val qnnAvailable = isQnnExecutionProviderAvailable(sessionOptionsClass)
        val isQualcomm = isQualcommPlatform(hardware, board, socModel)

        return when (requested) {
            HardwareBackend.CPU -> {
                ResolvedBackendResult(
                    backend = HardwareBackend.CPU,
                    isAccelerator = false,
                    description = "CPU execution baseline (ONNX Runtime Mobile CPU EP, 4 intra-op threads)",
                    qnnProviderAvailable = qnnAvailable,
                    isQualcommSoc = isQualcomm
                )
            }
            HardwareBackend.QNN_NPU -> {
                if (!qnnAvailable) {
                    throw IllegalStateException(
                        "Qualcomm QNN HTP NPU backend requested, but QNN Execution Provider is not present in active ONNX Runtime package (missing addQnn API). Strict accelerator policy requires failing visibly."
                    )
                }
                if (!isQualcomm) {
                    throw IllegalStateException(
                        "Qualcomm QNN HTP NPU backend requested, but device hardware ($hardware/$board) is not a supported Qualcomm Snapdragon SoC."
                    )
                }
                ResolvedBackendResult(
                    backend = HardwareBackend.QNN_NPU,
                    isAccelerator = true,
                    description = "Qualcomm AI Engine Direct QNN HTP (Hexagon Tensor Processor) NPU accelerator",
                    qnnProviderAvailable = true,
                    isQualcommSoc = true
                )
            }
            HardwareBackend.AUTO -> {
                if (qnnAvailable && isQualcomm) {
                    ResolvedBackendResult(
                        backend = HardwareBackend.QNN_NPU,
                        isAccelerator = true,
                        description = "AUTO: Qualcomm Snapdragon SoC and QNN EP detected. Accelerated via Hexagon NPU.",
                        qnnProviderAvailable = true,
                        isQualcommSoc = true
                    )
                } else {
                    val fallbackReason = when {
                        !qnnAvailable && !isQualcomm -> "QNN Execution Provider absent and non-Qualcomm SoC detected"
                        !qnnAvailable -> "QNN Execution Provider absent from active ONNX Runtime build"
                        else -> "Non-Qualcomm SoC detected ($hardware/$board)"
                    }
                    Log.i(TAG, "AUTO policy falling back to CPU: $fallbackReason")
                    ResolvedBackendResult(
                        backend = HardwareBackend.CPU,
                        isAccelerator = false,
                        description = "AUTO fallback to CPU: $fallbackReason",
                        qnnProviderAvailable = qnnAvailable,
                        isQualcommSoc = isQualcomm
                    )
                }
            }
        }
    }

    /**
     * Optional environment configuration for ADSP_LIBRARY_PATH.
     * Note: ONNX Runtime QNN Execution Provider manages this automatically if left unset.
     */
    fun configureEnvironmentForAdsp(nativeLibraryDir: String?) {
        // Left as no-op to allow ONNX Runtime QNN Execution Provider to configure its canonical DSP path
        Log.d(TAG, "Delegating ADSP_LIBRARY_PATH management to ONNX Runtime QNN Execution Provider (nativeDir=$nativeLibraryDir)")
    }

    /**
     * Builds standard QNN HTP provider options for YOLO vision inference on Snapdragon 8 Elite (SM8850 / HTP v81).
     */
    fun createQnnProviderOptions(
        backendPath: String = "libQnnHtp.so",
        nativeLibraryDir: String? = null,
        enableFp16Precision: Boolean = true,
        performanceMode: String = "burst",
        graphOptimizationMode: String = "3",
        htpArch: String = "81"
    ): Map<String, String> {
        val resolvedPath = if (!nativeLibraryDir.isNullOrBlank()) {
            "$nativeLibraryDir/libQnnHtp.so"
        } else {
            backendPath
        }
        val options = mutableMapOf(
            "backend_path" to resolvedPath,
            "enable_htp_fp16_precision" to if (enableFp16Precision) "1" else "0",
            "htp_performance_mode" to performanceMode,
            "htp_graph_finalization_optimization_mode" to graphOptimizationMode
        )
        if (htpArch.isNotBlank()) {
            options["htp_arch"] = htpArch
        }
        return options
    }
}
