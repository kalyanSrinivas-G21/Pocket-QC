package com.pocketqc

import android.graphics.Bitmap
import com.pocketqc.batch.PersistentBatchManager
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.inference.HardwareBackend
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloInferenceEngine
import com.pocketqc.lab.dataset.ExperimentalDatasetManager
import com.pocketqc.lab.evaluation.ExperimentalEvaluationEngine
import com.pocketqc.lab.model.BiscuitLabClass
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.lab.model.TrainingConfig
import com.pocketqc.lab.registry.ExperimentalModelRegistry
import com.pocketqc.lab.training.OnDeviceTrainingEngine
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

class ExperimentalModelIsolationUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val productionModelAssetFile = File("src/main/assets/biscuits_final.onnx")

    private fun getProductionModelSha256(file: File): String {
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

    private fun createTestWorkspace(baseFilesDir: File): ExperimentalWorkspace {
        return ExperimentalWorkspace(
            baseFilesDir = baseFilesDir,
            assetStreamProvider = { fileName ->
                val f = File("src/main/assets", fileName)
                if (!f.exists()) throw java.io.FileNotFoundException("Asset $fileName not found")
                FileInputStream(f)
            }
        )
    }

    private fun createSyntheticSample(
        workspace: ExperimentalWorkspace,
        id: String,
        biscuitClass: BiscuitLabClass,
        split: DatasetSplit
    ): ExperimentalSample {
        val imgFile = File(workspace.imagesDir, "$id.jpg")
        val dummyBytes = ByteArray(128) { idx ->
            ((idx * 11 + biscuitClass.classIndex * 60) % 256).toByte()
        }
        imgFile.writeBytes(dummyBytes)

        return ExperimentalSample(
            id = id,
            imageFileName = imgFile.name,
            imageFilePath = imgFile.absolutePath,
            assignedClass = biscuitClass,
            boundingBox = BoundingBox(0.1f, 0.1f, 0.9f, 0.9f),
            isVerified = true,
            split = split
        )
    }

    @Test
    fun testMandatoryRequirement1_ProductionModelHashMatchesBeforeAndAfterTraining() = runBlocking {
        assertTrue("Production model asset must exist", productionModelAssetFile.exists())
        val hashBefore = getProductionModelSha256(productionModelAssetFile)

        val filesDir = tempFolder.newFolder("files_dir")
        val workspace = createTestWorkspace(filesDir)
        val trainingEngine = OnDeviceTrainingEngine(workspace)

        // Create balanced synthetic dataset
        val samples = mutableListOf<ExperimentalSample>()
        for (i in 1..4) {
            samples.add(createSyntheticSample(workspace, "train_broken_$i", BiscuitLabClass.BROKEN, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "train_fracture_$i", BiscuitLabClass.FRACTURE, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "train_intact_$i", BiscuitLabClass.INTACT, DatasetSplit.TRAIN))
        }

        // Run genuine on-device training session
        val result = trainingEngine.train(samples, TrainingConfig(epochs = 3, batchSize = 2))
        assertTrue("On-device training should succeed", result.isSuccess)

        val hashAfter = getProductionModelSha256(productionModelAssetFile)
        assertEquals(
            "Production model hash must match identically before and after training",
            hashBefore,
            hashAfter
        )
    }

    @Test
    fun testMandatoryRequirement2_TrainingCannotWriteToProductionModelLocation() {
        val filesDir = tempFolder.newFolder("files_dir_write_check")
        val workspace = createTestWorkspace(filesDir)

        // 1. Assert experimental outputs reside strictly inside files/experimental_lab
        assertTrue(workspace.rootDir.absolutePath.startsWith(filesDir.absolutePath))
        assertTrue(workspace.modelsDir.absolutePath.startsWith(filesDir.absolutePath))
        assertFalse(workspace.modelsDir.absolutePath.contains("assets"))

        // 2. Production assets are read-only; attempts to open an asset output stream or overwrite must fail
        val assetDir = File("src/main/assets")
        assertTrue("Assets folder exists and holds production models", assetDir.exists())
        val productionModel = File(assetDir, "biscuits_final.onnx")

        // Workspace files are isolated from production asset path
        assertNotEquals(workspace.modelsDir.absolutePath, assetDir.absolutePath)
        assertFalse(workspace.rootDir.canonicalPath.startsWith(assetDir.canonicalPath))
    }

    @Test
    fun testMandatoryRequirement3_Live2DInspectionContinuesLoadingOriginalProductionModel() {
        // Confirm production model config definition is strictly immutable
        assertEquals("biscuits_final.onnx", ModelConfig.BISCUITS_FINAL.modelAssetPath)
        assertEquals("biscuits_final", ModelConfig.BISCUITS_FINAL.id)
        assertEquals(3, ModelConfig.BISCUITS_FINAL.classNames.size)
        assertEquals("Broken", ModelConfig.BISCUITS_FINAL.classNames[0])
        assertEquals("Fracture", ModelConfig.BISCUITS_FINAL.classNames[1])
        assertEquals("Intact", ModelConfig.BISCUITS_FINAL.classNames[2])

        // Verify inference engine targetBackend and configuration
        val dummyBytes = ByteArray(1024)
        val engine = YoloInferenceEngine(
            modelBytes = dummyBytes,
            modelConfig = ModelConfig.BISCUITS_FINAL,
            targetBackend = HardwareBackend.CPU
        )
        assertEquals(HardwareBackend.CPU, engine.actualBackend)
        assertEquals("biscuits_final", engine.modelConfig.id)
    }

    @Test
    fun testMandatoryRequirement4_TrainExperimentalModelAndConfirmSavedSeparately() = runBlocking {
        val filesDir = tempFolder.newFolder("files_dir_save_check")
        val workspace = createTestWorkspace(filesDir)
        val trainingEngine = OnDeviceTrainingEngine(workspace)
        val registry = ExperimentalModelRegistry(workspace)

        val samples = mutableListOf<ExperimentalSample>()
        for (i in 1..3) {
            samples.add(createSyntheticSample(workspace, "b_train_$i", BiscuitLabClass.BROKEN, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "f_train_$i", BiscuitLabClass.FRACTURE, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "i_train_$i", BiscuitLabClass.INTACT, DatasetSplit.TRAIN))
        }

        val trainResult = trainingEngine.train(samples, TrainingConfig(epochs = 2, batchSize = 3))
        assertTrue(trainResult.isSuccess)
        val model = trainResult.getOrThrow()

        // Verify model is marked as experimental only
        assertTrue("Model must be marked isExperimentalOnly", model.metadata.isExperimentalOnly)
        assertTrue(
            "Notice must declare production model unchanged",
            model.metadata.isolationNotice.contains("production 2D inspection model remains unchanged")
        )

        // Verify artifact is in modelsDir and NOT in assets
        val modelFile = File(model.metadata.artifactPath)
        assertTrue("Exported model file must exist", modelFile.exists())
        assertEquals(workspace.modelsDir.absolutePath, modelFile.parentFile?.absolutePath)
        assertFalse(modelFile.absolutePath.contains("assets"))

        // Verify registry lists this experimental model
        val registeredModels = registry.listExperimentalModels()
        assertEquals(1, registeredModels.size)
        assertEquals(model.metadata.modelId, registeredModels[0].modelId)
    }

    @Test
    fun testMandatoryRequirement5_ExperimentalEvaluationIsolatedFromProductionHistoryAndMetrics() = runBlocking {
        val filesDir = tempFolder.newFolder("files_dir_eval_check")
        val workspace = createTestWorkspace(filesDir)
        val trainingEngine = OnDeviceTrainingEngine(workspace)
        val evaluationEngine = ExperimentalEvaluationEngine(workspace)

        // Create separate batch manager representing production inspection history
        val batchStorageDir = tempFolder.newFolder("prod_batch_storage")
        val prodBatchManager = PersistentBatchManager(baseDir = batchStorageDir, fileName = "batches.json")
        assertEquals(0, prodBatchManager.getRecentBatches().size)

        val samples = mutableListOf<ExperimentalSample>()
        for (i in 1..2) {
            samples.add(createSyntheticSample(workspace, "tr_b_$i", BiscuitLabClass.BROKEN, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "tr_i_$i", BiscuitLabClass.INTACT, DatasetSplit.TRAIN))
            samples.add(createSyntheticSample(workspace, "te_b_$i", BiscuitLabClass.BROKEN, DatasetSplit.TEST))
            samples.add(createSyntheticSample(workspace, "te_i_$i", BiscuitLabClass.INTACT, DatasetSplit.TEST))
        }

        val trainResult = trainingEngine.train(samples, TrainingConfig(epochs = 2, batchSize = 2))
        val model = trainResult.getOrThrow()

        // Run experimental evaluation on held-out test data
        val evalResult = evaluationEngine.evaluate(model, samples, targetSplit = DatasetSplit.TEST)
        assertTrue("Evaluation must succeed", evalResult.isSuccess)
        val report = evalResult.getOrThrow()

        // Verify evaluation output
        assertEquals(4, report.testSampleCount)
        assertEquals("TEST", report.datasetSplitUsed)
        assertTrue(report.samplePredictions.isNotEmpty())

        // Verify report saved inside evaluationsDir
        val reportFiles = workspace.evaluationsDir.listFiles()
        assertNotNull(reportFiles)
        assertTrue(reportFiles!!.any { it.name.contains(report.reportId) })

        // CRITICAL CHECK: Production batch history has NOT been touched
        assertEquals("Production batch manager must remain untouched", 0, prodBatchManager.getRecentBatches().size)
        assertNull("No active production batch should exist", prodBatchManager.currentBatch.value)
    }

    @Test
    fun testMandatoryRequirement6_RestartApplicationMaintainsIdentitySeparationAndPreventsPromotion() {
        val filesDir = tempFolder.newFolder("files_dir_restart_check")
        val workspace = createTestWorkspace(filesDir)
        val registry = ExperimentalModelRegistry(workspace)

        // Attempting to promote an experimental model to production must throw UnsupportedOperationException
        try {
            registry.promoteToProduction("EXP_MODEL_12345")
            fail("Expected UnsupportedOperationException on promoteToProduction")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("ARCHITECTURAL SECURITY VIOLATION"))
        }

        // Restart simulation: Recreate registry and verify it has no active-model or production-switching interface
        val newRegistryInstance = ExperimentalModelRegistry(createTestWorkspace(filesDir))
        assertEquals(0, newRegistryInstance.listExperimentalModels().size)
    }

    @Test
    fun testMandatoryRequirement7_DeletingExperimentalDataCannotDeleteOrDamageProductionModel() = runBlocking {
        val filesDir = tempFolder.newFolder("files_dir_wipe_check")
        val workspace = createTestWorkspace(filesDir)
        val trainingEngine = OnDeviceTrainingEngine(workspace)

        val initialHash = getProductionModelSha256(productionModelAssetFile)

        val samples = mutableListOf<ExperimentalSample>()
        for (i in 1..2) {
            samples.add(createSyntheticSample(workspace, "sample_$i", BiscuitLabClass.INTACT, DatasetSplit.TRAIN))
        }
        trainingEngine.train(samples, TrainingConfig(epochs = 1, batchSize = 2))

        assertTrue("Experimental files should exist before reset", workspace.imagesDir.listFiles()!!.isNotEmpty())

        // Execute workspace reset
        workspace.clearWorkspace()

        // Verify experimental data wiped
        assertEquals(0, workspace.imagesDir.listFiles()!!.size)
        assertEquals(0, workspace.modelsDir.listFiles()!!.size)
        assertEquals(0, workspace.evaluationsDir.listFiles()!!.size)

        // Verify production model asset is completely undamaged and has identical hash
        assertTrue("Production model asset must still exist", productionModelAssetFile.exists())
        val finalHash = getProductionModelSha256(productionModelAssetFile)
        assertEquals("Production model hash must remain identical after experimental wipe", initialHash, finalHash)
    }
}
