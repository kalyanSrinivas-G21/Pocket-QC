package com.pocketqc.cv

import com.pocketqc.camera.CameraFrame
import com.pocketqc.core.model.BoundingBox

/**
 * Abstraction for computer vision preprocessing operations
 * (resizing, letterboxing, ROI cropping, tensor conversion) prior to NPU inference.
 */
interface ImagePreprocessor {
    fun cropRoi(frame: CameraFrame, boundingBox: BoundingBox): ByteArray?
    fun normalize(rawBytes: ByteArray): FloatArray
}

class DefaultImagePreprocessor : ImagePreprocessor {
    override fun cropRoi(frame: CameraFrame, boundingBox: BoundingBox): ByteArray? {
        return frame.payload
    }

    override fun normalize(rawBytes: ByteArray): FloatArray {
        return FloatArray(rawBytes.size) { i -> (rawBytes[i].toInt() and 0xFF) / 255f }
    }
}
