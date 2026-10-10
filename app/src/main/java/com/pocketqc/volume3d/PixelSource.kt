package com.pocketqc.volume3d

import android.graphics.Bitmap

/**
 * Pixel data source abstraction allowing image processing algorithms to run both
 * on live Android [Bitmap]s and deterministic test array buffers on the JVM.
 */
interface PixelSource {
    val width: Int
    val height: Int
    fun getPixel(x: Int, y: Int): Int
}

/**
 * Android [Bitmap] backed pixel source.
 */
class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    override fun getPixel(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

/**
 * Pure memory [IntArray] backed pixel source for high-speed testing on JVM without Android graphics mocks.
 */
class ArrayPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray
) : PixelSource {
    override fun getPixel(x: Int, y: Int): Int {
        val safeX = x.coerceIn(0, width - 1)
        val safeY = y.coerceIn(0, height - 1)
        return pixels[safeY * width + safeX]
    }

    companion object {
        /**
         * Helper to construct a synthetic image with a known meniscus transition at [meniscusY].
         * Above meniscus: headspace luminance [headspaceLuminance].
         * Below meniscus: liquid luminance [liquidLuminance].
         */
        fun createSyntheticBottleImage(
            width: Int = 100,
            height: Int = 200,
            meniscusY: Int = 80,
            headspaceLuminance: Int = 220,
            liquidLuminance: Int = 60
        ): ArrayPixelSource {
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val lum = if (y < meniscusY) headspaceLuminance else liquidLuminance
                // Pack ARGB
                val argb = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
                for (x in 0 until width) {
                    pixels[y * width + x] = argb
                }
            }
            return ArrayPixelSource(width, height, pixels)
        }
    }
}
