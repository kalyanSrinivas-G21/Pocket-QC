package com.pocketqc.autolabel.evaluation

import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.AutoLabelQualityReport
import com.pocketqc.autolabel.model.BenchmarkEvaluationResult
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.cv.BoundingBoxUtils
import kotlin.math.max

/**
 * Objective evaluation engine assessing auto-labeling pipeline quality,
 * coverage, agreement, and human correction rate.
 */
object AutoLabelEvaluator {

    /**
     * Standard manually verified ground truth dataset for benchmark evaluation.
     * Mapped by standard filename base or hash identifier.
     */
    val STANDARD_BENCHMARK_GROUND_TRUTH = mapOf(
        "biscuit_intact" to listOf(
            ProposedBoxAnnotation(
                boxId = "gt_intact_01",
                boundingBox = com.pocketqc.core.model.BoundingBox(0.20f, 0.30f, 0.72f, 0.78f),
                predictedClass = AutoLabelClass.INTACT,
                confidence = 1.0f,
                assignedClass = AutoLabelClass.INTACT,
                isHumanVerified = true,
                source = com.pocketqc.autolabel.model.ProposalSource.BENCHMARK_GROUND_TRUTH
            )
        ),
        "biscuit_fracture" to listOf(
            ProposedBoxAnnotation(
                boxId = "gt_fracture_01",
                boundingBox = com.pocketqc.core.model.BoundingBox(0.15f, 0.10f, 0.70f, 0.68f),
                predictedClass = AutoLabelClass.FRACTURE,
                confidence = 1.0f,
                assignedClass = AutoLabelClass.FRACTURE,
                isHumanVerified = true,
                source = com.pocketqc.autolabel.model.ProposalSource.BENCHMARK_GROUND_TRUTH
            )
        ),
        "biscuit_broken" to listOf(
            ProposedBoxAnnotation(
                boxId = "gt_broken_01",
                boundingBox = com.pocketqc.core.model.BoundingBox(0.18f, 0.20f, 0.85f, 0.85f),
                predictedClass = AutoLabelClass.BROKEN,
                confidence = 1.0f,
                assignedClass = AutoLabelClass.BROKEN,
                isHumanVerified = true,
                source = com.pocketqc.autolabel.model.ProposalSource.BENCHMARK_GROUND_TRUTH
            )
        )
    )

    /**
     * Evaluates a collection of image items and produces an objective quality report.
     */
    fun evaluate(
        items: List<AutoLabelImageItem>,
        groundTruthMap: Map<String, List<ProposedBoxAnnotation>> = STANDARD_BENCHMARK_GROUND_TRUTH
    ): AutoLabelQualityReport {
        if (items.isEmpty()) {
            return AutoLabelQualityReport(
                totalImages = 0,
                imagesWithNoDetections = 0,
                totalProposedBoxes = 0,
                approvedCount = 0,
                reviewRequiredCount = 0,
                rejectedCount = 0,
                pendingCount = 0,
                classDistribution = emptyMap(),
                invalidBoxCount = 0,
                duplicateBoxCount = 0,
                meanProcessingTimeMs = 0.0,
                minProcessingTimeMs = 0L,
                maxProcessingTimeMs = 0L,
                humanCorrectionRate = 0f,
                summaryNotes = listOf("Dataset is empty. No samples evaluated.")
            )
        }

        val totalImages = items.size
        val noDetectionsCount = items.count { it.annotations.isEmpty() }
        val allBoxes = items.flatMap { it.annotations }
        val totalProposedBoxes = allBoxes.size

        val approvedCount = items.count { it.reviewStatus == AnnotationStatus.APPROVED }
        val reviewRequiredCount = items.count { it.reviewStatus == AnnotationStatus.REVIEW_REQUIRED }
        val rejectedCount = items.count { it.reviewStatus == AnnotationStatus.REJECTED }
        val pendingCount = items.count { it.reviewStatus == AnnotationStatus.PENDING }

        // Class distribution
        val classDist = mutableMapOf(
            AutoLabelClass.INTACT to 0,
            AutoLabelClass.FRACTURE to 0,
            AutoLabelClass.BROKEN to 0
        )
        for (box in allBoxes) {
            classDist[box.assignedClass] = (classDist[box.assignedClass] ?: 0) + 1
        }

        // Box validation metrics
        var invalidBoxes = 0
        var duplicateBoxes = 0
        for (item in items) {
            val boxes = item.annotations
            for (i in boxes.indices) {
                val b = boxes[i].boundingBox
                if (b.area <= 0f || b.left < 0f || b.top < 0f || b.right > 1f || b.bottom > 1f || b.left >= b.right || b.top >= b.bottom) {
                    invalidBoxes++
                }
                for (j in (i + 1) until boxes.size) {
                    if (BoundingBoxUtils.computeIoU(b, boxes[j].boundingBox) >= 0.85f) {
                        duplicateBoxes++
                    }
                }
            }
        }

        // Processing time
        val times = items.map { it.processingTimeMs }.filter { it > 0L }
        val meanTime = if (times.isNotEmpty()) times.average() else 0.0
        val minTime = if (times.isNotEmpty()) times.minOrNull() ?: 0L else 0L
        val maxTime = if (times.isNotEmpty()) times.maxOrNull() ?: 0L else 0L

        // Human correction rate (% of items where human modified or verified)
        val humanCorrectedCount = items.count { it.hasHumanCorrections }
        val humanCorrectionRate = if (totalImages > 0) humanCorrectedCount.toFloat() / totalImages.toFloat() else 0f

        // Benchmark evaluation
        val benchmarkResult = evaluateBenchmark(items, groundTruthMap)

        val notes = mutableListOf<String>()
        if (noDetectionsCount > 0) {
            notes.add("$noDetectionsCount images (%.1f%%) produced 0 detections".format((noDetectionsCount.toFloat() / totalImages) * 100f))
        }
        if (reviewRequiredCount > 0) {
            notes.add("$reviewRequiredCount images require operator review due to low confidence, boundary clipping, or visual conflict".format())
        }
        benchmarkResult?.let { b ->
            notes.add("Benchmark agreement rate: %.1f%% across %d matched samples".format(b.classificationAgreementRate * 100f, b.matchedBoxCount))
            notes.add("Benchmark mean IoU: %.3f".format(b.meanIoU))
        }

        return AutoLabelQualityReport(
            totalImages = totalImages,
            imagesWithNoDetections = noDetectionsCount,
            totalProposedBoxes = totalProposedBoxes,
            approvedCount = approvedCount,
            reviewRequiredCount = reviewRequiredCount,
            rejectedCount = rejectedCount,
            pendingCount = pendingCount,
            classDistribution = classDist,
            invalidBoxCount = invalidBoxes,
            duplicateBoxCount = duplicateBoxes,
            meanProcessingTimeMs = meanTime,
            minProcessingTimeMs = minTime,
            maxProcessingTimeMs = maxTime,
            humanCorrectionRate = humanCorrectionRate,
            benchmarkEvaluation = benchmarkResult,
            summaryNotes = notes
        )
    }

