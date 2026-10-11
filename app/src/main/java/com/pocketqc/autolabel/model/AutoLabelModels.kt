package com.pocketqc.autolabel.model

import com.pocketqc.core.model.BoundingBox

/**
 * Review state for an experimental image sample or proposed annotation.
 */
enum class AnnotationStatus {
    PENDING,
    REVIEW_REQUIRED,
    APPROVED,
    REJECTED
}

/**
 * Origin of a bounding box proposal.
 */
enum class ProposalSource {
    MODEL_DETECTOR,
    HEURISTIC_REFINEMENT,
    MANUAL_CORRECTION,
    MANUAL_ADDITION,
    BENCHMARK_GROUND_TRUTH
}

/**
 * A single biscuit bounding box annotation within an image.
 */
data class ProposedBoxAnnotation(
    val boxId: String,
    val boundingBox: BoundingBox,
    val predictedClass: AutoLabelClass,
    val confidence: Float,
    val assignedClass: AutoLabelClass = predictedClass,
    val isHumanVerified: Boolean = false,
    val source: ProposalSource = ProposalSource.MODEL_DETECTOR,
    val reviewNotes: String? = null
) {
    /**
     * Converts normalized [BoundingBox] (left, top, right, bottom in [0.0, 1.0])
     * into normalized YOLO coordinates: x_center, y_center, width, height.
     */
    fun toYoloCoordinates(): YoloBoxCoordinates {
        val left = boundingBox.left.coerceIn(0f, 1f)
        val top = boundingBox.top.coerceIn(0f, 1f)
        val right = boundingBox.right.coerceIn(0f, 1f)
        val bottom = boundingBox.bottom.coerceIn(0f, 1f)

        val xCenter = ((left + right) / 2f).coerceIn(0f, 1f)
        val yCenter = ((top + bottom) / 2f).coerceIn(0f, 1f)
        val width = (right - left).coerceAtLeast(0f).coerceIn(0f, 1f)
        val height = (bottom - top).coerceAtLeast(0f).coerceIn(0f, 1f)

        return YoloBoxCoordinates(xCenter, yCenter, width, height)
    }
}

/**
 * Normalized YOLO bounding box coordinates (all in [0.0, 1.0]).
 */
data class YoloBoxCoordinates(
    val xCenter: Float,
    val yCenter: Float,
    val width: Float,
    val height: Float
) {
    /**
     * Formats line for YOLO label .txt file: `<class_id> <x_center> <y_center> <width> <height>`
     */
    fun toYoloLine(classId: Int): String {
        return String.format(
            java.util.Locale.US,
            "%d %.6f %.6f %.6f %.6f",
            classId,
            xCenter,
            yCenter,
            width,
            height
        )
    }

    /**
     * Converts back to normalized [BoundingBox].
     */
    fun toBoundingBox(): BoundingBox {
        val halfW = width / 2f
        val halfH = height / 2f
        return BoundingBox(
            left = (xCenter - halfW).coerceIn(0f, 1f),
            top = (yCenter - halfH).coerceIn(0f, 1f),
            right = (xCenter + halfW).coerceIn(0f, 1f),
            bottom = (yCenter + halfH).coerceIn(0f, 1f)
        )
    }
}

/**
 * An imported or captured image item in the experimental auto-labeling workspace.
 */
data class AutoLabelImageItem(
    val id: String,
    val fileHash: String,
    val fileName: String,
    val filePath: String,
    val source: String, // e.g. "CAMERA", "IMPORT", "STANDARD_SAMPLE"
    val imageWidth: Int,
    val imageHeight: Int,
    val operatorAssignedClass: AutoLabelClass? = null,
    val reviewStatus: AnnotationStatus = AnnotationStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val annotations: List<ProposedBoxAnnotation> = emptyList(),
    val processingTimeMs: Long = 0L,
    val rejectionReason: String? = null,
    val hasHumanCorrections: Boolean = false
)
