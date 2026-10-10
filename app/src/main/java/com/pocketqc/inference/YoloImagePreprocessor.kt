package com.pocketqc.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

data class PreprocessedBitmap(
    val floatBuffer: FloatBuffer,
    val letterboxInfo: LetterboxInfo
)

/**
 * High-performance, zero-allocation YOLOv8n image preprocessor.
 *
 * Transforms camera/image inputs into planar CHW float32 tensors [1, 3, 640, 640]
 * normalized to [0.0, 1.0] with aspect-ratio preserving letterbox padding.
 *
 * When provided with [YoloPreallocatedBuffers], all operations execute with ZERO heap/direct
 * allocations in steady state:
 * - Direct draw onto preallocated letterbox Canvas (eliminates intermediate Bitmaps)
 * - Reuse of persistent DirectByteBuffer
 * - Reuse of persistent IntArray pixel buffer
 */
object YoloImagePreprocessor {

    /**
     * Preprocesses [bitmap] into letterbox tensor.
     * When [buffers] is supplied, reuses preallocated buffers without allocations.
     */
    fun preprocess(
        bitmap: Bitmap,
        targetWidth: Int = YoloConfig.INPUT_WIDTH,
        targetHeight: Int = YoloConfig.INPUT_HEIGHT,
        buffers: YoloPreallocatedBuffers? = null
    ): PreprocessedBitmap {
        val srcW = bitmap.width
        val srcH = bitmap.height

        val scale = minOf(targetWidth.toFloat() / srcW, targetHeight.toFloat() / srcH)
        val scaledW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val padX = (targetWidth - scaledW) / 2f
        val padY = (targetHeight - scaledH) / 2f

        val letterboxInfo = LetterboxInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            origW = srcW,
            origH = srcH
        )

        val channelSize = targetWidth * targetHeight

        if (buffers != null && buffers.canvas != null && buffers.letterboxBitmap != null) {
            // Reusable zero-allocation path
            val canvas = buffers.canvas
            canvas.drawColor(buffers.neutralGreyColor)

            val matrix = buffers.transformMatrix
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(padX, padY)
            canvas.drawBitmap(bitmap, matrix, buffers.filterPaint)

            val pixels = buffers.pixels
            buffers.letterboxBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

            val floatBuffer = buffers.floatBuffer
            floatBuffer.clear()

            val inv255 = 1.0f / 255.0f
            val channel2 = 2 * channelSize
            for (i in 0 until channelSize) {
                val pixel = pixels[i]
                floatBuffer.put(i, (pixel shr 16 and 0xFF) * inv255)
                floatBuffer.put(channelSize + i, (pixel shr 8 and 0xFF) * inv255)
                floatBuffer.put(channel2 + i, (pixel and 0xFF) * inv255)
            }
            floatBuffer.rewind()

            return PreprocessedBitmap(floatBuffer, letterboxInfo)
        }

        // Fallback path for one-off calls where persistent buffers are unavailable
        val letterboxedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxedBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val scaledBitmap = if (srcW == scaledW && srcH == scaledH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        }

        canvas.drawBitmap(scaledBitmap, padX, padY, null)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val pixels = IntArray(channelSize)
        letterboxedBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        letterboxedBitmap.recycle()

        val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * channelSize * 4)
            .order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val inv255 = 1.0f / 255.0f
        val channel2 = 2 * channelSize
        for (i in 0 until channelSize) {
            val pixel = pixels[i]
            floatBuffer.put(i, (pixel shr 16 and 0xFF) * inv255)
            floatBuffer.put(channelSize + i, (pixel shr 8 and 0xFF) * inv255)
            floatBuffer.put(channel2 + i, (pixel and 0xFF) * inv255)
        }
        floatBuffer.rewind()

        return PreprocessedBitmap(
            floatBuffer = floatBuffer,
            letterboxInfo = letterboxInfo
        )
    }

    /**
     * Unified zero-allocation preprocessor that combines sensor rotation and letterbox scaling
     * into a single transformation matrix, drawing directly onto the preallocated letterbox canvas.
     *
     * Eliminates both the intermediate rotated Bitmap and the intermediate scaled Bitmap.
     */
    fun preprocessWithTransform(
        rawBitmap: Bitmap,
        rotationDegrees: Int,
        targetWidth: Int = YoloConfig.INPUT_WIDTH,
        targetHeight: Int = YoloConfig.INPUT_HEIGHT,
        buffers: YoloPreallocatedBuffers
    ): PreprocessedBitmap {
        val rawW = rawBitmap.width
        val rawH = rawBitmap.height

        val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
        val effW = if (isRotated) rawH else rawW
        val effH = if (isRotated) rawW else rawH

        val scale = minOf(targetWidth.toFloat() / effW, targetHeight.toFloat() / effH)
        val scaledW = (effW * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (effH * scale).roundToInt().coerceAtLeast(1)
        val padX = (targetWidth - scaledW) / 2f
        val padY = (targetHeight - scaledH) / 2f

        val letterboxInfo = LetterboxInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            origW = effW,
            origH = effH
        )

        val canvas = checkNotNull(buffers.canvas) { "Canvas unavailable in preallocated buffers" }
        canvas.drawColor(buffers.neutralGreyColor)

        val matrix = buffers.transformMatrix
        matrix.reset()
        if (rotationDegrees != 0) {
            matrix.postTranslate(-rawW / 2f, -rawH / 2f)
            matrix.postRotate(rotationDegrees.toFloat())
            matrix.postScale(scale, scale)
            matrix.postTranslate(padX + scaledW / 2f, padY + scaledH / 2f)
        } else {
            matrix.setScale(scale, scale)
            matrix.postTranslate(padX, padY)
        }

        canvas.drawBitmap(rawBitmap, matrix, buffers.filterPaint)

        val channelSize = targetWidth * targetHeight
        val pixels = buffers.pixels
        checkNotNull(buffers.letterboxBitmap).getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        val floatBuffer = buffers.floatBuffer
        floatBuffer.clear()
        val inv255 = 1.0f / 255.0f
        val channel2 = 2 * channelSize
        for (i in 0 until channelSize) {
            val pixel = pixels[i]
            floatBuffer.put(i, (pixel shr 16 and 0xFF) * inv255)
            floatBuffer.put(channelSize + i, (pixel shr 8 and 0xFF) * inv255)
            floatBuffer.put(channel2 + i, (pixel and 0xFF) * inv255)
        }
        floatBuffer.rewind()

        return PreprocessedBitmap(floatBuffer, letterboxInfo)
    }
}
