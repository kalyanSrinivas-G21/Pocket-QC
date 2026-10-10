package com.pocketqc.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Reusable preallocated memory buffers for the YOLO CPU inference pipeline.
 *
 * Preallocates all large memory buffers once to eliminate per-frame GC allocation churn:
 * - Direct native [ByteBuffer] (~4.92 MB for 640x640x3) for ONNX input tensor layout [1, 3, H, W]
 * - Direct [FloatBuffer] view onto the native byte buffer
 * - Letterbox [Bitmap] (H x W ARGB_8888)
 * - Hardware/native [Canvas] and [Paint] for zero-intermediate scaling
 * - Reusable pixel [IntArray] (H x W ints)
 * - Output [FloatArray] (numFeatures x numAnchors floats) for ONNX output tensor
 *
 * Sized dynamically based on the active [ModelConfig].
 * Owned exclusively by the active inference worker to guarantee thread safety.
 */
class YoloPreallocatedBuffers(
    val targetWidth: Int = YoloConfig.INPUT_WIDTH,
    val targetHeight: Int = YoloConfig.INPUT_HEIGHT,
    val numAnchors: Int = YoloConfig.NUM_ANCHORS,
    val numClasses: Int = YoloConfig.NUM_CLASSES
) {
    constructor(modelConfig: ModelConfig) : this(
        targetWidth = modelConfig.inputWidth,
        targetHeight = modelConfig.inputHeight,
        numAnchors = modelConfig.numAnchors,
        numClasses = modelConfig.numClasses
    )

    val channelSize: Int = targetWidth * targetHeight
    val totalOutputSize: Int = (4 + numClasses) * numAnchors

    // 1. Native Direct ByteBuffer (1 * 3 * H * W * 4 bytes)
    val byteBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * channelSize * 4)
        .order(ByteOrder.nativeOrder())

    // 2. FloatBuffer view onto native direct memory
    val floatBuffer: FloatBuffer = byteBuffer.asFloatBuffer()

    // 3. Preallocated pixel array for getPixels
    val pixels: IntArray = IntArray(channelSize)

    // 4. Preallocated output array for ONNX tensor result
    val outputFloatArray: FloatArray = FloatArray(totalOutputSize)

    // 5. Letterbox Bitmap and Canvas (safe on JVM unit test where android framework may be stubbed)
    val letterboxBitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    val canvas: Canvas? = letterboxBitmap?.let { bmp ->
        runCatching { Canvas(bmp) }.getOrNull()
    }

    // 6. Transformation matrix and filter paint for zero-allocation letterbox scaling
    val transformMatrix: Matrix = runCatching { Matrix() }.getOrElse { Matrix() }
    val filterPaint: Paint = runCatching { Paint(Paint.FILTER_BITMAP_FLAG) }.getOrElse { Paint() }

    // Constant neutral grey padding color (RGB 114, 114, 114, Alpha 255)
    val neutralGreyColor: Int = (0xFF shl 24) or (114 shl 16) or (114 shl 8) or 114

    private var isReleased = false

    fun release() {
        if (!isReleased) {
            isReleased = true
            letterboxBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }
}
