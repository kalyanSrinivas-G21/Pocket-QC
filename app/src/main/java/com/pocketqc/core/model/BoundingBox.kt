package com.pocketqc.core.model

/**
 * Normalized bounding box coordinates within [0.0, 1.0] relative to frame dimensions.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
}
