package com.pocketqc.autolabel.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.pocketqc.autolabel.model.AutoLabelClass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CropAnalysisResult(
    val estimatedClass: AutoLabelClass,
    val edgeDensity: Float,
    val aspectRatio: Float,
    val isAmbiguous: Boolean,
    val ambiguityReason: String?
)

/**
 * Lightweight on-device visual crop analyzer.
 * Extracts geometric and edge fissure statistics from biscuit crops to provide
 * explainable visual evidence and identify ambiguous samples.
 */
object CropFeatureAnalyzer {

    /**
     * Analyzes a biscuit crop Bitmap and extracts visual quality metrics.
     */
    fun analyze(cropBitmap: Bitmap): CropAnalysisResult {
        val width = cropBitmap.width
        val height = cropBitmap.height

        if (width < 8 || height < 8) {
            return CropAnalysisResult(
                estimatedClass = AutoLabelClass.BROKEN,
                edgeDensity = 0f,
                aspectRatio = if (height > 0) width.toFloat() / height else 1f,
                isAmbiguous = true,
                ambiguityReason = "Crop too small for reliable feature extraction (${width}x${height})"
            )
        }

        val aspectRatio = width.toFloat() / height.toFloat()

        // Downsample crop for fast, low-memory pixel scan
        val targetDim = 64
        val scaled = if (width > targetDim || height > targetDim) {
            Bitmap.createScaledBitmap(cropBitmap, targetDim, targetDim, true)
        } else {
            cropBitmap
        }

        val sW = scaled.width
        val sH = scaled.height
        val pixels = IntArray(sW * sH)
        scaled.getPixels(pixels, 0, sW, 0, 0, sW, sH)

        if (scaled != cropBitmap) {
            scaled.recycle()
        }

        // Grayscale luminance map
        val luma = FloatArray(sW * sH)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Compute gradient / fissure energy in central 70% interior (ignoring biscuit perimeter edge)
        var highGradientCount = 0
        var totalInteriorPixels = 0
        val marginX = (sW * 0.15f).toInt()
        val marginY = (sH * 0.15f).toInt()

        for (y in marginY until sH - marginY) {
            for (x in marginX until sW - marginX) {
                val idx = y * sW + x
                val dx = abs(luma[idx + 1] - luma[idx - 1])
                val dy = abs(luma[(y + 1) * sW + x] - luma[(y - 1) * sW + x])
                val grad = dx + dy
                if (grad > 38f) { // High contrast edge or crack
                    highGradientCount++
                }
                totalInteriorPixels++
            }
        }

        val edgeDensity = if (totalInteriorPixels > 0) {
            highGradientCount.toFloat() / totalInteriorPixels.toFloat()
        } else {
            0f
        }

        // Classification heuristics
        val isExtremeAspect = aspectRatio < 0.70f || aspectRatio > 1.35f
        val hasProminentFissure = edgeDensity > 0.12f
        val hasModerateFissure = edgeDensity in 0.05f..0.12f

        val (estimatedClass, isAmbiguous, reason) = when {
            isExtremeAspect -> {
                Triple(AutoLabelClass.BROKEN, false, null)
            }
            hasProminentFissure -> {
                Triple(AutoLabelClass.BROKEN, false, null)
            }
            hasModerateFissure -> {
                Triple(AutoLabelClass.FRACTURE, false, null)
            }
            edgeDensity < 0.05f && (aspectRatio in 0.85f..1.18f) -> {
                Triple(AutoLabelClass.INTACT, false, null)
            }
            else -> {
                Triple(
                    AutoLabelClass.FRACTURE,
                    true,
                    "Ambiguous edge profile: edge density %.3f with aspect ratio %.2f".format(edgeDensity, aspectRatio)
                )
            }
        }

        return CropAnalysisResult(
            estimatedClass = estimatedClass,
            edgeDensity = edgeDensity,
            aspectRatio = aspectRatio,
            isAmbiguous = isAmbiguous,
            ambiguityReason = reason
        )
    }
}
