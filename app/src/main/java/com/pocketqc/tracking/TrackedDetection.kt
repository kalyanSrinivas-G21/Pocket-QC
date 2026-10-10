package com.pocketqc.tracking

import com.pocketqc.core.model.BoundingBox

/**
 * Represents an individual physical item tracked across consecutive camera frames.
 */
data class TrackedDetection(
    val trackId: Int,
    val className: String,
    var boundingBox: BoundingBox,
    var confidence: Float,
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    var lastSeenTimestamp: Long = System.currentTimeMillis(),
    var counted: Boolean = false
) {
    val centroidX: Float
        get() = (boundingBox.left + boundingBox.right) / 2f

    val centroidY: Float
        get() = (boundingBox.top + boundingBox.bottom) / 2f
}
