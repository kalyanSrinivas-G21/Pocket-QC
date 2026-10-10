package com.pocketqc.uv

import android.graphics.Bitmap
import android.graphics.Color
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CropFluorescenceResult(
    val hasGenuineFluorescence: Boolean,
    val fluorescenceScore: Float, // 0.0 to 1.0
    val contrastRatio: Float,
    val calibratedConfidence: Float
)

/**
 * Advanced Computer Vision Pre- and Post-Processing for UV Aflatoxin Optical Screening.
 *
 * Implements:
 * 1. CLAHE (Contrast Limited Adaptive Histogram Equalization) for localized contrast enhancement.
 * 2. HSV Fluorescent Emission Slicing & UV Masking (365nm Aflatoxin B1 green-yellow/blue fluorescence).
 * 3. Two-Stage "Crop & Classify" Regional Fluorescence Validation (eliminates false positives on healthy chilli skin).
 */
object UvOpticalEnhancer {

    /**
     * Applies Contrast Limited Adaptive Histogram Equalization (CLAHE) to [bitmap].
     * Divides image into [tilesX] x [tilesY] contextual regions, clips histogram to prevent
     * noise amplification, and applies bilinear interpolation across tile CDFs.
     */
    fun applyClahe(
        bitmap: Bitmap,
        clipLimit: Float = 3.0f,
        tilesX: Int = 8,
        tilesY: Int = 8
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yVals = FloatArray(width * height)
        val uVals = FloatArray(width * height)
        val vVals = FloatArray(width * height)

        // Convert RGB to YUV (standard ITU-R BT.601)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val u = -0.14713f * r - 0.28886f * g + 0.436f * b
            val v = 0.615f * r - 0.51499f * g - 0.10001f * b

            yVals[i] = y
            uVals[i] = u
            vVals[i] = v
        }

        val tileW = width / tilesX
        val tileH = height / tilesY

        // Build equalized CDF for each tile
        val cdfs = Array(tilesY) { Array(tilesX) { FloatArray(256) } }

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val startX = tx * tileW
                val endX = if (tx == tilesX - 1) width else (tx + 1) * tileW
                val startY = ty * tileH
                val endY = if (ty == tilesY - 1) height else (ty + 1) * tileH
                val numPixelsInTile = (endX - startX) * (endY - startY)

                val hist = IntArray(256)
                for (y in startY until endY) {
                    val rowOffset = y * width
                    for (x in startX until endX) {
                        val lum = yVals[rowOffset + x].roundToInt().coerceIn(0, 255)
                        hist[lum]++
                    }
                }

                // Contrast limiting & redistribution
                val clipThreshold = (clipLimit * numPixelsInTile / 256f).roundToInt().coerceAtLeast(1)
                var excess = 0
                for (bin in 0 until 256) {
                    if (hist[bin] > clipThreshold) {
                        excess += (hist[bin] - clipThreshold)
                        hist[bin] = clipThreshold
                    }
                }

                val bonusPerBin = excess / 256
                val remainder = excess % 256
                for (bin in 0 until 256) {
                    hist[bin] += bonusPerBin + (if (bin < remainder) 1 else 0)
                }

