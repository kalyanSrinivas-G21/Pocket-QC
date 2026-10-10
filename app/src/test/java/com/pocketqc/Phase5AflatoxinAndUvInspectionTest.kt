package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.LetterboxInfo
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloPostprocessor
import com.pocketqc.inference.YoloPreallocatedBuffers
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.uv.UvInspectionEngine
import com.pocketqc.uv.UvInspectionPhase
import com.pocketqc.uv.UvSampleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase5AflatoxinAndUvInspectionTest {

    @Test
    fun testModelConfigAflatoxinSpecification() {
        val config = ModelConfig.AFLATOXIN

        assertEquals("aflatoxin", config.id)
        assertEquals("aflatoxin.onnx", config.modelAssetPath)
        assertEquals("images", config.inputName)
        assertEquals(640, config.inputWidth)
        assertEquals(640, config.inputHeight)
        assertEquals(3, config.inputChannels)
        assertEquals(8400, config.numAnchors)
        assertEquals(listOf("Aflatoxin"), config.classNames)
        assertEquals(1, config.numClasses)
        assertEquals(5, config.numFeatures) // 4 bounding box coordinates (cx, cy, w, h) + 1 class score
        assertEquals(42000, config.totalOutputElements) // 5 * 8400
        assertEquals(0.25f, config.confidenceThreshold, 0.001f)
        assertEquals(0.45f, config.iouThreshold, 0.001f)

        assertEquals("Aflatoxin", config.getClassName(0))
        assertEquals("Unknown", config.getClassName(1))
    }

    @Test
    fun testAflatoxinModelAssetExistsAndIsValidSize() {
        val assetDir = File("src/main/assets").takeIf { it.exists() }
            ?: File("app/src/main/assets")

        val aflatoxinAsset = File(assetDir, "aflatoxin.onnx")
        assertTrue("aflatoxin.onnx must exist in assets", aflatoxinAsset.exists())
        assertTrue("aflatoxin.onnx must be non-empty (>11MB)", aflatoxinAsset.length() > 11_000_000L)

        // Verify coexistence with biscuits_final.onnx without collision
        val biscuitsAsset = File(assetDir, "biscuits_final.onnx")
        assertTrue("biscuits_final.onnx must also exist in assets", biscuitsAsset.exists())
    }

    @Test
    fun testPreallocatedBuffersForAflatoxinModel() {
        val config = ModelConfig.AFLATOXIN
        val buffers = YoloPreallocatedBuffers(config)

        assertEquals(640 * 640, buffers.channelSize)
        assertEquals(1 * 3 * 640 * 640 * 4, buffers.byteBuffer.capacity()) // 4,915,200 bytes
        assertEquals(640 * 640, buffers.pixels.size)
        assertEquals(42000, buffers.outputFloatArray.size) // (4 + 1) * 8400 floats = 42,000

        buffers.release()
    }

    @Test
    fun testAflatoxinSingleClassOutputParsingAndFiltering() {
        val config = ModelConfig.AFLATOXIN
        val rawOutput = FloatArray(config.totalOutputElements) // 5 * 8400

        // Anchor 50: High-confidence Aflatoxin spot (score 0.84, cx=320, cy=320, w=50, h=50)
        rawOutput[0 * 8400 + 50] = 320f
        rawOutput[1 * 8400 + 50] = 320f
        rawOutput[2 * 8400 + 50] = 50f
        rawOutput[3 * 8400 + 50] = 50f
        rawOutput[4 * 8400 + 50] = 0.84f // score

        // Anchor 60: Low-confidence noise below threshold (score 0.15)
        rawOutput[0 * 8400 + 60] = 100f
        rawOutput[1 * 8400 + 60] = 100f
        rawOutput[2 * 8400 + 60] = 30f
        rawOutput[3 * 8400 + 60] = 30f
        rawOutput[4 * 8400 + 60] = 0.15f // score

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = rawOutput,
            letterboxInfo = letterbox,
            modelConfig = config,
            confidenceThreshold = 0.25f
        )

        assertEquals(1, detections.size)
        val det = detections[0]
        assertEquals("Aflatoxin", det.className)
        assertEquals(0.84f, det.confidence, 0.001f)
    }

    @Test
    fun testAflatoxinNmsOverlappingSpotSuppression() {
        val config = ModelConfig.AFLATOXIN
        val rawOutput = FloatArray(config.totalOutputElements)

        // Anchor 10: Primary detection of Aflatoxin spot (score 0.85)
        rawOutput[0 * 8400 + 10] = 200f
        rawOutput[1 * 8400 + 10] = 200f
        rawOutput[2 * 8400 + 10] = 60f
        rawOutput[3 * 8400 + 10] = 60f
        rawOutput[4 * 8400 + 10] = 0.85f

        // Anchor 11: Duplicate overlapping detection of same spot (score 0.72) -> Should be suppressed
        rawOutput[0 * 8400 + 11] = 202f
        rawOutput[1 * 8400 + 11] = 202f
        rawOutput[2 * 8400 + 11] = 60f
        rawOutput[3 * 8400 + 11] = 60f
        rawOutput[4 * 8400 + 11] = 0.72f

        // Anchor 100: Distinct second Aflatoxin spot elsewhere (score 0.79)
        rawOutput[0 * 8400 + 100] = 450f
        rawOutput[1 * 8400 + 100] = 450f
        rawOutput[2 * 8400 + 100] = 40f
        rawOutput[3 * 8400 + 100] = 40f
        rawOutput[4 * 8400 + 100] = 0.79f

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = rawOutput,
            letterboxInfo = letterbox,
            modelConfig = config,
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        assertEquals("Overlapping duplicate should be suppressed by NMS, leaving 2 distinct spots", 2, detections.size)
        assertEquals(0.85f, detections[0].confidence, 0.001f)
        assertEquals(0.79f, detections[1].confidence, 0.001f)
    }

    @Test
    fun testUvInspectionEngineWithRealPositiveDetections() {
        runBlocking {
            val mockBatchManager = MockBatchManager()
            val positiveSpots = listOf(
                Detection("Aflatoxin", 0.84f, BoundingBox(0.2f, 0.2f, 0.4f, 0.4f)),
                Detection("Aflatoxin", 0.71f, BoundingBox(0.6f, 0.6f, 0.8f, 0.8f))
            )

            val engine = UvInspectionEngine(
                batchManager = mockBatchManager,
                scope = CoroutineScope(Dispatchers.Default),
                interSampleWaitSeconds = 0,
                analysisDurationMs = 50L,
                liveDetectionsProvider = { positiveSpots }
            )

            val batchId = engine.startBatch(productName = "Guntur Sannam Chilli Lot 1")
            var waited = 0
            while (engine.state.value.totalInspected == 0 && waited < 3000) {
                delay(100)
                waited += 100
            }

            val state = engine.state.value
            assertTrue("At least 1 sample inspected", state.totalInspected >= 1)
            assertEquals(1, state.flaggedCount)
            assertEquals(0, state.passedCount)
            assertEquals(100f, state.flagRate, 0.01f)

            val record = state.sampleRecords.first()
            assertEquals(UvSampleResult.FLAGGED, record.result)
            assertEquals(2, record.spotsDetected)
            assertEquals(0.84f, record.confidence, 0.001f)
            assertEquals(84f, record.fluorescenceScore, 0.1f)
            assertEquals(2, record.detections.size)
            assertTrue(record.modelVersion.startsWith("Aflatoxin-YOLOv8n"))

            val completed = engine.stopBatch()
            assertEquals(BatchStatus.REJECTED, completed.status)
            assertEquals(1, completed.flaggedCount)
            assertEquals(batchId, completed.batchId)
        }
    }

    @Test
    fun testUvInspectionEngineWithCleanSampleZeroDetections() {
        runBlocking {
            val mockBatchManager = MockBatchManager()
            val emptyDetections = emptyList<Detection>()

            val engine = UvInspectionEngine(
                batchManager = mockBatchManager,
                scope = CoroutineScope(Dispatchers.Default),
                interSampleWaitSeconds = 0,
                analysisDurationMs = 50L,
                liveDetectionsProvider = { emptyDetections }
            )

            engine.startBatch(productName = "Pure Red Chilli Sample")
            var waited = 0
            while (engine.state.value.totalInspected == 0 && waited < 3000) {
                delay(100)
                waited += 100
            }

            val state = engine.state.value
            assertTrue(state.totalInspected >= 1)
            assertEquals(1, state.passedCount)
            assertEquals(0, state.flaggedCount)
            assertEquals(0f, state.flagRate, 0.01f)

            val record = state.sampleRecords.first()
            assertEquals(UvSampleResult.PASS, record.result)
            assertEquals(0, record.spotsDetected)
            assertEquals(0f, record.confidence, 0.001f)
            assertEquals(0f, record.fluorescenceScore, 0.1f)

            val completed = engine.stopBatch()
            assertEquals(BatchStatus.APPROVED, completed.status)
            assertEquals(0, completed.flaggedCount)
        }
    }

    @Test
    fun testUvInspectionEngineImmediateScanTrigger() {
        runBlocking {
            val mockBatchManager = MockBatchManager()
            var callCount = 0
            val engine = UvInspectionEngine(
                batchManager = mockBatchManager,
                scope = CoroutineScope(Dispatchers.Default),
                interSampleWaitSeconds = 60, // Long wait time
                analysisDurationMs = 50L,
                liveDetectionsProvider = {
                    callCount++
                    emptyList()
                }
            )

            engine.startBatch()
            // Wait for first sample to complete
            var waited = 0
            while (engine.state.value.totalInspected == 0 && waited < 3000) {
                delay(100)
                waited += 100
            }
            assertEquals(1, engine.state.value.totalInspected)

            // Currently in waiting countdown phase
            // Trigger immediate scan to bypass the 60s wait!
            engine.triggerImmediateScan()

            // Wait for sample 2 to be processed
            waited = 0
            while (engine.state.value.totalInspected < 2 && waited < 3000) {
                delay(100)
                waited += 100
            }

            assertEquals("Immediate scan must bypass countdown to produce sample 2", 2, engine.state.value.totalInspected)
            engine.stopBatch()
        }
    }

    @Test
    fun testUvBatchQrPayloadFormatAndPersistence() {
        runBlocking {
            val mockBatchManager = MockBatchManager()
            var sampleIndex = 0

            val engine = UvInspectionEngine(
                batchManager = mockBatchManager,
                scope = CoroutineScope(Dispatchers.Default),
                interSampleWaitSeconds = 0,
                analysisDurationMs = 50L,
                liveDetectionsProvider = {
                    sampleIndex++
                    if (sampleIndex == 1) {
                        emptyList() // Sample 1: Clean (PASS)
                    } else {
                        listOf(Detection("Aflatoxin", 0.78f, BoundingBox(0.3f, 0.3f, 0.5f, 0.5f))) // Sample 2: Contaminated (FLAGGED)
                    }
                }
            )

            engine.startBatch()
            var waited = 0
            while (engine.state.value.totalInspected < 2 && waited < 4000) {
                delay(100)
                waited += 100
            }

            val completedBatch = engine.stopBatch()
            mockBatchManager.saveBatch(completedBatch)

            assertEquals(2, completedBatch.inspectionCount)
            assertEquals(1, completedBatch.passCount)
            assertEquals(1, completedBatch.failCount)
            assertEquals(1, completedBatch.flaggedCount)
            assertEquals(InspectionMode.UV_AFLATOXIN, completedBatch.mode)

            // Parse and validate QR JSON payload
            val qrJson = JSONObject(completedBatch.qrPayload)
            assertEquals("PocketQC", qrJson.getString("app"))
            assertEquals("UV", qrJson.getString("type"))
            assertEquals(2, qrJson.getInt("total"))
            assertEquals(1, qrJson.getInt("pass"))
            assertEquals(1, qrJson.getInt("defect"))
            assertEquals(completedBatch.batchId, qrJson.getString("id"))

            // Verify retrieval from history
            val storedBatch = mockBatchManager.getBatchById(completedBatch.batchId)
            assertNotNull(storedBatch)
            assertEquals(2, storedBatch!!.inspectionCount)
            assertEquals(1, storedBatch.flaggedCount)
        }
    }

    @Test
    fun testUvInspectionEngineResetClearsState() {
        val mockBatchManager = MockBatchManager()
        val engine = UvInspectionEngine(batchManager = mockBatchManager)

        engine.startBatch()
        engine.reset()

        val state = engine.state.value
        assertEquals(UvInspectionPhase.IDLE, state.phase)
        assertEquals(0, state.totalInspected)
        assertEquals(0, state.sampleRecords.size)
        assertFalse(state.isLightOn)
    }
}
