package com.pocketqc.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.pocketqc.batch.BatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.Detection
import com.pocketqc.core.utils.BatchIdGenerator
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.settings.HapticManager
import com.pocketqc.settings.TtsManager
import com.pocketqc.tracking.CentroidDetectionTracker
import com.pocketqc.ui.components.InspectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable UI state model for the 2D Conveyor Inspection workflow.
 */
data class Inspection2DUiState(
    val isBatchRunning: Boolean = false,
    val activeBatchId: String = "",
    val batchStartTime: Long = 0L,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val defectiveCount: Int = 0,
    val brokenCount: Int = 0,
    val fractureCount: Int = 0,
    val intactCount: Int = 0,
    val defectRate: Float = 0f,
    val passRate: Float = 0f,
    val liveDetections: List<Detection> = emptyList(),
    val fps: Float = 0f,
    val latencyMs: Long = 0L,
    val error: String? = null,
    val completedBatch: Batch? = null
)

/**
 * Lifecycle-aware ViewModel for 2D Conveyor Inspection.
 *
 * Responsibilities:
 * - Owns and preserves inspection batch state across configuration changes and recompositions.
 * - Enforces deterministic batch lifecycle state machine transitions:
 *     STOPPED -> START -> RUNNING -> STOP -> COMPLETED
 * - Rejects invalid transitions (e.g. STOPPED -> STOP, RUNNING -> START).
 * - Enforces honest zero-item batch outcome (0 inspected items can never be APPROVED).
 * - Coordinates temporal debouncing tracker with live frame detections.
 * - Persists completed batches to [BatchManager].
 *
 * Constraints:
 * - Never holds Android Context, CameraX, PreviewView, or Bitmap rendering references.
 */
class Inspection2DViewModel(
    private val batchManager: BatchManager,
    private val tracker: CentroidDetectionTracker = CentroidDetectionTracker(),
    private val ttsManager: TtsManager? = null,
    private val hapticManager: HapticManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(Inspection2DUiState())
    val uiState: StateFlow<Inspection2DUiState> = _uiState.asStateFlow()

    /**
     * Starts a new inspection batch session.
     * Enforces transition: STOPPED -> RUNNING.
     * Rejects invalid transition if already running.
     */
    fun startBatch(): Boolean {
        if (_uiState.value.isBatchRunning) {
            // Reject invalid transition: RUNNING -> START
            return false
        }

        val newBatchId = BatchIdGenerator.generate(prefix = "PQ")
        val startTime = System.currentTimeMillis()
        tracker.startBatch()

        _uiState.value = _uiState.value.copy(
            isBatchRunning = true,
            activeBatchId = newBatchId,
            batchStartTime = startTime,
            totalInspected = 0,
            passedCount = 0,
            defectiveCount = 0,
            brokenCount = 0,
            fractureCount = 0,
            intactCount = 0,
            defectRate = 0f,
            passRate = 0f,
            completedBatch = null,
            error = null
        )

        ttsManager?.announceBatchStarted()
        return true
    }

    /**
     * Stops the active inspection batch, finalizes statistics, and persists the result.
     * Enforces transition: RUNNING -> STOPPED.
     * Rejects invalid transition if not running.
     *
     * @return The completed [Batch] if successfully stopped, or null if transition was rejected.
     */
    fun stopBatch(): Batch? {
        if (!_uiState.value.isBatchRunning) {
            // Reject invalid transition: STOPPED -> STOP
            return null
        }

        tracker.stopBatch()
        val endTime = System.currentTimeMillis()
        val total = tracker.totalInspected
        val pass = tracker.passedCount
        val defect = tracker.defectiveCount
        val defectRate = tracker.defectRate

        // An empty batch (0 inspected) cannot be approved
        val isPass = (total > 0 && defectRate <= 12f)
        val batchStatus = if (isPass) BatchStatus.APPROVED else BatchStatus.REJECTED

        val activeId = _uiState.value.activeBatchId
        val startTime = _uiState.value.batchStartTime

        val qrPayload = QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = activeId,
            type = "2D",
            total = total,
            pass = pass,
            defectOrFlagged = defect,
            rate = defectRate,
            result = if (isPass) "PASS" else "REJECT"
        )

        val completed = Batch(
            batchId = activeId,
            productName = "Biscuit Conveyor Lot",
            inspectionCount = total,
            passCount = pass,
            failCount = defect,
            defectRate = if (total > 0) defect.toFloat() / total.toFloat() else 0f,
            timestamp = endTime,
            status = batchStatus,
            mode = InspectionMode.DEFECT_SORT_2D,
            startTime = startTime,
            endTime = endTime,
            brokenCount = tracker.brokenCount,
            fractureCount = tracker.fractureCount,
            intactCount = tracker.intactCount,
            qrPayload = qrPayload
        )

        batchManager.saveBatch(completed)
        ttsManager?.announceBatchCompleted(isPass)

        _uiState.value = _uiState.value.copy(
            isBatchRunning = false,
            completedBatch = completed
        )

        return completed
    }

    /**
     * Ingests live telemetry from the camera inference analyzer.
     * When batch is running, runs detections through [CentroidDetectionTracker]
     * to ensure unique physical item counting.
     */
    fun onLiveTelemetryUpdated(
        detections: List<Detection>,
        fps: Float,
        latencyMs: Long
    ) {
        val currentState = _uiState.value
        if (currentState.isBatchRunning && detections.isNotEmpty()) {
            val newlyCounted = tracker.processFrame(detections)
            if (newlyCounted.any { it.className == "Broken" || it.className == "Fracture" }) {
                ttsManager?.announceDefectDetected()
                hapticManager?.vibrateDefect()
            }

            _uiState.value = currentState.copy(
                totalInspected = tracker.totalInspected,
                passedCount = tracker.passedCount,
                defectiveCount = tracker.defectiveCount,
                brokenCount = tracker.brokenCount,
                fractureCount = tracker.fractureCount,
                intactCount = tracker.intactCount,
                defectRate = tracker.defectRate,
                passRate = tracker.passRate,
                liveDetections = detections,
                fps = fps,
                latencyMs = latencyMs
            )
        } else {
            _uiState.value = currentState.copy(
                liveDetections = detections,
                fps = fps,
                latencyMs = latencyMs
            )
        }
    }

    /**
     * Resets the completed batch reference once the UI navigation has consumed it.
     */
    fun clearCompletedBatch() {
        _uiState.value = _uiState.value.copy(completedBatch = null)
    }

    /**
     * Sets an operational error message (e.g. camera failed or model unavailable).
     */
    fun setError(message: String?) {
        _uiState.value = _uiState.value.copy(error = message)
    }
}
