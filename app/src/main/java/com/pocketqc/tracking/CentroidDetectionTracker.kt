package com.pocketqc.tracking

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, deterministic detection tracker and debouncer for continuous conveyor inspection.
 *
 * Ensures a physical biscuit moving through the camera viewport across 10-60 consecutive frames
 * is counted exactly ONCE rather than once per frame.
 */
class CentroidDetectionTracker(
    private val maxCentroidDistance: Float = 0.20f,
    private val disappearanceThresholdMs: Long = 1500L,
    private val minIouThreshold: Float = 0.25f
) {
    private val trackIdGenerator = AtomicInteger(1)
    private val activeTracks = mutableMapOf<Int, TrackedDetection>()

    // Statistics accumulated during an active batch run
    var totalInspected: Int = 0
        private set
    var passedCount: Int = 0
        private set
    var defectiveCount: Int = 0
        private set
    var brokenCount: Int = 0
        private set
    var fractureCount: Int = 0
        private set
    var intactCount: Int = 0
        private set

    val defectRate: Float
        get() = if (totalInspected > 0) (defectiveCount.toFloat() / totalInspected.toFloat()) * 100f else 0f

    val passRate: Float
        get() = if (totalInspected > 0) (passedCount.toFloat() / totalInspected.toFloat()) * 100f else 0f

    var isRunning: Boolean = false
        private set

    fun startBatch() {
        isRunning = true
        resetCounts()
    }

    fun stopBatch() {
        isRunning = false
    }

    fun resetCounts() {
        activeTracks.clear()
        totalInspected = 0
        passedCount = 0
        defectiveCount = 0
        brokenCount = 0
        fractureCount = 0
        intactCount = 0
    }

    /**
     * Updates tracker with detections from a new camera frame.
     * Returns the list of newly identified unique items that were first counted in this frame.
     */
    @Synchronized
    fun processFrame(
        detections: List<Detection>,
        currentTimeMs: Long = System.currentTimeMillis()
    ): List<TrackedDetection> {
        if (!isRunning) {
            return emptyList()
        }

        // 1. Expire tracks not seen within the disappearance threshold
        val expiredTrackIds = activeTracks.filter { (_, track) ->
            currentTimeMs - track.lastSeenTimestamp > disappearanceThresholdMs
        }.keys
        expiredTrackIds.forEach { activeTracks.remove(it) }

        val newlyCountedTracks = mutableListOf<TrackedDetection>()
        val matchedTrackIds = mutableSetOf<Int>()

        // 2. Associate detections with existing tracks
        for (det in detections) {
            val detCx = (det.boundingBox.left + det.boundingBox.right) / 2f
            val detCy = (det.boundingBox.top + det.boundingBox.bottom) / 2f

            var bestTrackId: Int? = null
            var bestDistance = Float.MAX_VALUE

            for ((id, track) in activeTracks) {
                if (id in matchedTrackIds) continue

                // Check centroid distance
                val dist = hypot(detCx - track.centroidX, detCy - track.centroidY)
                val iou = calculateIoU(det.boundingBox, track.boundingBox)

                val isMatch = (dist <= maxCentroidDistance) || (iou >= minIouThreshold)
                if (isMatch && dist < bestDistance) {
                    bestDistance = dist
                    bestTrackId = id
                }
            }

            if (bestTrackId != null) {
                // Update existing track - DO NOT increment counts
                val existing = activeTracks[bestTrackId]!!
                existing.boundingBox = det.boundingBox
                existing.confidence = det.confidence
                existing.lastSeenTimestamp = currentTimeMs
                matchedTrackIds.add(bestTrackId)
            } else {
                // Brand new physical object entering viewport!
                val newId = trackIdGenerator.getAndIncrement()
                val newTrack = TrackedDetection(
                    trackId = newId,
                    className = det.className,
                    boundingBox = det.boundingBox,
                    confidence = det.confidence,
                    firstSeenTimestamp = currentTimeMs,
                    lastSeenTimestamp = currentTimeMs,
                    counted = true
                )
                activeTracks[newId] = newTrack
                matchedTrackIds.add(newId)

                // Count this item exactly once
                recordNewDetection(newTrack.className)
                newlyCountedTracks.add(newTrack)
            }
        }

        return newlyCountedTracks
    }

    private fun recordNewDetection(className: String) {
        totalInspected++
        when (className) {
            "Broken" -> {
                brokenCount++
                defectiveCount++
            }
            "Fracture" -> {
                fractureCount++
                defectiveCount++
            }
            "Intact" -> {
                intactCount++
                passedCount++
            }
            else -> {
                // Defensive: If another class string occurs, treat broken/defect substrings as defect
                if (className.contains("Broken", ignoreCase = true) || className.contains("Defect", ignoreCase = true)) {
                    brokenCount++
                    defectiveCount++
                } else {
                    intactCount++
                    passedCount++
                }
            }
        }
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val interLeft = max(box1.left, box2.left)
        val interTop = max(box1.top, box2.top)
        val interRight = min(box1.right, box2.right)
        val interBottom = min(box1.bottom, box2.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val unionArea = box1.area + box2.area - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}
