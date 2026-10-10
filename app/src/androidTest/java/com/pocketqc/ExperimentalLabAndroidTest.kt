package com.pocketqc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketqc.lab.dataset.ExperimentalDatasetManager
import com.pocketqc.lab.evaluation.ExperimentalEvaluationEngine
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.TrainingConfig
import com.pocketqc.lab.model.TrainingProgress
import com.pocketqc.lab.registry.ExperimentalModelRegistry
import com.pocketqc.lab.training.OnDeviceTrainingEngine
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentalLabAndroidTest {

    private val expectedProductionSha256 = "92D96E80AF5C8E897459B74C58862CE201DBE09CDAA41F0FD03EDA6A4A2EDF08"

    @Test
    fun testEndToEndExperimentalLabOnDeviceExecutionAndStrictIsolation() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = ExperimentalWorkspace(appContext)
        val datasetManager = ExperimentalDatasetManager(appContext, workspace)
        val trainingEngine = OnDeviceTrainingEngine(workspace)
        val evaluationEngine = ExperimentalEvaluationEngine(workspace)
        val registry = ExperimentalModelRegistry(workspace)

        println("=== POCKET QC EXPERIMENTAL LAB ON-DEVICE INSTRUMENTATION TEST ===")
        println("Target Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
        println("Files Dir: ${appContext.filesDir.absolutePath}")

        // 1. Verify initial production model SHA-256 before any operations
        val initialProductionHash = workspace.computeProductionModelSha256()
        println("Initial Production Model Asset Hash: $initialProductionHash")
        assertEquals(
            "Production model asset hash must match expected production ONNX hash",
            expectedProductionSha256,
            initialProductionHash
        )

        // 2. Clear any prior experimental workspace residue
        workspace.clearWorkspace()
        assertEquals(0, workspace.imagesDir.listFiles()?.size ?: 0)
        assertEquals(0, workspace.modelsDir.listFiles()?.size ?: 0)

        // 3. Import baseline dataset from assets to private storage without touching assets
        val importedSamples = datasetManager.importStandardBiscuitSamples()
        println("Imported ${importedSamples.size} samples to private storage: ${workspace.imagesDir.absolutePath}")
        assertTrue("At least 3 asset samples should be imported", importedSamples.size >= 3)
        assertTrue("Images must exist in private storage", workspace.imagesDir.listFiles()!!.isNotEmpty())

        // 4. Verify split partitioning
        val trainSamples = importedSamples.filter { it.split == DatasetSplit.TRAIN }
        val testSamples = importedSamples.filter { it.split == DatasetSplit.TEST }
        println("Split distribution -> Train: ${trainSamples.size}, Test: ${testSamples.size}")

        // 5. Run on-device training
        val trainingConfig = TrainingConfig(
            epochs = 5,
            learningRate = 0.05f,
            batchSize = 2,
            hiddenUnits = 32
        )
        var lastProgress: TrainingProgress? = null
        val trainResult = trainingEngine.train(
            samples = importedSamples,
            config = trainingConfig,
            onProgress = { lastProgress = it }
        )

        assertTrue("Training should succeed: ${trainResult.exceptionOrNull()?.message}", trainResult.isSuccess)
        val experimentalModel = trainResult.getOrThrow()
        val metadata = experimentalModel.metadata

        println("Training completed: Model ID = ${metadata.modelId}")
        println("Epochs executed: ${lastProgress?.currentEpoch}, Final Train Loss: ${lastProgress?.trainLoss}")
        assertEquals(5, lastProgress?.currentEpoch)
        assertTrue("Model must be registered in workspace models dir", metadata.artifactPath.contains("experimental_lab/models"))

        // 6. Verify production model hash remains UNCHANGED after training
        val postTrainingHash = workspace.computeProductionModelSha256()
        println("Post-Training Production Model Asset Hash: $postTrainingHash")
        assertEquals(
            "CRITICAL: Production model hash must NOT change after on-device training",
            initialProductionHash,
            postTrainingHash
        )

        // 7. Verify model export and listing in registry
        val registeredModels = registry.listExperimentalModels()
        println("Registered experimental models: ${registeredModels.size}")
        assertTrue("Registry must list the newly trained experimental model", registeredModels.any { it.modelId == metadata.modelId })

        // 8. Run on-device evaluation on held-out split
        val evalResult = evaluationEngine.evaluate(experimentalModel, importedSamples, DatasetSplit.TEST)
        assertTrue("Evaluation should succeed: ${evalResult.exceptionOrNull()?.message}", evalResult.isSuccess)
        val evalReport = evalResult.getOrThrow()

        println("Evaluation Report: Tested ${evalReport.testSampleCount} samples, Overall Accuracy: ${"%.2f".format(evalReport.overallAccuracy * 100)}%")
        assertNotNull(evalReport)
        assertTrue("Must test at least 1 sample", evalReport.testSampleCount > 0)

        // 8b. Test Interactive Single-Image Live Classification Workflow
        val testImageFile = File(importedSamples.first().imageFilePath)
        val testBitmap = android.graphics.BitmapFactory.decodeFile(testImageFile.absolutePath)
        val singlePrediction = experimentalModel.predictImageRegion(testBitmap, com.pocketqc.core.model.BoundingBox(0.15f, 0.15f, 0.85f, 0.85f))
        println("Interactive Single-Image Test Prediction: class=${singlePrediction.predictedClass}, conf=${singlePrediction.confidence}")
        assertNotNull(singlePrediction.predictedClass)
        assertTrue("Confidence must be between 0 and 1", singlePrediction.confidence in 0f..1f)
        assertEquals(3, singlePrediction.probabilities.size)

        // 9. Verify promotion barrier: promoteToProduction MUST throw UnsupportedOperationException
        try {
            registry.promoteToProduction(metadata.modelId)
            fail("promoteToProduction MUST throw UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            println("Verified promotion barrier: ${e.message}")
            assertTrue(e.message!!.contains("ARCHITECTURAL SECURITY VIOLATION"))
        }

        // 10. Perform Isolation Audit
        val audit = workspace.performIsolationAudit(initialProductionHash)
        println("Isolation Audit: isIsolated=${audit.isDirectoryIsolated}, hashesMatch=${audit.isHashIdentical}")
        assertTrue("Experimental workspace must pass directory isolation audit", audit.isDirectoryIsolated)
        assertTrue("Production model hash must match initial hash", audit.isHashIdentical)
        assertTrue("Production asset must remain immutable", audit.isAssetImmutable)

        // 11. Clean up workspace and confirm production asset remains unharmed
        workspace.clearWorkspace()
        assertEquals(0, workspace.imagesDir.listFiles()?.size ?: 0)
        val finalHash = workspace.computeProductionModelSha256()
        assertEquals("Production model hash must remain immutable after workspace wipe", initialProductionHash, finalHash)
        println("=== ON-DEVICE EXPERIMENTAL LAB VERIFICATION PASSED SUCCESSFULLY ===")
    }
}
