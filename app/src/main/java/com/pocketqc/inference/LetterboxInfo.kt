package com.pocketqc.inference

/**
 * Encapsulates letterbox scaling and padding parameters used to prepare
 * arbitrary-dimension input images for the fixed 640x640 model input.
 */
data class LetterboxInfo(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val origW: Int,
    val origH: Int
) {
    /**
     * Inverts a letterbox coordinate (in 640x640 space) back to original image space.
     */
    fun invertX(xLetterbox: Float): Float {
        return ((xLetterbox - padX) / scale).coerceIn(0f, origW.toFloat())
    }

    /**
     * Inverts a letterbox coordinate (in 640x640 space) back to original image space.
     */
    fun invertY(yLetterbox: Float): Float {
        return ((yLetterbox - padY) / scale).coerceIn(0f, origH.toFloat())
    }
}