    /**
     * Evaluates predictions against ground truth for matching benchmark images.
     */
    fun evaluateBenchmark(
        items: List<AutoLabelImageItem>,
        groundTruthMap: Map<String, List<ProposedBoxAnnotation>>
    ): BenchmarkEvaluationResult? {
        val benchmarkItems = items.filter { item ->
            groundTruthMap.keys.any { key -> item.fileName.contains(key, ignoreCase = true) }
        }
        if (benchmarkItems.isEmpty()) return null

        var totalGtBoxes = 0
        var totalPredBoxes = 0
        var matchedCount = 0
        var totalIoU = 0f
        var agreementCount = 0
        val perClassMatched = mutableMapOf<AutoLabelClass, Int>()
        val perClassAgreed = mutableMapOf<AutoLabelClass, Int>()

        for (item in benchmarkItems) {
            val matchingKey = groundTruthMap.keys.firstOrNull { item.fileName.contains(it, ignoreCase = true) } ?: continue
            val gtBoxes = groundTruthMap[matchingKey] ?: continue
            totalGtBoxes += gtBoxes.size

            val predBoxes = item.annotations
            totalPredBoxes += predBoxes.size

            val matchedGtIndices = mutableSetOf<Int>()

            for (pred in predBoxes) {
                var bestGtIdx = -1
                var bestIoU = 0f

                for (idx in gtBoxes.indices) {
                    if (idx in matchedGtIndices) continue
                    val iou = BoundingBoxUtils.computeIoU(pred.boundingBox, gtBoxes[idx].boundingBox)
                    if (iou > bestIoU) {
                        bestIoU = iou
                        bestGtIdx = idx
                    }
                }

                if (bestGtIdx != -1 && bestIoU >= 0.50f) {
                    matchedGtIndices.add(bestGtIdx)
                    matchedCount++
                    totalIoU += bestIoU

                    val gtClass = gtBoxes[bestGtIdx].assignedClass
                    perClassMatched[gtClass] = (perClassMatched[gtClass] ?: 0) + 1

                    if (pred.assignedClass == gtClass) {
                        agreementCount++
                        perClassAgreed[gtClass] = (perClassAgreed[gtClass] ?: 0) + 1
                    }
                }
            }
        }

        val meanIoU = if (matchedCount > 0) totalIoU / matchedCount.toFloat() else 0f
        val precision = if (totalPredBoxes > 0) matchedCount.toFloat() / totalPredBoxes.toFloat() else 0f
        val recall = if (totalGtBoxes > 0) matchedCount.toFloat() / totalGtBoxes.toFloat() else 0f
        val f1 = if (precision + recall > 0f) 2f * (precision * recall) / (precision + recall) else 0f
        val agreementRate = if (matchedCount > 0) agreementCount.toFloat() / matchedCount.toFloat() else 0f

        val perClassAgreement = perClassMatched.mapValues { (cls, total) ->
            val agreed = perClassAgreed[cls] ?: 0
            if (total > 0) agreed.toFloat() / total.toFloat() else 0f
        }

        return BenchmarkEvaluationResult(
            benchmarkImageCount = benchmarkItems.size,
            totalGroundTruthBoxes = totalGtBoxes,
            totalPredictedBoxes = totalPredBoxes,
            matchedBoxCount = matchedCount,
            meanIoU = meanIoU,
            localizationPrecision = precision,
            localizationRecall = recall,
            localizationF1 = f1,
            classificationAgreementRate = agreementRate,
            perClassAgreement = perClassAgreement
        )
    }
}
