package com.pocketqc.core.model

/**
 * Represents a single object or defect detected within a frame by the inference engine.
 */
data class Detection(
    val className: String,
    val confidence: Float,
    val boundingBox: BoundingBox
)
