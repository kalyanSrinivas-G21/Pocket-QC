package com.pocketqc.cv

import com.pocketqc.core.model.BoundingBox
import kotlin.math.max
import kotlin.math.min

object BoundingBoxUtils {

    /**
     * Computes Intersection over Union (IoU) between two bounding boxes.
     */
    fun computeIoU(boxA: BoundingBox, boxB: BoundingBox): Float {
        val xA = max(boxA.left, boxB.left)
        val yA = max(boxA.top, boxB.top)
        val xB = min(boxA.right, boxB.right)
        val yB = min(boxA.bottom, boxB.bottom)

        val interArea = max(0f, xB - xA) * max(0f, yB - yA)
        val unionArea = boxA.area + boxB.area - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
