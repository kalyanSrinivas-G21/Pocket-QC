package com.pocketqc.volume3d

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Production computer-vision fill-level analyzer implementing explainable,
 * deterministic geometric surface measurement on on-device CPU.
 *
 * Capabilities:
 * - Level A: Calibrated liquid fill-height ratio measurement against container reference height.
 * - Level B: Volumetric estimation for uniform cylindrical profiles (V = pi * r^2 * h).
 * - Ambiguity & Occlusion Detection: Rejects flat, occluded, or ambiguous images with [MeasurementStatus.INCONCLUSIVE].
 * - Zero external dependencies, offline, zero randomizers.
 */
class RealGeometricFillAnalyzer(
    var profile: ContainerProfile = ContainerProfile.CYLINDER_500ML,
    var calibrationRoi: CalibrationRoi = CalibrationRoi.DEFAULT
) : VolumeAnalyzer {

    override suspend fun analyze(
        topFrame: Bitmap?,
        sideFrame: Bitmap?,
        requiredFillPercent: Float
    ): VolumeAnalysisResult = withContext(Dispatchers.Default) {
        val frame = sideFrame ?: topFrame
        if (frame == null) {
            return@withContext VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = 0f,
                status = MeasurementStatus.ERROR,
                diagnostics = "No camera frame received for analysis",
                containerProfile = profile
            )
        }

        try {
            val pixelSource = BitmapPixelSource(frame)
            analyzePixelSource(pixelSource, requiredFillPercent)
        } catch (e: Exception) {
            VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = 0f,
                status = MeasurementStatus.ERROR,
                diagnostics = "Image analysis error: ${e.message}",
                containerProfile = profile
            )
        }
    }

    /**
     * Core deterministic computer-vision algorithm running on any [PixelSource].
     */
    fun analyzePixelSource(
        source: PixelSource,
        requiredFillPercent: Float
    ): VolumeAnalysisResult {
        val width = source.width
        val height = source.height

        if (width < 16 || height < 16) {
            return VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = 0f,
                status = MeasurementStatus.ERROR,
                diagnostics = "Frame dimensions too small ($width x $height)",
                containerProfile = profile
            )
        }

        val yTop = (height * calibrationRoi.topNormalizedY).toInt().coerceIn(0, height - 2)
        val yBase = (height * calibrationRoi.baseNormalizedY).toInt().coerceIn(yTop + 5, height - 1)
        val xLeft = (width * calibrationRoi.leftNormalizedX).toInt().coerceIn(0, width - 2)
        val xRight = (width * calibrationRoi.rightNormalizedX).toInt().coerceIn(xLeft + 5, width - 1)

        val containerHeightPx = yBase - yTop
        if (containerHeightPx < 10) {
            return VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = 0f,
                status = MeasurementStatus.ERROR,
                diagnostics = "Container ROI vertical span too small ($containerHeightPx px)",
                containerProfile = profile
            )
        }

        // Sampling band: central 50% of container width to minimize sidewall refractive distortions
        val xSpan = xRight - xLeft
        val xSampleStart = (xLeft + xSpan * 0.25f).toInt().coerceIn(0, width - 1)
        val xSampleEnd = (xRight - xSpan * 0.25f).toInt().coerceIn(xSampleStart + 1, width)
        val sampleWidth = max(1, xSampleEnd - xSampleStart)

        // 1. Calculate vertical mean luminance profile L(y)
        val numRows = containerHeightPx + 1
        val luminanceProfile = FloatArray(numRows)
        var minLum = Float.MAX_VALUE
        var maxLum = Float.MIN_VALUE

        for (i in 0 until numRows) {
            val y = yTop + i
            var rowSum = 0.0
            for (x in xSampleStart until xSampleEnd) {
                val argb = source.getPixel(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                rowSum += lum
            }
            val avgLum = (rowSum / sampleWidth).toFloat()
            luminanceProfile[i] = avgLum
            if (avgLum < minLum) minLum = avgLum
            if (avgLum > maxLum) maxLum = avgLum
        }

        // 2. Scene validation: Verify contrast span across container height
        val contrastSpan = maxLum - minLum
        if (contrastSpan < 12.0f) {
            return VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = 0.10f,
                status = MeasurementStatus.INCONCLUSIVE,
                diagnostics = String.format(Locale.US, "Low contrast in container area (contrast span: %.1f). Check lighting or reposition container.", contrastSpan),
                containerProfile = profile
            )
        }

        // 3. Compute 1D smoothed gradient profile G(y) using 3-tap smoothing and central differences
        val smoothed = FloatArray(numRows)
        smoothed[0] = luminanceProfile[0]
        for (i in 1 until numRows - 1) {
            smoothed[i] = 0.25f * luminanceProfile[i - 1] + 0.50f * luminanceProfile[i] + 0.25f * luminanceProfile[i + 1]
        }
        smoothed[numRows - 1] = luminanceProfile[numRows - 1]

        val gradientProfile = FloatArray(numRows)
        var gradSum = 0.0
        var gradCount = 0

        for (i in 1 until numRows - 1) {
            val grad = abs(smoothed[i + 1] - smoothed[i - 1])
            gradientProfile[i] = grad
            gradSum += grad
            gradCount++
        }

        val meanGrad = if (gradCount > 0) (gradSum / gradCount).toFloat() else 1.0f

        // Search for peak horizontal meniscus edge in valid fill range (5% to 95% of container height)
        val minSearchIdx = max(1, (numRows * 0.05f).toInt())
        val maxSearchIdx = min(numRows - 2, (numRows * 0.95f).toInt())

        var peakGrad = -1.0f
        for (i in minSearchIdx..maxSearchIdx) {
            if (gradientProfile[i] > peakGrad) {
                peakGrad = gradientProfile[i]
            }
        }

        // Subpixel centroid calculation across the peak plateau
        var weightedIndexSum = 0.0
        var weightSum = 0.0
        if (peakGrad > 0f) {
            val threshold = peakGrad * 0.85f
            for (i in minSearchIdx..maxSearchIdx) {
                if (gradientProfile[i] >= threshold) {
                    val w = (gradientProfile[i] - threshold).toDouble() + 0.001
                    weightedIndexSum += i * w
                    weightSum += w
                }
            }
        }

        val peakIdxFloat = if (weightSum > 0.0) (weightedIndexSum / weightSum).toFloat() else -1.0f

        // 4. Contrast-to-Noise Ratio (CNR) and sharpness assessment
        val cnr = peakGrad / (meanGrad + 0.5f)

        // Quality Gate: Surface must present clear contrast transition
        if (peakGrad < 8.0f || cnr < 1.35f || peakIdxFloat < 0f) {
            return VolumeAnalysisResult(
                detectedFillPercent = 0f,
                requiredFillPercent = requiredFillPercent,
                differencePercent = -requiredFillPercent,
                isPass = false,
                confidence = (cnr / 3.0f).coerceIn(0.15f, 0.45f),
                status = MeasurementStatus.INCONCLUSIVE,
                diagnostics = String.format(Locale.US, "Meniscus boundary ambiguous (peak grad: %.1f, CNR: %.2f). Adjust illumination angle.", peakGrad, cnr),
                containerProfile = profile
            )
        }

        // 5. Fill-Height & Volumetric Calculations
        val liquidHeightPx = (numRows - 1).toFloat() - peakIdxFloat
        val detectedFillPercent = (liquidHeightPx / (numRows - 1).toFloat() * 100.0f).coerceIn(0.0f, 100.0f)
        val diffPercent = detectedFillPercent - requiredFillPercent
        val isPass = abs(diffPercent) <= profile.tolerancePercent
        val status = if (isPass) MeasurementStatus.PASS else MeasurementStatus.OUT_OF_TOLERANCE

        val measuredHeightMm = profile.referenceHeightMm * (detectedFillPercent / 100.0f)
        val estimatedVolumeMl = profile.calculateEstimatedVolumeMl(measuredHeightMm)

        val confidence = (0.50f + (cnr / 8.0f) * 0.45f).coerceIn(0.50f, 0.98f)
        val meniscusRowInFrame = yTop + peakIdxFloat
        val meniscusYNormalized = meniscusRowInFrame.toFloat() / height.toFloat()

        val volStr = if (estimatedVolumeMl != null) {
            String.format(Locale.US, ", Vol: %.1f mL", estimatedVolumeMl)
        } else {
            " (Tapered Neck: Vol Non-linear)"
        }

        val diag = String.format(
            Locale.US,
            "Meniscus detected (Fill: %.1f%%, Diff: %+.1f%%, H: %.1f mm%s, CNR: %.2f)",
            detectedFillPercent,
            diffPercent,
            measuredHeightMm,
            volStr,
            cnr
        )

        return VolumeAnalysisResult(
            detectedFillPercent = detectedFillPercent,
            requiredFillPercent = requiredFillPercent,
            differencePercent = diffPercent,
            isPass = isPass,
            confidence = confidence,
            status = status,
            measuredHeightMm = measuredHeightMm,
            estimatedVolumeMl = estimatedVolumeMl,
            meniscusYNormalized = meniscusYNormalized,
            diagnostics = diag,
            containerProfile = profile
        )
    }
}
