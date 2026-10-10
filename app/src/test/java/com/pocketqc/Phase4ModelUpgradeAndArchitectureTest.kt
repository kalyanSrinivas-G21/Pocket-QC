package com.pocketqc

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.inference.LetterboxInfo
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloConfig
import com.pocketqc.inference.YoloPostprocessor
import com.pocketqc.inference.YoloPreallocatedBuffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase4ModelUpgradeAndArchitectureTest {

    @Test
    fun testModelConfigDefaultValuesAndComputedProperties() {
        val config = ModelConfig.BISCUITS_FINAL

        assertEquals("biscuits_final", config.id)
        assertEquals("biscuits_final.onnx", config.modelAssetPath)
        assertEquals("images", config.inputName)
        assertEquals(640, config.inputWidth)
        assertEquals(640, config.inputHeight)
        assertEquals(3, config.inputChannels)
        assertEquals(8400, config.numAnchors)
        assertEquals(listOf("Broken", "Fracture", "Intact"), config.classNames)
        assertEquals(3, config.numClasses)
        assertEquals(7, config.numFeatures)
        assertEquals(58800, config.totalOutputElements)
        assertEquals(0.25f, config.confidenceThreshold, 0.001f)
        assertEquals(0.45f, config.iouThreshold, 0.001f)

        assertEquals("Broken", config.getClassName(0))
        assertEquals("Fracture", config.getClassName(1))
        assertEquals("Intact", config.getClassName(2))
        assertEquals("Unknown", config.getClassName(99))

        // Check YoloConfig delegation
        assertEquals("biscuits_final.onnx", YoloConfig.MODEL_ASSET_PATH)
        assertEquals(3, YoloConfig.NUM_CLASSES)
        assertEquals(8400, YoloConfig.NUM_ANCHORS)
        assertEquals(7, YoloConfig.NUM_FEATURES)
    }

    @Test
    fun testCustomModelConfigExtensibility() {
        // Verify architecture easily supports a future model (e.g. 5 defect classes, 1280x1280)
        val futureModel = ModelConfig(
            id = "industrial_flaw_v1",
            modelAssetPath = "flaw_detector.onnx",
            inputWidth = 1280,
            inputHeight = 1280,
            inputChannels = 3,
            numAnchors = 33600,
            classNames = listOf("Dent", "Scratch", "Contaminant", "Discoloration", "Burn"),
            confidenceThreshold = 0.30f,
            iouThreshold = 0.50f
        )

        assertEquals(5, futureModel.numClasses)
        assertEquals(9, futureModel.numFeatures) // 4 coords + 5 class scores
        assertEquals(9 * 33600, futureModel.totalOutputElements)
        assertEquals("Discoloration", futureModel.getClassName(3))
    }

    @Test
    fun testModelAssetExistsOnDiskWithoutDuplicateBloat() {
        val assetDir = File("src/main/assets").takeIf { it.exists() }
            ?: File("app/src/main/assets")

        val newAsset = File(assetDir, "biscuits_final.onnx")
        assertTrue("biscuits_final.onnx must exist in assets", newAsset.exists())
        assertTrue("biscuits_final.onnx must be non-empty (>10MB)", newAsset.length() > 10_000_000L)

        // Verify old model was cleaned up to avoid duplicate APK bloat
        val oldAsset = File(assetDir, "biscuit_yolov8n.onnx")
        assertFalse("Old duplicate model biscuit_yolov8n.onnx should not remain in assets", oldAsset.exists())
    }

    @Test
    fun testGenericPostprocessorWithDynamicModelConfig() {
        val customConfig = ModelConfig(
            id = "custom_test_model",
            modelAssetPath = "test.onnx",
            numAnchors = 50,
            classNames = listOf("DefectA", "DefectB", "DefectC", "DefectD"),
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        val totalElements = customConfig.totalOutputElements // (4 + 4) * 50 = 400
        val rawOutput = FloatArray(totalElements)

        // Anchor 5: DefectB (Class 1, score 0.88)
        rawOutput[0 * 50 + 5] = 320f // cx
        rawOutput[1 * 50 + 5] = 320f // cy
        rawOutput[2 * 50 + 5] = 100f // w
        rawOutput[3 * 50 + 5] = 100f // h
        rawOutput[(4 + 1) * 50 + 5] = 0.88f // score DefectB

        // Anchor 6: DefectB (Class 1, score 0.70, overlapping Anchor 5) -> Must be suppressed
        rawOutput[0 * 50 + 6] = 322f
        rawOutput[1 * 50 + 6] = 322f
        rawOutput[2 * 50 + 6] = 100f
        rawOutput[3 * 50 + 6] = 100f
        rawOutput[(4 + 1) * 50 + 6] = 0.70f

        // Anchor 20: DefectD (Class 3, score 0.92)
        rawOutput[0 * 50 + 20] = 150f
        rawOutput[1 * 50 + 20] = 150f
        rawOutput[2 * 50 + 20] = 80f
        rawOutput[3 * 50 + 20] = 80f
        rawOutput[(4 + 3) * 50 + 20] = 0.92f // score DefectD

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = rawOutput,
            letterboxInfo = letterbox,
            modelConfig = customConfig
        )

        assertEquals(2, detections.size)

        val detB = detections.firstOrNull { it.className == "DefectB" }
        assertNotNull(detB)
        assertEquals(0.88f, detB!!.confidence, 0.001f)

        val detD = detections.firstOrNull { it.className == "DefectD" }
        assertNotNull(detD)
        assertEquals(0.92f, detD!!.confidence, 0.001f)
    }

    @Test
    fun testDynamicConfidenceThresholdFiltering() {
        val config = ModelConfig.BISCUITS_FINAL
        val rawOutput = FloatArray(config.totalOutputElements)

        // Anchor 10: Broken (Class 0, score 0.40)
        rawOutput[0 * 8400 + 10] = 320f
        rawOutput[1 * 8400 + 10] = 320f
        rawOutput[2 * 8400 + 10] = 100f
        rawOutput[3 * 8400 + 10] = 100f
        rawOutput[4 * 8400 + 10] = 0.40f

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)

        // At confidence 0.25 (Settings default): Detected!
        val detLow = YoloPostprocessor.parseAndFilter(
            rawOutput = rawOutput,
            letterboxInfo = letterbox,
            modelConfig = config,
            confidenceThreshold = 0.25f
        )
        assertEquals(1, detLow.size)
        assertEquals("Broken", detLow[0].className)

        // At confidence 0.50 (User adjusted in Settings): Filtered out!
        val detHigh = YoloPostprocessor.parseAndFilter(
            rawOutput = rawOutput,
            letterboxInfo = letterbox,
            modelConfig = config,
            confidenceThreshold = 0.50f
        )
        assertTrue("Candidate with 0.40 confidence must be filtered out when threshold is 0.50", detHigh.isEmpty())
    }

    @Test
    fun testPreallocatedBuffersDynamicModelConfigSizing() {
        val customConfig = ModelConfig(
            id = "tiny_model",
            modelAssetPath = "tiny.onnx",
            inputWidth = 320,
            inputHeight = 320,
            numAnchors = 2100,
            classNames = listOf("A", "B", "C", "D", "E")
        )

        val buffers = YoloPreallocatedBuffers(customConfig)

        // Sizing must match custom model specification
        assertEquals(320 * 320, buffers.channelSize)
        assertEquals(1 * 3 * 320 * 320 * 4, buffers.byteBuffer.capacity()) // 1,228,800 bytes
        assertEquals(320 * 320, buffers.pixels.size)
        assertEquals((4 + 5) * 2100, buffers.outputFloatArray.size) // 18,900 floats

        buffers.release()
    }
}
