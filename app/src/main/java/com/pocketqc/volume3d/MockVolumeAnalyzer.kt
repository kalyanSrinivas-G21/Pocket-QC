package com.pocketqc.volume3d

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deterministic bottle fill-level simulator for developing and testing
 * the automated 3D volume inspection workflow without physical dual-camera jig.
 */
class MockVolumeAnalyzer(
    private val tolerancePercent: Float = 3.0f
) : VolumeAnalyzer {

    override suspend fun analyze(
        topFrame: Bitmap?,
        sideFrame: Bitmap?,
        requiredFillPercent: Float
    ): VolumeAnalysisResult {
        delay(600) // Simulate cross-section image analysis latency

        // Variance: 85% normal variation within +/- 2.5%, 15% out-of-spec (+/- 4.5% to 7%)
        val isDefectiveOutlier = Random.nextFloat() < 0.15f
        val delta = if (isDefectiveOutlier) {
            val sign = if (Random.nextBoolean()) 1f else -1f
            sign * (3.5f + Random.nextFloat() * 3.5f)
        } else {
            (Random.nextFloat() * 4.0f) - 2.0f
        }

        val detectedFill = (requiredFillPercent + delta).coerceIn(10f, 100f)
        val difference = detectedFill - requiredFillPercent
        val isPass = abs(difference) <= tolerancePercent

        return VolumeAnalysisResult(
            detectedFillPercent = detectedFill,
            requiredFillPercent = requiredFillPercent,
            differencePercent = difference,
            isPass = isPass,
            confidence = 0.94f + (Random.nextFloat() * 0.05f)
        )
    }
}