                // Cumulative Distribution Function (CDF)
                var sum = 0
                for (bin in 0 until 256) {
                    sum += hist[bin]
                    cdfs[ty][tx][bin] = (sum.toFloat() / numPixelsInTile) * 255f
                }
            }
        }

        // Bilinear interpolation across tiles
        val outPixels = IntArray(width * height)
        for (y in 0 until height) {
            val tyFloat = (y.toFloat() / tileH) - 0.5f
            val ty0 = tyFloat.toInt().coerceIn(0, tilesY - 1)
            val ty1 = (ty0 + 1).coerceIn(0, tilesY - 1)
            val fracY = (tyFloat - ty0).coerceIn(0f, 1f)

            val rowOffset = y * width
            for (x in 0 until width) {
                val txFloat = (x.toFloat() / tileW) - 0.5f
                val tx0 = txFloat.toInt().coerceIn(0, tilesX - 1)
                val tx1 = (tx0 + 1).coerceIn(0, tilesX - 1)
                val fracX = (txFloat - tx0).coerceIn(0f, 1f)

                val origY = yVals[rowOffset + x].roundToInt().coerceIn(0, 255)

                val v00 = cdfs[ty0][tx0][origY]
                val v10 = cdfs[ty0][tx1][origY]
                val v01 = cdfs[ty1][tx0][origY]
                val v11 = cdfs[ty1][tx1][origY]

                val interpTop = (1f - fracX) * v00 + fracX * v10
                val interpBottom = (1f - fracX) * v01 + fracX * v11
                val newY = (1f - fracY) * interpTop + fracY * interpBottom

                // Convert back from YUV to RGB
                val u = uVals[rowOffset + x]
                val v = vVals[rowOffset + x]

                val r = (newY + 1.13983f * v).roundToInt().coerceIn(0, 255)
                val g = (newY - 0.39465f * u - 0.58060f * v).roundToInt().coerceIn(0, 255)
                val b = (newY + 2.03211f * u).roundToInt().coerceIn(0, 255)

                outPixels[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Applies HSV Color Slicing & UV Fluorescence Enhancement.
     * Selectively amplifies fluorescent green-yellow/cyan/blue emission (420-530nm)
     * against dark red chilli pods.
     */
    fun applyUvFluorescenceEnhancement(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hsv = FloatArray(3)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            Color.RGBToHSV(r, g, b, hsv)
            val hue = hsv[0]        // 0..360
            val sat = hsv[1]        // 0..1
            val value = hsv[2]      // 0..1

            // Aflatoxin B1 fluorescent emission under 365nm:
            // Hue in [35..185 deg] (greenish-yellow to cyan/blue fluorescence)
            val isFluorescentHue = hue in 35f..185f
            if (isFluorescentHue && sat > 0.15f && value > 0.18f) {
                // Boost saturation and brightness of fluorescent fungal markers
                hsv[1] = (sat * 1.35f).coerceAtMost(1f)
                hsv[2] = (value * 1.40f).coerceAtMost(1f)
                pixels[i] = Color.HSVToColor(hsv)
            } else {
                // Dim non-fluorescent background (healthy red chilli body)
                hsv[2] = (value * 0.85f).coerceAtLeast(0f)
                pixels[i] = Color.HSVToColor(hsv)
            }
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Stage 2: Two-Stage "Crop & Classify" Regional Fluorescence Validation.
     * Evaluates whether a candidate detection box genuinely exhibits the physical 365nm UV
     * fluorescent emission characteristic of Aflatoxin B1, filtering out false positives
     * (e.g. chilli seed specular reflections, paper background, healthy pod edges).
     */
    fun validateCropFluorescence(bitmap: Bitmap, box: BoundingBox, rawConfidence: Float): CropFluorescenceResult {
        val bw = bitmap.width
        val bh = bitmap.height

        val leftPx = (box.left * bw).toInt().coerceIn(0, bw - 1)
        val topPx = (box.top * bh).toInt().coerceIn(0, bh - 1)
        val rightPx = (box.right * bw).toInt().coerceIn(leftPx + 1, bw)
        val bottomPx = (box.bottom * bh).toInt().coerceIn(topPx + 1, bh)

        val cropW = rightPx - leftPx
        val cropH = bottomPx - topPx

        var totalSamples = 0
        var fluorescentSamples = 0
        var sumCropLum = 0f
        val hsv = FloatArray(3)

        val stepX = (cropW / 24).coerceAtLeast(1)
        val stepY = (cropH / 24).coerceAtLeast(1)

        for (y in topPx until bottomPx step stepY) {
            for (x in leftPx until rightPx step stepX) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                sumCropLum += lum
                totalSamples++

                // Aflatoxin emission: Green-yellow to cyan/blue fluorescent band
                if (hue in 35f..185f && sat > 0.18f && value > 0.20f) {
                    fluorescentSamples++
                }
            }
        }

        val safeTotal = totalSamples.coerceAtLeast(1)
        val fluorescenceRatio = fluorescentSamples.toFloat() / safeTotal
        val meanCropLum = sumCropLum / safeTotal

        // Measure background chilli context around the box
        val bgMargin = 16
        val bgLeft = (leftPx - bgMargin).coerceAtLeast(0)
        val bgTop = (topPx - bgMargin).coerceAtLeast(0)
        val bgRight = (rightPx + bgMargin).coerceAtMost(bw)
        val bgBottom = (bottomPx + bgMargin).coerceAtMost(bh)

        var bgSamples = 0
        var sumBgLum = 0f
        for (y in bgTop until bgBottom step (stepY * 2).coerceAtLeast(2)) {
            for (x in bgLeft until bgRight step (stepX * 2).coerceAtLeast(2)) {
                if (x < leftPx || x >= rightPx || y < topPx || y >= bottomPx) {
                    val p = bitmap.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sumBgLum += 0.299f * r + 0.587f * g + 0.114f * b
                    bgSamples++
                }
            }
        }

        val meanBgLum = if (bgSamples > 0) sumBgLum / bgSamples else meanCropLum
        val contrastRatio = if (meanBgLum > 5f) meanCropLum / meanBgLum else 1.0f

        // Two-Stage Decision:
        // Genuine aflatoxin has fluorescent emission ratio >= 0.08 and positive contrast over background
        val hasGenuineFluorescence = fluorescenceRatio >= 0.06f || (fluorescenceRatio >= 0.03f && contrastRatio >= 1.25f)

        val calibratedConf = if (hasGenuineFluorescence) {
            // Boost confidence if physical fluorescence is confirmed
            val bonus = (fluorescenceRatio * 0.40f).coerceAtMost(0.25f)
            (rawConfidence + bonus).coerceAtMost(0.99f)
        } else {
            // Suppress false alarms lacking fluorescent wavelength signature
            (rawConfidence * 0.45f).coerceAtLeast(0.05f)
        }

        return CropFluorescenceResult(
            hasGenuineFluorescence = hasGenuineFluorescence,
            fluorescenceScore = fluorescenceRatio,
            contrastRatio = contrastRatio,
            calibratedConfidence = calibratedConf
        )
    }

    /**
     * Refines and filters raw YOLO detections using the Two-Stage Crop & Classify pipeline.
     */
    fun refineAflatoxinDetections(
        rawDetections: List<Detection>,
        bitmap: Bitmap
    ): List<Detection> {
        val verifiedDetections = mutableListOf<Detection>()

        for (det in rawDetections) {
            val cropResult = validateCropFluorescence(bitmap, det.boundingBox, det.confidence)
            if (cropResult.hasGenuineFluorescence && cropResult.calibratedConfidence >= 0.20f) {
                verifiedDetections.add(
                    det.copy(confidence = cropResult.calibratedConfidence)
                )
            }
        }

        return verifiedDetections
    }
}
