package com.pocketqc.camera

import com.pocketqc.core.model.BoundingBox

/**
 * Transforms normalized bounding box coordinates from the analyzed camera frame coordinate space
 * to the PreviewView coordinate space according to ScaleType.FILL_CENTER (aspect fill with center crop).
 */
object CameraCoordinateTransformer {

    fun transformToPreview(
        box: BoundingBox,
        camWidth: Int,
        camHeight: Int,
        viewWidth: Float,
        viewHeight: Float
    ): BoundingBox {
        if (camWidth <= 0 || camHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            return box
        }

        // Fill center scaling factor (aspect fill)
        val scale = maxOf(viewWidth / camWidth.toFloat(), viewHeight / camHeight.toFloat())
        val scaledCamW = camWidth * scale
        val scaledCamH = camHeight * scale

        // Offsets due to center cropping (typically <= 0)
        val dx = (viewWidth - scaledCamW) / 2f
        val dy = (viewHeight - scaledCamH) / 2f

        // Convert normalized coordinates in camera space to camera pixel coordinates
        val camLeft = box.left * camWidth
        val camTop = box.top * camHeight
        val camRight = box.right * camWidth
        val camBottom = box.bottom * camHeight

        // Transform to view pixel coordinates
        val viewLeft = camLeft * scale + dx
        val viewTop = camTop * scale + dy
        val viewRight = camRight * scale + dx
        val viewBottom = camBottom * scale + dy

        // Normalize back to view dimensions [0.0, 1.0] and clamp
        val normLeft = (viewLeft / viewWidth).coerceIn(0f, 1f)
        val normTop = (viewTop / viewHeight).coerceIn(0f, 1f)
        val normRight = (viewRight / viewWidth).coerceIn(0f, 1f)
        val normBottom = (viewBottom / viewHeight).coerceIn(0f, 1f)

        return BoundingBox(
            left = normLeft,
            top = normTop,
            right = normRight,
            bottom = normBottom
        )
    }
}
