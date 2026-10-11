package com.pocketqc.autolabel.engine

import android.content.Context
import android.graphics.Bitmap
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction for localized biscuit candidate proposal.
 */
interface BiscuitDetector {
    suspend fun detect(bitmap: Bitmap?): List<Detection>
}

/**
 * Isolated biscuit detector utilizing the bundled read-only ONNX asset on CPU.
 * Completely separate from production 2D conveyor inspection session and NPU hardware paths.
 */
class ProductionAssetBiscuitDetector(
    context: Context,
    confidenceThreshold: Float = 0.25f,
    iouThreshold: Float = 0.45f
) : BiscuitDetector {

    // Isolated engine instance dedicated solely to auto-labeling proposals
    private val engine = YoloInferenceEngine(
        context = context,
        modelConfig = ModelConfig.BISCUITS_FINAL,
        confidenceThreshold = confidenceThreshold,
        iouThreshold = iouThreshold,
        targetBackend = HardwareBackend.CPU
    )

    override suspend fun detect(bitmap: Bitmap?): List<Detection> = withContext(Dispatchers.Default) {
        if (bitmap == null) return@withContext emptyList()
        if (!engine.isInitialized) {
            engine.initialize()
        }
        engine.detectBitmap(bitmap)
    }
}

/**
 * Lightweight mock detector for unit tests and headless environments.
 */
class MockBiscuitDetector(
    private val mockDetectionsProvider: (Bitmap?) -> List<Detection> = {
        listOf(
            Detection(
                className = "Intact",
                confidence = 0.92f,
                boundingBox = BoundingBox(0.20f, 0.25f, 0.75f, 0.80f)
            )
        )
    }
) : BiscuitDetector {
    override suspend fun detect(bitmap: Bitmap?): List<Detection> {
        return mockDetectionsProvider(bitmap)
    }
}
