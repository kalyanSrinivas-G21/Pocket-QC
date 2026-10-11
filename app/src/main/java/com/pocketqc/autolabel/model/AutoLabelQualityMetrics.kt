package com.pocketqc.autolabel.model

/**
 * Detailed quality evaluation report measuring automated labeling pipeline performance.
 */
data class AutoLabelQualityReport(
    val timestamp: Long = System.currentTimeMillis(),
    val totalImages: Int,
    val imagesWithNoDetections: Int,
    val totalProposedBoxes: Int,
    val approvedCount: Int,
    val reviewRequiredCount: Int,
    val rejectedCount: Int,
    val pendingCount: Int,
    val classDistribution: Map<AutoLabelClass, Int>,
    val invalidBoxCount: Int,
    val duplicateBoxCount: Int,
    val meanProcessingTimeMs: Double,
    val minProcessingTimeMs: Long,
    val maxProcessingTimeMs: Long,
    val humanCorrectionRate: Float, // % of images where boxes/classes were modified by human
    val benchmarkEvaluation: BenchmarkEvaluationResult? = null,
    val summaryNotes: List<String> = emptyList()
)

/**
 * Objective evaluation metrics against a manually verified benchmark dataset.
 */
data class BenchmarkEvaluationResult(
    val benchmarkImageCount: Int,
    val totalGroundTruthBoxes: Int,
    val totalPredictedBoxes: Int,
    val matchedBoxCount: Int, // IoU >= 0.50
    val meanIoU: Float,
    val localizationPrecision: Float, // matched / totalPredicted
    val localizationRecall: Float,    // matched / totalGroundTruth
    val localizationF1: Float,
    val classificationAgreementRate: Float, // percentage of matched boxes where predicted == ground truth
    val perClassAgreement: Map<AutoLabelClass, Float> = emptyMap()
)
