package com.pocketqc.lab.evaluation

import android.graphics.BitmapFactory
import com.pocketqc.lab.model.BiscuitLabClass
import com.pocketqc.lab.model.ClassEvaluationMetric
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.ExperimentalEvaluationReport
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.lab.model.ExperimentalSamplePrediction
import com.pocketqc.lab.training.ExperimentalModel
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Evaluates an experimental model exclusively against held-out test samples.
 * Produces structured metrics (Precision, Recall, F1, Confusion Matrix) and saves evaluation
 * reports to the isolated experimental workspace without touching production metrics.
 */
class ExperimentalEvaluationEngine(
    private val workspace: ExperimentalWorkspace
) {

    /**
     * Runs full evaluation of [model] on the held-out [samples] matching [targetSplit].
     */
    suspend fun evaluate(
        model: ExperimentalModel,
        samples: List<ExperimentalSample>,
        targetSplit: DatasetSplit = DatasetSplit.TEST
    ): Result<ExperimentalEvaluationReport> = withContext(Dispatchers.Default) {
        runCatching {
            val evalSamples = samples.filter { it.split == targetSplit }.ifEmpty {
                samples.filter { it.split == DatasetSplit.VAL }.ifEmpty { samples }
            }

            check(evalSamples.isNotEmpty()) { "No samples available for evaluation in split $targetSplit." }

            val samplePredictions = mutableListOf<ExperimentalSamplePrediction>()
            val classes = BiscuitLabClass.entries

            // Confusion matrix: groundTruth -> (predicted -> count)
            val matrix = mutableMapOf<String, MutableMap<String, Int>>()
            for (gt in classes) {
                matrix[gt.label] = mutableMapOf()
                for (pred in classes) {
                    matrix[gt.label]!![pred.label] = 0
                }
            }

            var correctCount = 0

            for (s in evalSamples) {
                val file = File(s.imageFilePath)
                if (!file.exists()) continue

                val result = model.predictImageFile(file, s.boundingBox)

                val isCorrect = result.predictedClass == s.assignedClass
                if (isCorrect) correctCount++

                matrix[s.assignedClass.label]!![result.predictedClass.label] =
                    (matrix[s.assignedClass.label]!![result.predictedClass.label] ?: 0) + 1

                samplePredictions.add(
                    ExperimentalSamplePrediction(
                        sampleId = s.id,
                        imageFileName = s.imageFileName,
                        expectedClass = s.assignedClass,
                        predictedClass = result.predictedClass,
                        confidence = result.confidence,
                        isCorrect = isCorrect,
                        boundingBox = s.boundingBox
                    )
                )
            }

            val totalEvaluated = samplePredictions.size
            check(totalEvaluated > 0) { "Failed to evaluate any samples from the dataset." }
            val overallAccuracy = correctCount.toFloat() / totalEvaluated

            // Calculate per-class metrics
            val perClassMetrics = mutableListOf<ClassEvaluationMetric>()
            for (c in classes) {
                val label = c.label
                val tp = matrix[label]?.get(label) ?: 0
                var fp = 0
                var fn = 0
                var tn = 0

                for (otherGt in classes) {
                    if (otherGt.label != label) {
                        fp += matrix[otherGt.label]?.get(label) ?: 0
                    }
                }

                for (otherPred in classes) {
                    if (otherPred.label != label) {
                        fn += matrix[label]?.get(otherPred.label) ?: 0
                    }
                }

                for (otherGt in classes) {
                    if (otherGt.label != label) {
                        for (otherPred in classes) {
                            if (otherPred.label != label) {
                                tn += matrix[otherGt.label]?.get(otherPred.label) ?: 0
                            }
                        }
                    }
                }

                val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp) else 0f
                val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn) else 0f
                val f1 = if (precision + recall > 0f) 2f * (precision * recall) / (precision + recall) else 0f

                perClassMetrics.add(
                    ClassEvaluationMetric(
                        className = label,
                        truePositives = tp,
                        falsePositives = fp,
                        falseNegatives = fn,
                        trueNegatives = tn,
                        precision = precision,
                        recall = recall,
                        f1Score = f1
                    )
                )
            }

            val reportTimestamp = System.currentTimeMillis()
            val reportId = "EVAL_${reportTimestamp}"

            val report = ExperimentalEvaluationReport(
                reportId = reportId,
                modelId = model.metadata.modelId,
                evaluationTimestamp = reportTimestamp,
                testSampleCount = totalEvaluated,
                overallAccuracy = overallAccuracy,
                perClassMetrics = perClassMetrics,
                confusionMatrix = matrix,
                samplePredictions = samplePredictions,
                datasetSplitUsed = targetSplit.name
            )

            // Save report to experimental evaluations directory
            saveReportToFile(report)

            report
        }
    }

    private fun saveReportToFile(report: ExperimentalEvaluationReport) {
        val destFile = File(workspace.evaluationsDir, "eval_${report.reportId}.json")
        val root = JSONObject()
        root.put("reportId", report.reportId)
        root.put("modelId", report.modelId)
        root.put("evaluationTimestamp", report.evaluationTimestamp)
        root.put("testSampleCount", report.testSampleCount)
        root.put("overallAccuracy", report.overallAccuracy)
        root.put("datasetSplitUsed", report.datasetSplitUsed)
        root.put("isolationStatement", report.isolationStatement)

        val metricsArr = JSONArray()
        for (m in report.perClassMetrics) {
            val obj = JSONObject()
            obj.put("className", m.className)
            obj.put("tp", m.truePositives)
            obj.put("fp", m.falsePositives)
            obj.put("fn", m.falseNegatives)
            obj.put("tn", m.trueNegatives)
            obj.put("precision", m.precision)
            obj.put("recall", m.recall)
            obj.put("f1Score", m.f1Score)
            metricsArr.put(obj)
        }
        root.put("perClassMetrics", metricsArr)

        val matrixObj = JSONObject()
        for ((gt, row) in report.confusionMatrix) {
            val rowObj = JSONObject()
            for ((pred, count) in row) {
                rowObj.put(pred, count)
            }
            matrixObj.put(gt, rowObj)
        }
        root.put("confusionMatrix", matrixObj)

        destFile.writeText(root.toString(2))
    }
}
