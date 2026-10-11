package com.pocketqc.autolabel.engine

import android.graphics.Bitmap
import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.ProposalSource
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.cv.BoundingBoxUtils
import java.util.UUID

/**
 * Result bundle produced by running the auto-labeling pipeline on an image.
 */
data class PipelineProcessResult(
    val updatedItem: AutoLabelImageItem,
    val totalProposed: Int,
    val validBoxes: Int,
    val invalidBoxes: Int,
    val duplicateBoxes: Int,
    val processingDurationMs: Long,
    val reviewRequiredReasons: List<String>
)

/**
 * Core processing pipeline orchestrating automated localization, box sanitization,
 * crop feature analysis, and review-flagging.
 */
class AutoLabelPipeline(
    private val detector: BiscuitDetector,
    private val minConfidenceThreshold: Float = 0.25f,
    private val reviewConfidenceThreshold: Float = 0.65f,
    private val minBoxArea: Float = 0.005f,
    private val overlapIouThreshold: Float = 0.15f,
    private val duplicateIouThreshold: Float = 0.85f
) {

    /**
     * Processes an image item: runs detector, sanitizes boxes, checks touching/overlapping
     * biscuits, extracts visual crop evidence, and assigns initial review status.
     */
    suspend fun processImage(
        item: AutoLabelImageItem,
        bitmap: Bitmap? = null
    ): PipelineProcessResult {
        val startTime = System.currentTimeMillis()
        val reasons = mutableListOf<String>()

        // 1. Run Localization via BiscuitDetector
        val rawDetections = detector.detect(bitmap)

        var invalidCount = 0
        var duplicateCount = 0
        val sanitizedBoxes = mutableListOf<ProposedBoxAnnotation>()

        // 2. Box Validation & Filtering
        for (det in rawDetections) {
            val box = det.boundingBox

            // Validate boundaries and area
            if (!isValidBox(box)) {
                invalidCount++
                continue
            }

            // Check for duplicates within this frame
            val isDuplicate = sanitizedBoxes.any { existing ->
                BoundingBoxUtils.computeIoU(existing.boundingBox, box) >= duplicateIouThreshold
            }
            if (isDuplicate) {
                duplicateCount++
                continue
            }

            // Map production model label string ("Broken", "Fracture", "Intact")
            // to independent experimental AutoLabelClass (0=Intact, 1=Fracture, 2=Broken)
            val modelClass = AutoLabelClass.fromProductionLabel(det.className)

            // Extract crop and analyze visual features
            val crop = if (bitmap != null) extractCrop(bitmap, box) else null
            val cropAnalysis = crop?.let { CropFeatureAnalyzer.analyze(it) }
            crop?.recycle()

            val boxReviewNotes = mutableListOf<String>()

            // A. Check confidence
            if (det.confidence < reviewConfidenceThreshold) {
                boxReviewNotes.add("Low confidence (%.2f < %.2f)".format(det.confidence, reviewConfidenceThreshold))
            }

            // B. Check boundary collision (partially cut off biscuit)
            if (touchesImageBoundary(box)) {
                boxReviewNotes.add("Box touches image boundary (potential clipping)")
            }

            // C. Check visual discrepancy between model prediction and crop heuristics
            if (cropAnalysis != null) {
                if (modelClass == AutoLabelClass.INTACT && cropAnalysis.estimatedClass == AutoLabelClass.BROKEN) {
                    boxReviewNotes.add("Model predicted Intact but crop displays severe fissure/irregularity")
                } else if (modelClass != AutoLabelClass.INTACT && cropAnalysis.edgeDensity < 0.02f && cropAnalysis.aspectRatio in 0.90f..1.10f) {
                    boxReviewNotes.add("Model predicted ${modelClass.displayName} but crop appears uniform/intact")
                }
                if (cropAnalysis.isAmbiguous && cropAnalysis.ambiguityReason != null) {
                    boxReviewNotes.add(cropAnalysis.ambiguityReason)
                }
            }

            val proposed = ProposedBoxAnnotation(
                boxId = UUID.randomUUID().toString(),
                boundingBox = box,
                predictedClass = modelClass,
                confidence = det.confidence,
                assignedClass = modelClass,
                isHumanVerified = false,
                source = ProposalSource.MODEL_DETECTOR,
                reviewNotes = if (boxReviewNotes.isNotEmpty()) boxReviewNotes.joinToString("; ") else null
            )
            sanitizedBoxes.add(proposed)
        }

        // 3. Overlapping & Touching Biscuits Detection
        val flaggedBoxes = sanitizedBoxes.map { currentBox ->
            val overlapping = sanitizedBoxes.filter { other ->
                other.boxId != currentBox.boxId &&
                    BoundingBoxUtils.computeIoU(currentBox.boundingBox, other.boundingBox) >= overlapIouThreshold
            }

            if (overlapping.isNotEmpty()) {
                val existingNotes = currentBox.reviewNotes
                val overlapNote = "Overlapping/touching biscuit detected (IoU >= %.2f)".format(overlapIouThreshold)
                currentBox.copy(
                    reviewNotes = if (existingNotes != null) "$existingNotes; $overlapNote" else overlapNote
                )
            } else {
                currentBox
            }
        }

        // 4. Determine overall Image Review Status
        val finalStatus = when {
            flaggedBoxes.isEmpty() -> {
                reasons.add("No valid biscuits localized in image")
                AnnotationStatus.REVIEW_REQUIRED
            }
            flaggedBoxes.any { it.reviewNotes != null } -> {
                reasons.add("One or more boxes require manual verification")
                AnnotationStatus.REVIEW_REQUIRED
            }
            else -> {
                AnnotationStatus.PENDING // Ready for operator approval
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        val updatedItem = item.copy(
            annotations = flaggedBoxes,
            reviewStatus = finalStatus,
            processingTimeMs = elapsed,
            rejectionReason = if (flaggedBoxes.isEmpty()) "No biscuit detected" else null
        )

        return PipelineProcessResult(
            updatedItem = updatedItem,
            totalProposed = rawDetections.size,
            validBoxes = flaggedBoxes.size,
            invalidBoxes = invalidCount,
            duplicateBoxes = duplicateCount,
            processingDurationMs = elapsed,
            reviewRequiredReasons = reasons
        )
    }

    private fun isValidBox(box: BoundingBox): Boolean {
        if (box.left < 0f || box.top < 0f || box.right > 1f || box.bottom > 1f) return false
        if (box.right <= box.left || box.bottom <= box.top) return false
        if (box.area < minBoxArea) return false
        if (box.width < 0.03f || box.height < 0.03f) return false
        return true
    }

    private fun touchesImageBoundary(box: BoundingBox, margin: Float = 0.015f): Boolean {
        return box.left <= margin || box.top <= margin || box.right >= (1f - margin) || box.bottom >= (1f - margin)
    }

    private fun extractCrop(bitmap: Bitmap, box: BoundingBox): Bitmap? {
        val bW = bitmap.width
        val bH = bitmap.height
        val x = (box.left * bW).toInt().coerceIn(0, bW - 1)
        val y = (box.top * bH).toInt().coerceIn(0, bH - 1)
        val w = ((box.right - box.left) * bW).toInt().coerceIn(1, bW - x)
        val h = ((box.bottom - box.top) * bH).toInt().coerceIn(1, bH - y)

        return try {
            Bitmap.createBitmap(bitmap, x, y, w, h)
        } catch (_: Exception) {
            null
        }
    }
}
