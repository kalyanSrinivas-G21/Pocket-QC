package com.pocketqc.inference

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.cv.BoundingBoxUtils

data class YoloCandidate(
    val classId: Int,
    val confidence: Float,
    val boundingBox: BoundingBox
)

/**
 * High-performance, generic YOLO postprocessor.
 *
 * Decoupled from hardcoded class vocabularies and dimensions.
 * Parses flat row-major output arrays of shape [1, (4 + numClasses), numAnchors],
 * filters candidates by confidence threshold, inverts letterbox coordinates,
 * and performs class-aware Non-Maximum Suppression (NMS).
 *
 * Optimized to eliminate temporary collections, map allocations (groupBy), and iterator churn.
 */
object YoloPostprocessor {

    /**
     * Parses the flat model output array using a generic [ModelConfig].
     */
    fun parseAndFilter(
        rawOutput: FloatArray,
        letterboxInfo: LetterboxInfo,
        modelConfig: ModelConfig,
        confidenceThreshold: Float = modelConfig.confidenceThreshold,
        iouThreshold: Float = modelConfig.iouThreshold
    ): List<Detection> {
        return parseAndFilterInternal(
            rawOutput = rawOutput,
            letterboxInfo = letterboxInfo,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold,
            numAnchors = modelConfig.numAnchors,
            numClasses = modelConfig.numClasses,
            classNames = modelConfig.classNames
        )
    }

    /**
     * Legacy/overload signature for backward compatibility with existing tests and callers.
     */
    fun parseAndFilter(
        rawOutput: FloatArray,
        letterboxInfo: LetterboxInfo,
        confidenceThreshold: Float = YoloConfig.DEFAULT_CONFIDENCE_THRESHOLD,
        iouThreshold: Float = YoloConfig.DEFAULT_IOU_THRESHOLD,
        numAnchors: Int = YoloConfig.NUM_ANCHORS,
        numClasses: Int = YoloConfig.NUM_CLASSES
    ): List<Detection> {
        return parseAndFilterInternal(
            rawOutput = rawOutput,
            letterboxInfo = letterboxInfo,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold,
            numAnchors = numAnchors,
            numClasses = numClasses,
            classNames = YoloConfig.CLASS_NAMES
        )
    }

    private fun parseAndFilterInternal(
        rawOutput: FloatArray,
        letterboxInfo: LetterboxInfo,
        confidenceThreshold: Float,
        iouThreshold: Float,
        numAnchors: Int,
        numClasses: Int,
        classNames: List<String>
    ): List<Detection> {
        val totalExpected = (4 + numClasses) * numAnchors
        require(rawOutput.size >= totalExpected) {
            "Raw output array size ${rawOutput.size} is smaller than expected $totalExpected"
        }

        // Direct class-partitioned buckets to eliminate groupBy map and wrapper allocations
        val candidatesByClass = Array(numClasses) { ArrayList<YoloCandidate>() }

        // 1. Iterate over all anchors
        for (i in 0 until numAnchors) {
            // Find class with maximum confidence score
            var bestClass = 0
            var maxScore = -1f
            for (c in 0 until numClasses) {
                val score = rawOutput[(4 + c) * numAnchors + i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            // Confidence threshold filtering
            if (maxScore < confidenceThreshold) {
                continue
            }

            val cx = rawOutput[0 * numAnchors + i]
            val cy = rawOutput[1 * numAnchors + i]
            val w = rawOutput[2 * numAnchors + i]
            val h = rawOutput[3 * numAnchors + i]

            // Convert center (cx, cy, w, h) in letterbox space to corners
            val halfW = w / 2f
            val halfH = h / 2f
            val boxLeft = cx - halfW
            val boxTop = cy - halfH
            val boxRight = cx + halfW
            val boxBottom = cy + halfH

            // Invert letterbox back to original image space
            val origLeft = letterboxInfo.invertX(boxLeft)
            val origTop = letterboxInfo.invertY(boxTop)
            val origRight = letterboxInfo.invertX(boxRight)
            val origBottom = letterboxInfo.invertY(boxBottom)

            // Normalize to [0.0, 1.0] relative to original image dimensions
            val normLeft = (origLeft / letterboxInfo.origW).coerceIn(0f, 1f)
            val normTop = (origTop / letterboxInfo.origH).coerceIn(0f, 1f)
            val normRight = (origRight / letterboxInfo.origW).coerceIn(0f, 1f)
            val normBottom = (origBottom / letterboxInfo.origH).coerceIn(0f, 1f)

            if (normRight <= normLeft || normBottom <= normTop) {
                continue
            }

            candidatesByClass[bestClass].add(
                YoloCandidate(
                    classId = bestClass,
                    confidence = maxScore,
                    boundingBox = BoundingBox(
                        left = normLeft,
                        top = normTop,
                        right = normRight,
                        bottom = normBottom
                    )
                )
            )
        }

        // 2. Class-aware Non-Maximum Suppression (NMS) with in-place suppression marking
        val finalDetections = ArrayList<Detection>()

        for (classId in 0 until numClasses) {
            val classCandidates = candidatesByClass[classId]
            if (classCandidates.isEmpty()) continue

            // In-place sort by descending confidence
            classCandidates.sortByDescending { it.confidence }
            val count = classCandidates.size
            val suppressed = BooleanArray(count)

            for (i in 0 until count) {
                if (suppressed[i]) continue
                val best = classCandidates[i]

                finalDetections.add(
                    Detection(
                        className = classNames.getOrElse(classId) { "Class_$classId" },
                        confidence = best.confidence,
                        boundingBox = best.boundingBox
                    )
                )

                for (j in (i + 1) until count) {
                    if (suppressed[j]) continue
                    val other = classCandidates[j]
                    val iou = BoundingBoxUtils.computeIoU(best.boundingBox, other.boundingBox)
                    if (iou > iouThreshold) {
                        suppressed[j] = true
                    }
                }
            }
        }

        return finalDetections
    }
}
