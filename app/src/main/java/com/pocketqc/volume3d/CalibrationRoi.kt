package com.pocketqc.volume3d

/**
 * Normalized Region of Interest (ROI) and alignment guide reticle for container inspection.
 * Coordinates are normalized in [0.0, 1.0] relative to camera frame dimensions.
 */
data class CalibrationRoi(
    val topNormalizedY: Float = 0.20f,   // Container neck/cap / 100% reference line
    val baseNormalizedY: Float = 0.85f,  // Container bottom base / 0% reference line
    val leftNormalizedX: Float = 0.25f,  // Left container wall boundary
    val rightNormalizedX: Float = 0.75f  // Right container wall boundary
) {
    val heightFraction: Float get() = (baseNormalizedY - topNormalizedY).coerceAtLeast(0.01f)
    val widthFraction: Float get() = (rightNormalizedX - leftNormalizedX).coerceAtLeast(0.01f)

    /**
     * Target line Y normalized in frame coordinates.
     */
    fun targetLineY(targetFillPercent: Float): Float {
        val fillFraction = (targetFillPercent / 100f).coerceIn(0f, 1f)
        return baseNormalizedY - (heightFraction * fillFraction)
    }

    /**
     * Tolerance lower boundary Y (fill percent - tolerance) in frame coordinates.
     */
    fun toleranceLowerY(targetFillPercent: Float, tolerancePercent: Float): Float {
        val fillFraction = ((targetFillPercent - tolerancePercent) / 100f).coerceIn(0f, 1f)
        return baseNormalizedY - (heightFraction * fillFraction)
    }

    /**
     * Tolerance upper boundary Y (fill percent + tolerance) in frame coordinates.
     */
    fun toleranceUpperY(targetFillPercent: Float, tolerancePercent: Float): Float {
        val fillFraction = ((targetFillPercent + tolerancePercent) / 100f).coerceIn(0f, 1f)
        return baseNormalizedY - (heightFraction * fillFraction)
    }

    companion object {
        val DEFAULT = CalibrationRoi()
    }
}
