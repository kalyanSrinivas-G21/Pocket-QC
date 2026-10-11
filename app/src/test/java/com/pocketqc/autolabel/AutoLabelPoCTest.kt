package com.pocketqc.autolabel

import android.graphics.Bitmap
import com.pocketqc.autolabel.dataset.AutoLabelDatasetManager
import com.pocketqc.autolabel.dataset.DuplicateImageException
import com.pocketqc.autolabel.engine.AutoLabelPipeline
import com.pocketqc.autolabel.engine.CropFeatureAnalyzer
import com.pocketqc.autolabel.engine.MockBiscuitDetector
import com.pocketqc.autolabel.evaluation.AutoLabelEvaluator
import com.pocketqc.autolabel.export.YoloDatasetExporter
import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.ProposalSource
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.autolabel.model.YoloBoxCoordinates
import com.pocketqc.autolabel.workspace.AutoLabelWorkspace
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.ModelConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class AutoLabelPoCTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val productionModelAssetFile = File("src/main/assets/biscuits_final.onnx")
    private val expectedProductionHash = "92D96E80AF5C8E897459B74C58862CE201DBE09CDAA41F0FD03EDA6A4A2EDF08"

    private fun getFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun createTestWorkspace(baseDir: File): AutoLabelWorkspace {
        return AutoLabelWorkspace(
            baseFilesDir = baseDir,
            assetStreamProvider = { fileName ->
                val f = File("src/main/assets", fileName)
                if (!f.exists()) throw java.io.FileNotFoundException("Asset $fileName not found")
                FileInputStream(f)
            }
        )
    }

    private fun createSyntheticImageBytes(size: Int = 256, seed: Byte = 42): ByteArray {
        return ByteArray(size) { idx -> ((idx * 7 + seed) % 256).toByte() }
    }

    // =========================================================================
    // 1. Coordinate Conversion & Image Boundaries
    // =========================================================================

    @Test
    fun testCoordinateConversionAndYoloNormalization() {
        // Center box: left=0.2, top=0.3, right=0.8, bottom=0.7
        val box = BoundingBox(left = 0.2f, top = 0.3f, right = 0.8f, bottom = 0.7f)
        val annotation = ProposedBoxAnnotation(
            boxId = "box-1",
            boundingBox = box,
            predictedClass = AutoLabelClass.INTACT,
            confidence = 0.95f
        )

        val yolo = annotation.toYoloCoordinates()
        // Expected: xCenter = (0.2 + 0.8)/2 = 0.5, yCenter = (0.3 + 0.7)/2 = 0.5
        // width = 0.8 - 0.2 = 0.6, height = 0.7 - 0.3 = 0.4
        assertEquals(0.5f, yolo.xCenter, 0.001f)
        assertEquals(0.5f, yolo.yCenter, 0.001f)
        assertEquals(0.6f, yolo.width, 0.001f)
        assertEquals(0.4f, yolo.height, 0.001f)

        // Round trip back to BoundingBox
        val restoredBox = yolo.toBoundingBox()
        assertEquals(0.2f, restoredBox.left, 0.001f)
        assertEquals(0.3f, restoredBox.top, 0.001f)
        assertEquals(0.8f, restoredBox.right, 0.001f)
        assertEquals(0.7f, restoredBox.bottom, 0.001f)

        // YOLO formatted line
        val line = yolo.toYoloLine(classId = 0)
        assertEquals("0 0.500000 0.500000 0.600000 0.400000", line)
    }

    @Test
    fun testPixelToYoloConversionAndBoundaryClamping() {
        // Image 640x480, pixel coords (160, 120, 480, 360)
        val coords = YoloDatasetExporter.convertPixelToYolo(
            x1 = 160f,
            y1 = 120f,
            x2 = 480f,
            y2 = 360f,
            imageWidth = 640,
            imageHeight = 480
        )
        // xCenter = (160 + 480) / 2 / 640 = 320 / 640 = 0.5
        // yCenter = (120 + 360) / 2 / 480 = 240 / 480 = 0.5
        // width = (480 - 160) / 640 = 320 / 640 = 0.5
        // height = (360 - 120) / 480 = 240 / 480 = 0.5
        assertEquals(0.5f, coords[0], 0.001f)
        assertEquals(0.5f, coords[1], 0.001f)
        assertEquals(0.5f, coords[2], 0.001f)
        assertEquals(0.5f, coords[3], 0.001f)

        // Out of bounds inputs must be coerced to [0.0, 1.0]
        val clamped = YoloDatasetExporter.convertPixelToYolo(
            x1 = -50f,
            y1 = -50f,
            x2 = 1000f,
            y2 = 1000f,
            imageWidth = 640,
            imageHeight = 480
        )
        assertTrue(clamped[0] in 0f..1f)
        assertTrue(clamped[1] in 0f..1f)
        assertTrue(clamped[2] in 0f..1f)
        assertTrue(clamped[3] in 0f..1f)
    }

    // =========================================================================
    // 2. Class-ID Mapping & Independence
    // =========================================================================

    @Test
    fun testClassIdMappingIndependenceFromProductionModel() {
        // Requirement:
        // Experimental Dataset: 0 = Intact, 1 = Fracture, 2 = Broken
        assertEquals(0, AutoLabelClass.INTACT.classId)
        assertEquals("Intact", AutoLabelClass.INTACT.displayName)

        assertEquals(1, AutoLabelClass.FRACTURE.classId)
        assertEquals("Fracture", AutoLabelClass.FRACTURE.displayName)

        assertEquals(2, AutoLabelClass.BROKEN.classId)
        assertEquals("Broken", AutoLabelClass.BROKEN.displayName)

        // Production Model: 0 = Broken, 1 = Fracture, 2 = Intact
        val prodConfig = ModelConfig.BISCUITS_FINAL
        assertEquals("Broken", prodConfig.getClassName(0))
        assertEquals("Fracture", prodConfig.getClassName(1))
        assertEquals("Intact", prodConfig.getClassName(2))

        // Decoupled Translation:
        assertEquals(AutoLabelClass.INTACT, AutoLabelClass.fromProductionLabel("Intact"))
        assertEquals(AutoLabelClass.FRACTURE, AutoLabelClass.fromProductionLabel("Fracture"))
        assertEquals(AutoLabelClass.BROKEN, AutoLabelClass.fromProductionLabel("Broken"))

        assertEquals(AutoLabelClass.BROKEN, AutoLabelClass.fromProductionClassId(0))
        assertEquals(AutoLabelClass.FRACTURE, AutoLabelClass.fromProductionClassId(1))
        assertEquals(AutoLabelClass.INTACT, AutoLabelClass.fromProductionClassId(2))
    }

    // =========================================================================
    // 3. Invalid or Empty Detector Results Handling
    // =========================================================================

    @Test
    fun testEmptyDetectorResultsHandledGracefully() = runBlocking {
        val emptyDetector = MockBiscuitDetector { emptyList() }
        val pipeline = AutoLabelPipeline(emptyDetector)

        val item = AutoLabelImageItem(
            id = "test-1",
            fileHash = "hash1",
            fileName = "img1.jpg",
            filePath = "/mock/img1.jpg",
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640
        )
        val result = pipeline.processImage(item)

        assertEquals(0, result.validBoxes)
        assertEquals(0, result.updatedItem.annotations.size)
        // Images with 0 detections must be flagged REVIEW_REQUIRED
        assertEquals(AnnotationStatus.REVIEW_REQUIRED, result.updatedItem.reviewStatus)
        assertEquals("No biscuit detected", result.updatedItem.rejectionReason)
    }

    @Test
    fun testDegenerateAndInvalidBoxesRejected() = runBlocking {
        val invalidDetector = MockBiscuitDetector {
            listOf(
                // Degenerate: inverted coordinates
                Detection("Intact", 0.9f, BoundingBox(0.8f, 0.8f, 0.2f, 0.2f)),
                // Degenerate: area smaller than minimum threshold (< 0.005)
                Detection("Intact", 0.9f, BoundingBox(0.1f, 0.1f, 0.11f, 0.11f)),
                // Valid box
                Detection("Fracture", 0.88f, BoundingBox(0.2f, 0.2f, 0.7f, 0.7f))
            )
        }
        val pipeline = AutoLabelPipeline(invalidDetector)

        val item = AutoLabelImageItem(
            id = "test-2",
            fileHash = "hash2",
            fileName = "img2.jpg",
            filePath = "/mock/img2.jpg",
            source = "IMPORT",
            imageWidth = 640,
            imageHeight = 640
        )

        val result = pipeline.processImage(item)

        assertEquals(3, result.totalProposed)
        assertEquals(2, result.invalidBoxes)
        assertEquals(1, result.validBoxes)
        assertEquals(AutoLabelClass.FRACTURE, result.updatedItem.annotations[0].assignedClass)
    }

    // =========================================================================
    // 4. Ambiguous Classifications, Border Touching & Overlapping Biscuits
    // =========================================================================

    @Test
    fun testLowConfidenceAndBorderTouchingFlaggedAsReviewRequired() = runBlocking {
        val borderlineDetector = MockBiscuitDetector {
            listOf(
                // Low confidence (< 0.65) and touches left border (left <= 0.015)
                Detection("Intact", 0.45f, BoundingBox(0.005f, 0.20f, 0.60f, 0.70f))
            )
        }
        val pipeline = AutoLabelPipeline(borderlineDetector, reviewConfidenceThreshold = 0.65f)

        val item = AutoLabelImageItem(
            id = "test-3",
            fileHash = "hash3",
            fileName = "img3.jpg",
            filePath = "/mock/img3.jpg",
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640
        )

        val result = pipeline.processImage(item)

        assertEquals(1, result.validBoxes)
        val box = result.updatedItem.annotations[0]
        assertNotNull(box.reviewNotes)
        assertTrue(box.reviewNotes!!.contains("Low confidence"))
        assertTrue(box.reviewNotes!!.contains("boundary"))
        // Image status must be REVIEW_REQUIRED
        assertEquals(AnnotationStatus.REVIEW_REQUIRED, result.updatedItem.reviewStatus)
        // Machine proposal is never human verified automatically
        assertFalse(box.isHumanVerified)
    }

    @Test
    fun testOverlappingTouchingBiscuitsFlaggedForReview() = runBlocking {
        val overlappingDetector = MockBiscuitDetector {
            listOf(
                Detection("Intact", 0.85f, BoundingBox(0.10f, 0.10f, 0.60f, 0.60f)),
                Detection("Broken", 0.80f, BoundingBox(0.30f, 0.30f, 0.80f, 0.80f)) // IoU ~ 0.22 >= 0.15
            )
        }
        val pipeline = AutoLabelPipeline(overlappingDetector, overlapIouThreshold = 0.15f)

        val item = AutoLabelImageItem(
            id = "test-4",
            fileHash = "hash4",
            fileName = "img4.jpg",
            filePath = "/mock/img4.jpg",
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640
        )

        val result = pipeline.processImage(item)

        assertEquals(2, result.validBoxes)
        // Both overlapping boxes must have notes
        for (box in result.updatedItem.annotations) {
            assertNotNull(box.reviewNotes)
            assertTrue(box.reviewNotes!!.contains("Overlapping/touching"))
        }
        assertEquals(AnnotationStatus.REVIEW_REQUIRED, result.updatedItem.reviewStatus)
    }

    // =========================================================================
    // 5. Duplicate Imports & Filename Collisions
    // =========================================================================

    @Test
    fun testDuplicateImageImportRejection() {
        val baseDir = tempFolder.newFolder("app_files")
        val workspace = createTestWorkspace(baseDir)
        val manager = AutoLabelDatasetManager(workspace = workspace)

        val bytes = createSyntheticImageBytes(size = 200, seed = 1)

        // 1. First ingestion succeeds
        val res1 = manager.ingestImageBytes(bytes, width = 120, height = 120, source = "TEST", operatorClass = AutoLabelClass.INTACT)
        assertTrue(res1.isSuccess)
        val item1 = res1.getOrThrow()
        assertTrue(File(item1.filePath).exists())

        // 2. Second ingestion of identical content is rejected with DuplicateImageException
        val res2 = manager.ingestImageBytes(bytes, width = 120, height = 120, source = "TEST", operatorClass = AutoLabelClass.INTACT)
        assertTrue(res2.isFailure)
        assertTrue(res2.exceptionOrNull() is DuplicateImageException)

        // Check catalog only contains 1 image
        val items = manager.loadItems()
        assertEquals(1, items.size)
    }

    // =========================================================================
    // 6. Missing or Corrupted Images Handling
    // =========================================================================

    @Test
    fun testMissingImagesHandledGracefully() {
        val baseDir = tempFolder.newFolder("app_files_missing")
        val workspace = createTestWorkspace(baseDir)
        val manager = AutoLabelDatasetManager(workspace = workspace)

        val bytes = createSyntheticImageBytes(size = 64, seed = 8)
        val item = manager.ingestImageBytes(bytes, width = 64, height = 64).getOrThrow()
        assertEquals(1, manager.loadItems().size)

        // Delete underlying image file to simulate missing/corrupted file
        File(item.filePath).delete()

        // loadItems must prune missing image without crashing
        val reloaded = manager.loadItems()
        assertEquals(0, reloaded.size)
    }

    // =========================================================================
    // 7. YOLO Dataset Export Consistency
    // =========================================================================

    @Test
    fun testYoloDatasetExportOnlyApprovedAnnotations() {
        val baseDir = tempFolder.newFolder("app_files_export")
        val workspace = createTestWorkspace(baseDir)

        // Create dummy image file
        val imgFile = File(workspace.imagesDir, "biscuit_approved_01.jpg")
        FileOutputStream(imgFile).use { it.write(ByteArray(100)) }

        val approvedItem = AutoLabelImageItem(
            id = "appr-1",
            fileHash = "hash_appr",
            fileName = "biscuit_approved_01.jpg",
            filePath = imgFile.absolutePath,
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640,
            reviewStatus = AnnotationStatus.APPROVED,
            annotations = listOf(
                ProposedBoxAnnotation(
                    boxId = "b1",
                    boundingBox = BoundingBox(0.2f, 0.2f, 0.8f, 0.8f),
                    predictedClass = AutoLabelClass.INTACT,
                    confidence = 0.98f,
                    assignedClass = AutoLabelClass.INTACT,
                    isHumanVerified = true
                ),
                ProposedBoxAnnotation(
                    boxId = "b2",
                    boundingBox = BoundingBox(0.1f, 0.1f, 0.4f, 0.4f),
                    predictedClass = AutoLabelClass.FRACTURE,
                    confidence = 0.92f,
                    assignedClass = AutoLabelClass.FRACTURE,
                    isHumanVerified = true
                )
            )
        )

        val unapprovedItem = AutoLabelImageItem(
            id = "unappr-1",
            fileHash = "hash_unappr",
            fileName = "biscuit_unapproved_02.jpg",
            filePath = File(workspace.imagesDir, "biscuit_unapproved_02.jpg").apply { writeBytes(ByteArray(50)) }.absolutePath,
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640,
            reviewStatus = AnnotationStatus.REVIEW_REQUIRED,
            annotations = listOf(
                ProposedBoxAnnotation(
                    boxId = "b3",
                    boundingBox = BoundingBox(0.3f, 0.3f, 0.7f, 0.7f),
                    predictedClass = AutoLabelClass.BROKEN,
                    confidence = 0.50f
                )
            )
        )

        val exportDir = File(baseDir, "test_yolo_out")
        val result = YoloDatasetExporter.exportToYolo(
            items = listOf(approvedItem, unapprovedItem),
            exportDir = exportDir
        )

        // Strict verification: only 1 approved image exported, 1 unapproved skipped
        assertEquals(1, result.exportedImageCount)
        assertEquals(2, result.exportedBoxCount)
        assertEquals(1, result.skippedUnapprovedCount)
        assertTrue(result.validationErrors.isEmpty())
        assertTrue("Export must be structurally valid", result.isStructurallyValid)

        // Check file outputs in train partition (1 sample < minSamplesForVal goes to train)
        val exportedImg = File(result.getSplitImagesDir(com.pocketqc.autolabel.export.DatasetSplit.TRAIN), "biscuit_approved_01.jpg")
        val exportedLabel = File(result.getSplitLabelsDir(com.pocketqc.autolabel.export.DatasetSplit.TRAIN), "biscuit_approved_01.txt")
        assertTrue(exportedImg.exists())
        assertTrue(exportedLabel.exists())

        val lines = exportedLabel.readLines()
        assertEquals(2, lines.size)
        // First box: class 0 (Intact), cx=0.5, cy=0.5, w=0.6, h=0.6
        assertEquals("0 0.500000 0.500000 0.600000 0.600000", lines[0])
        // Second box: class 1 (Fracture), cx=0.25, cy=0.25, w=0.3, h=0.3
        assertEquals("1 0.250000 0.250000 0.300000 0.300000", lines[1])

        // Verify dataset.yaml points to disjoint partitions
        assertTrue(result.datasetYamlFile.exists())
        val yamlText = result.datasetYamlFile.readText()
        assertTrue(yamlText.contains("train: images/train"))
        assertTrue(yamlText.contains("val: images/val"))
        assertTrue(yamlText.contains("test: images/test"))
        assertTrue(yamlText.contains("0: Intact"))
        assertTrue(yamlText.contains("1: Fracture"))
        assertTrue(yamlText.contains("2: Broken"))

        // Verify manifest.json
        assertTrue(result.manifestJsonFile.exists())
        val manifestObj = JSONObject(result.manifestJsonFile.readText())
        assertEquals(1, manifestObj.getInt("totalExportedImages"))
        assertEquals(2, manifestObj.getInt("totalExportedBoxes"))
    }

    // =========================================================================
    // 8. Experimental Data Cleanup
    // =========================================================================

    @Test
    fun testExperimentalWorkspaceIsolationAndCleanup() {
        val baseDir = tempFolder.newFolder("app_files_cleanup")
        val workspace = createTestWorkspace(baseDir)

        // Populate files
        val dummyImg = File(workspace.imagesDir, "test.jpg").apply { writeText("img") }
        val dummyAnnot = File(workspace.annotationsDir, "annotations.json").apply { writeText("[]") }
        val dummyExport = File(workspace.yoloExportDir, "sample.txt").apply { writeText("txt") }

        assertTrue(dummyImg.exists())
        assertTrue(dummyAnnot.exists())
        assertTrue(dummyExport.exists())

        // Clear workspace
        workspace.clearWorkspace()

        assertFalse(dummyImg.exists())
        assertFalse(dummyAnnot.exists())
        assertFalse(dummyExport.exists())

        // Directories reinitialized
        assertTrue(workspace.imagesDir.exists())
        assertTrue(workspace.annotationsDir.exists())
        assertTrue(workspace.yoloExportDir.exists())
    }

    // =========================================================================
    // 9. Production Model Immutability & Hash Verification
    // =========================================================================

    @Test
    fun testProductionModelAssetImmutability() {
        assertTrue("Production model biscuits_final.onnx must exist", productionModelAssetFile.exists())
        val currentHash = getFileSha256(productionModelAssetFile)
        assertEquals(
            "Production model SHA-256 hash must remain exactly identical to recorded baseline",
            expectedProductionHash.uppercase(),
            currentHash.uppercase()
        )
    }

    // =========================================================================
    // 10. Benchmark Ground Truth Quality Evaluation
    // =========================================================================

    @Test
    fun testBenchmarkEvaluationObjectiveMetrics() {
        val groundTruth = mapOf(
            "biscuit_intact" to listOf(
                ProposedBoxAnnotation(
                    boxId = "gt_1",
                    boundingBox = BoundingBox(0.20f, 0.30f, 0.70f, 0.80f),
                    predictedClass = AutoLabelClass.INTACT,
                    confidence = 1.0f,
                    assignedClass = AutoLabelClass.INTACT,
                    isHumanVerified = true
                )
            )
        )

        // Prediction closely matching ground truth
        val matchingItem = AutoLabelImageItem(
            id = "bench-1",
            fileHash = "h1",
            fileName = "biscuit_intact_sample.jpg",
            filePath = "/mock/intact.jpg",
            source = "BENCHMARK",
            imageWidth = 640,
            imageHeight = 640,
            annotations = listOf(
                ProposedBoxAnnotation(
                    boxId = "pred_1",
                    boundingBox = BoundingBox(0.21f, 0.31f, 0.69f, 0.79f), // IoU ~ 0.90
                    predictedClass = AutoLabelClass.INTACT,
                    confidence = 0.95f,
                    assignedClass = AutoLabelClass.INTACT
                )
            )
        )

        val report = AutoLabelEvaluator.evaluate(
            items = listOf(matchingItem),
            groundTruthMap = groundTruth
        )

        assertNotNull(report.benchmarkEvaluation)
        val b = report.benchmarkEvaluation!!
        assertEquals(1, b.matchedBoxCount)
        assertTrue("Mean IoU should be high (> 0.85)", b.meanIoU > 0.85f)
        assertEquals(1.0f, b.localizationPrecision, 0.001f)
        assertEquals(1.0f, b.localizationRecall, 0.001f)
        assertEquals(1.0f, b.classificationAgreementRate, 0.001f)
    }

    // =========================================================================
    // 11. Comprehensive Partitioning, Leakage Prevention & Self-Validation Tests
    // =========================================================================

    private fun createApprovedSample(
        id: String,
        hash: String,
        timestamp: Long,
        source: String = "CAMERA",
        classId: Int = 0,
        box: BoundingBox = BoundingBox(0.2f, 0.2f, 0.8f, 0.8f),
        imageDir: File
    ): AutoLabelImageItem {
        val f = File(imageDir, "$id.jpg")
        if (!f.exists()) f.writeBytes(ByteArray(64))
        val autoClass = AutoLabelClass.fromId(classId) ?: AutoLabelClass.INTACT
        return AutoLabelImageItem(
            id = id,
            fileHash = hash,
            fileName = "$id.jpg",
            filePath = f.absolutePath,
            source = source,
            imageWidth = 640,
            imageHeight = 640,
            reviewStatus = AnnotationStatus.APPROVED,
            timestamp = timestamp,
            annotations = listOf(
                ProposedBoxAnnotation(
                    boxId = "box_$id",
                    boundingBox = box,
                    predictedClass = autoClass,
                    confidence = 0.95f,
                    assignedClass = autoClass,
                    isHumanVerified = true
                )
            )
        )
    }

    @Test
    fun testDeterministicSplitting() {
        val imgDir = tempFolder.newFolder("det_img")
        val items = (1..15).map { i ->
            createApprovedSample(
                id = "det_$i",
                hash = "hash_%04d".format(i),
                timestamp = 10000L + (i * 10000L), // non-burst
                classId = i % 3,
                imageDir = imgDir
            )
        }

        val groups1 = YoloDatasetExporter.groupItems(items)
        val (splits1, _) = YoloDatasetExporter.partitionGroups(groups1)

        val groups2 = YoloDatasetExporter.groupItems(items)
        val (splits2, _) = YoloDatasetExporter.partitionGroups(groups2)

        assertEquals(splits1[com.pocketqc.autolabel.export.DatasetSplit.TRAIN]?.map { it.id },
            splits2[com.pocketqc.autolabel.export.DatasetSplit.TRAIN]?.map { it.id })
        assertEquals(splits1[com.pocketqc.autolabel.export.DatasetSplit.VAL]?.map { it.id },
            splits2[com.pocketqc.autolabel.export.DatasetSplit.VAL]?.map { it.id })
        assertEquals(splits1[com.pocketqc.autolabel.export.DatasetSplit.TEST]?.map { it.id },
            splits2[com.pocketqc.autolabel.export.DatasetSplit.TEST]?.map { it.id })
    }

    @Test
    fun testCorrectSplitRatiosWhenSamplePermits() {
        val imgDir = tempFolder.newFolder("ratio_img")
        val outDir = tempFolder.newFolder("ratio_out")
        val items = (1..20).map { i ->
            createApprovedSample(
                id = "ratio_$i",
                hash = "hash_ratio_%04d".format(i),
                timestamp = 100000L + (i * 20000L),
                classId = i % 3,
                imageDir = imgDir
            )
        }

        val result = YoloDatasetExporter.exportToYolo(items, outDir)
        assertTrue("Validation errors: " + result.validationErrors.joinToString("; "), result.isStructurallyValid)
        assertEquals(20, result.exportedImageCount)

        val trainCount = result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.TRAIN]?.imageCount ?: 0
        val valCount = result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.VAL]?.imageCount ?: 0
        val testCount = result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.TEST]?.imageCount ?: 0

        // 70% train = 14, 20% val = 4, 10% test = 2
        assertEquals(14, trainCount)
        assertEquals(4, valCount)
        assertEquals(2, testCount)
        assertEquals(20, trainCount + valCount + testCount)
    }

    @Test
    fun testGroupIsolationAcrossPartitions() {
        val imgDir = tempFolder.newFolder("grp_img")
        val outDir = tempFolder.newFolder("grp_out")

        // Create 2 burst items (timestamps 1000 and 1500, <= 3000ms window)
        val burst1 = createApprovedSample("burst_1", "h_b1", 1000L, source = "CAMERA", imageDir = imgDir)
        val burst2 = createApprovedSample("burst_2", "h_b2", 1500L, source = "CAMERA", imageDir = imgDir)

        // Create other independent samples
        val others = (3..12).map { i ->
            createApprovedSample("other_$i", "h_o$i", 100000L + (i * 10000L), source = "CAMERA", imageDir = imgDir)
        }

        val allItems = listOf(burst1, burst2) + others
        val result = YoloDatasetExporter.exportToYolo(allItems, outDir)

        assertTrue(result.isStructurallyValid)

        // Find which partition burst_1 and burst_2 landed in
        var split1: com.pocketqc.autolabel.export.DatasetSplit? = null
        var split2: com.pocketqc.autolabel.export.DatasetSplit? = null

        for ((split, summary) in result.splitSummaries) {
            val dir = result.getSplitImagesDir(split)
            if (File(dir, "burst_1.jpg").exists()) split1 = split
            if (File(dir, "burst_2.jpg").exists()) split2 = split
        }

        assertNotNull(split1)
        assertNotNull(split2)
        assertEquals("Burst images must remain in the same partition", split1, split2)
    }

    @Test
    fun testDuplicateHashIsolationAcrossPartitions() {
        val imgDir = tempFolder.newFolder("dup_img")
        val outDir = tempFolder.newFolder("dup_out")

        // Two items with the exact same content hash but different timestamps & filenames
        val dup1 = createApprovedSample("dup_1", "SAME_CONTENT_HASH_ABC", 10000L, imageDir = imgDir)
        val dup2 = createApprovedSample("dup_2", "SAME_CONTENT_HASH_ABC", 90000L, imageDir = imgDir)

        val others = (3..14).map { i ->
            createApprovedSample("indep_$i", "hash_uniq_$i", 200000L + (i * 10000L), imageDir = imgDir)
        }

        val allItems = listOf(dup1, dup2) + others
        val result = YoloDatasetExporter.exportToYolo(allItems, outDir)

        assertTrue(result.isStructurallyValid)

        var dupSplit1: com.pocketqc.autolabel.export.DatasetSplit? = null
        var dupSplit2: com.pocketqc.autolabel.export.DatasetSplit? = null

        for (split in com.pocketqc.autolabel.export.DatasetSplit.entries) {
            val dir = result.getSplitImagesDir(split)
            if (File(dir, "dup_1.jpg").exists()) dupSplit1 = split
            if (File(dir, "dup_2.jpg").exists()) dupSplit2 = split
        }

        assertEquals("Duplicate hash images must be in identical partition", dupSplit1, dupSplit2)
    }

    @Test
    fun testSmallDatasetWarningAndMissingClasses() {
        val imgDir = tempFolder.newFolder("small_img")
        val outDir = tempFolder.newFolder("small_out")

        // Only 2 samples, only class 0 (Intact)
        val sample1 = createApprovedSample("s1", "h1", 1000L, classId = 0, imageDir = imgDir)
        val sample2 = createApprovedSample("s2", "h2", 20000L, classId = 0, imageDir = imgDir)

        val result = YoloDatasetExporter.exportToYolo(listOf(sample1, sample2), outDir)
        assertTrue(result.isStructurallyValid)
        assertEquals(2, result.exportedImageCount)

        // All 2 must be in train; val and test empty
        assertEquals(2, result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.TRAIN]?.imageCount)
        assertEquals(0, result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.VAL]?.imageCount)
        assertEquals(0, result.splitSummaries[com.pocketqc.autolabel.export.DatasetSplit.TEST]?.imageCount)

        // Check warnings for small dataset
        assertTrue(result.warnings.any { it.contains("approved sample") && it.contains("3") })
    }

    @Test
    fun testEmptyLabelImageExport() {
        val imgDir = tempFolder.newFolder("empty_img")
        val outDir = tempFolder.newFolder("empty_out")

        val f = File(imgDir, "background.jpg").apply { writeBytes(ByteArray(64)) }
        val emptyItem = AutoLabelImageItem(
            id = "bg_1",
            fileHash = "bg_hash",
            fileName = "background.jpg",
            filePath = f.absolutePath,
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640,
            reviewStatus = AnnotationStatus.APPROVED,
            annotations = emptyList() // No boxes
        )

        val result = YoloDatasetExporter.exportToYolo(listOf(emptyItem), outDir)
        assertTrue(result.isStructurallyValid)
        assertEquals(1, result.exportedImageCount)
        assertEquals(0, result.exportedBoxCount)

        val labelFile = File(result.getSplitLabelsDir(com.pocketqc.autolabel.export.DatasetSplit.TRAIN), "background.txt")
        assertTrue(labelFile.exists())
        assertEquals(0, labelFile.length()) // Standard YOLO empty label file for negative background
    }

    @Test
    fun testInvalidCoordinatesAndClassIdsExcluded() {
        val imgDir = tempFolder.newFolder("invalid_img")
        val outDir = tempFolder.newFolder("invalid_out")

        val f = File(imgDir, "invalid.jpg").apply { writeBytes(ByteArray(64)) }
        val item = AutoLabelImageItem(
            id = "inv_1",
            fileHash = "inv_hash",
            fileName = "invalid.jpg",
            filePath = f.absolutePath,
            source = "CAMERA",
            imageWidth = 640,
            imageHeight = 640,
            reviewStatus = AnnotationStatus.APPROVED,
            annotations = listOf(
                // Inverted box (width <= 0)
                ProposedBoxAnnotation("b_inv1", BoundingBox(0.8f, 0.8f, 0.2f, 0.2f), AutoLabelClass.INTACT, 0.9f, isHumanVerified = true),
                // Valid box
                ProposedBoxAnnotation("b_val", BoundingBox(0.2f, 0.2f, 0.6f, 0.6f), AutoLabelClass.INTACT, 0.9f, isHumanVerified = true),
                // Unverified box (must be skipped)
                ProposedBoxAnnotation("b_unver", BoundingBox(0.1f, 0.1f, 0.4f, 0.4f), AutoLabelClass.FRACTURE, 0.9f, isHumanVerified = false)
            )
        )

        val result = YoloDatasetExporter.exportToYolo(listOf(item), outDir)
        // 1 box exported out of 3
        assertEquals(1, result.exportedBoxCount)
        assertTrue(result.validationErrors.any { it.contains("Degenerate box") })
        assertTrue(result.validationErrors.any { it.contains("Unverified box") })
    }

    @Test
    fun testApprovedOnlyExport() {
        val imgDir = tempFolder.newFolder("approved_img")
        val outDir = tempFolder.newFolder("approved_out")

        val approved = createApprovedSample("appr", "h1", 1000L, imageDir = imgDir)
        val pending = createApprovedSample("pend", "h2", 2000L, imageDir = imgDir).copy(reviewStatus = AnnotationStatus.PENDING)
        val rejected = createApprovedSample("rej", "h3", 3000L, imageDir = imgDir).copy(reviewStatus = AnnotationStatus.REJECTED)
        val reviewReq = createApprovedSample("rev", "h4", 4000L, imageDir = imgDir).copy(reviewStatus = AnnotationStatus.REVIEW_REQUIRED)

        val result = YoloDatasetExporter.exportToYolo(listOf(approved, pending, rejected, reviewReq), outDir)
        assertEquals(1, result.exportedImageCount)
        assertEquals(3, result.skippedUnapprovedCount)
    }

    @Test
    fun testCorrectImageLabelPairingAndNoOrphanFiles() {
        val imgDir = tempFolder.newFolder("pair_img")
        val outDir = tempFolder.newFolder("pair_out")
        val items = (1..6).map { i ->
            createApprovedSample("pair_$i", "h_pair_$i", 10000L + (i * 5000L), classId = i % 3, imageDir = imgDir)
        }

        val result = YoloDatasetExporter.exportToYolo(items, outDir)
        assertTrue(result.isStructurallyValid)

        val validation = YoloDatasetExporter.validateExportedDataset(outDir)
        assertTrue("Exported dataset must pass self-validation", validation.isValid)
        assertTrue(validation.errors.isEmpty())
    }

    @Test
    fun testYamlValidityAndDirectoryExistence() {
        val imgDir = tempFolder.newFolder("yaml_img")
        val outDir = tempFolder.newFolder("yaml_out")
        val items = listOf(createApprovedSample("y1", "hy1", 1000L, imageDir = imgDir))

        val result = YoloDatasetExporter.exportToYolo(items, outDir)
        val yaml = result.datasetYamlFile.readText()

        assertTrue(yaml.contains("train: images/train"))
        assertTrue(yaml.contains("val: images/val"))
        assertTrue(yaml.contains("test: images/test"))

        for (split in com.pocketqc.autolabel.export.DatasetSplit.entries) {
            assertTrue(File(outDir, "images/${split.dirName}").exists())
            assertTrue(File(outDir, "labels/${split.dirName}").exists())
        }
    }

    @Test
    fun testExportValidationFailureBehavior() {
        val badDir = tempFolder.newFolder("bad_export")
        // Malformed dataset.yaml pointing to the SAME directory for train and val
        val yaml = File(badDir, "dataset.yaml")
        yaml.writeText("train: images\nval: images\ntest: images\n")

        val report = YoloDatasetExporter.validateExportedDataset(badDir)
        assertFalse("Validator must catch data leakage in yaml", report.isValid)
        assertTrue(report.errors.any { it.contains("Critical data leakage") })
    }

    @Test
    fun testPreserveExistingExportViaBackup() {
        val imgDir = tempFolder.newFolder("backup_img")
        val outDir = tempFolder.newFolder("backup_out")
        val item1 = createApprovedSample("b1", "hb1", 1000L, imageDir = imgDir)

        // First export
        val result1 = YoloDatasetExporter.exportToYolo(listOf(item1), outDir)
        assertNull(result1.backupDir)

        // Second export into same directory
        val item2 = createApprovedSample("b2", "hb2", 5000L, imageDir = imgDir)
        val result2 = YoloDatasetExporter.exportToYolo(listOf(item1, item2), outDir)
        assertNotNull("Previous export must be backed up", result2.backupDir)
        assertTrue(result2.backupDir!!.exists())
        assertTrue(File(result2.backupDir, "dataset.yaml").exists())
    }
}

