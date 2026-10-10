package com.pocketqc.inference

import android.content.Context
import android.graphics.BitmapFactory
import com.pocketqc.core.model.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticSampleResult(
    val assetFileName: String,
    val expectedClass: String,
    val detections: List<Detection>,
    val benchmark: YoloBenchmarkResult,
    val isSuccess: Boolean
)

data class DiagnosticReport(
    val modelName: String,
    val totalSamplesTested: Int,
    val successfulInferences: Int,
    val results: List<DiagnosticSampleResult>,
    val averageInferenceMs: Long
)

object StaticModelDiagnostic {

    private val TEST_SAMPLES = listOf(
        Pair("test_images/biscuit_broken.jpg", "Broken"),
        Pair("test_images/biscuit_fracture.jpg", "Fracture"),
        Pair("test_images/biscuit_intact.jpg", "Intact")
    )

    /**
     * Executes the diagnostic test suite on all bundled test images in assets/test_images/
     * using the [YoloInferenceEngine] on the specified [HardwareBackend].
     */
    suspend fun runDiagnostic(
        context: Context,
        modelConfig: ModelConfig = ModelConfig.BISCUITS_FINAL,
        targetBackend: HardwareBackend = HardwareBackend.QNN_NPU
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val engine = YoloInferenceEngine(context, modelConfig = modelConfig, targetBackend = targetBackend)
        val initResult = engine.initialize()
        check(initResult.isSuccess) { "Failed to initialize YoloInferenceEngine on $targetBackend: ${initResult.exceptionOrNull()?.message}" }

        val results = mutableListOf<DiagnosticSampleResult>()
        var totalInferenceMs = 0L

        try {
            for ((assetPath, expectedClass) in TEST_SAMPLES) {
                val bitmap = context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue

                val benchmark = engine.detectBitmapWithBenchmark(bitmap)
                totalInferenceMs += benchmark.inferenceTimeMs

                val detectedMatchingClass = benchmark.detections.any { it.className == expectedClass }

                results.add(
                    DiagnosticSampleResult(
                        assetFileName = assetPath.substringAfterLast("/"),
                        expectedClass = expectedClass,
                        detections = benchmark.detections,
                        benchmark = benchmark,
                        isSuccess = detectedMatchingClass
                    )
                )

                bitmap.recycle()
            }
        } finally {
            engine.release()
        }

        val avgInference = if (results.isNotEmpty()) totalInferenceMs / results.size else 0L

        DiagnosticReport(
            modelName = modelConfig.modelAssetPath,
            totalSamplesTested = results.size,
            successfulInferences = results.count { it.isSuccess },
            results = results,
            averageInferenceMs = avgInference
        )
    }
}
