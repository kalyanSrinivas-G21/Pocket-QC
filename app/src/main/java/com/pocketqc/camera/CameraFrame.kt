package com.pocketqc.camera

/**
 * Abstract container for camera sensor frames delivered to the inspection pipeline.
 */
data class CameraFrame(
    val frameId: Long,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val rotationDegrees: Int = 0,
    val payload: ByteArray? = null,
    val bitmap: android.graphics.Bitmap? = null
)
