package com.pocketqc.lab.model

import com.pocketqc.core.model.BoundingBox

/**
 * Data split assignment for experimental training and evaluation.
 */
enum class DatasetSplit {
    TRAIN,
    VAL,
    TEST
}

/**
 * Supported biscuit defect classes matching production label space.
 */
enum class BiscuitLabClass(val label: String, val classIndex: Int) {
    BROKEN("Broken", 0),
    FRACTURE("Fracture", 1),
    INTACT("Intact", 2);

    companion object {
        fun fromLabel(label: String): BiscuitLabClass {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: INTACT
        }

        fun fromIndex(index: Int): BiscuitLabClass {
            return entries.firstOrNull { it.classIndex == index } ?: INTACT
        }
    }
}

/**
 * An annotated biscuit image sample in the experimental workspace.
 */
data class ExperimentalSample(
    val id: String,
    val imageFileName: String,
    val imageFilePath: String,
    val assignedClass: BiscuitLabClass,
    val boundingBox: BoundingBox,
    val isVerified: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val split: DatasetSplit = DatasetSplit.TRAIN
)

/**
 * Configuration hyperparameters for on-device training.
 */
data class TrainingConfig(
    val epochs: Int = 20,
    val learningRate: Float = 0.03f,
    val batchSize: Int = 8,
    val hiddenUnits: Int = 32,
    val momentum: Float = 0.9f,
    val weightDecay: Float = 0.001f
)

/**
 * Live training status emitted per epoch.
 */
data class TrainingProgress(
    val currentEpoch: Int,
    val totalEpochs: Int,
    val trainLoss: Float,
    val valLoss: Float,
    val trainAccuracy: Float,
    val valAccuracy: Float,
    val isCompleted: Boolean = false,
    val error: String? = null
)

/**
 * Historical log entry for a single epoch.
 */
data class EpochLog(
    val epoch: Int,
    val trainLoss: Float,
    val valLoss: Float,
    val trainAccuracy: Float,
    val valAccuracy: Float,
    val epochDurationMs: Long
)

/**
 * Metadata descriptor for an experimental model artifact.
 * Crucially marked with isExperimentalOnly = true and immutable production identity.
 */
data class ExperimentalModelMetadata(
    val modelId: String,
    val version: Int,
    val creationTimestamp: Long,
    val trainSampleCount: Int,
    val valSampleCount: Int,
    val epochs: Int,
    val finalAccuracy: Float,
    val artifactFileName: String,
    val artifactPath: String,
    val isExperimentalOnly: Boolean = true,
    val isolationNotice: String = "Experimental model only. The production 2D inspection model remains unchanged."
)

/**
 * Per-class evaluation statistics.
 */
data class ClassEvaluationMetric(
    val className: String,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val precision: Float,
    val recall: Float,
    val f1Score: Float
)

/**
 * Output prediction for an individual test sample.
 */
data class ExperimentalSamplePrediction(
    val sampleId: String,
    val imageFileName: String,
    val expectedClass: BiscuitLabClass,
    val predictedClass: BiscuitLabClass,
    val confidence: Float,
    val isCorrect: Boolean,
    val boundingBox: BoundingBox
)

/**
 * Comprehensive evaluation report produced by evaluating an experimental model on held-out test data.
 */
data class ExperimentalEvaluationReport(
    val reportId: String,
    val modelId: String,
    val evaluationTimestamp: Long,
    val testSampleCount: Int,
    val overallAccuracy: Float,
    val perClassMetrics: List<ClassEvaluationMetric>,
    val confusionMatrix: Map<String, Map<String, Int>>,
    val samplePredictions: List<ExperimentalSamplePrediction>,
    val datasetSplitUsed: String = "HELD_OUT_TEST_DATA",
    val isolationStatement: String = "Experimental model evaluation only. Production 2D inspection models and history remain untouched."
)

/**
 * Audit result verifying production model immutability and storage isolation.
 */
data class IsolationAuditResult(
    val productionModelAssetPath: String,
    val productionModelHashBefore: String,
    val productionModelHashAfter: String,
    val isHashIdentical: Boolean,
    val isAssetImmutable: Boolean,
    val experimentalStorageDirectory: String,
    val isDirectoryIsolated: Boolean,
    val message: String
)
