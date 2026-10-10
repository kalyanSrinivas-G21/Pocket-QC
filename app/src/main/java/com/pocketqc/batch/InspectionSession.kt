package com.pocketqc.batch

import com.pocketqc.core.model.Detection
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.inference.InferenceEngine
import com.pocketqc.inference.MockInferenceEngine
import com.pocketqc.inference.MockInspectionScenario
import com.pocketqc.inference.MockSample
import com.pocketqc.ui.components.InspectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionLifecycle {
    IDLE,
    READY,
    INSPECTING,
    COMPLETED
}

data class InspectionSessionState(
    val mode: InspectionMode = InspectionMode.DEFECT_SORT_2D,
    val productName: String = "Biscuit",
    val batchId: String = "B-2026-024",
    val sampleIndex: Int = 0,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val defectiveCount: Int = 0,
    val currentDetections: List<Detection> = emptyList(),
    val liveDetections: List<Detection> = emptyList(),
    val allDetections: List<Detection> = emptyList(),
    val lifecycle: SessionLifecycle = SessionLifecycle.IDLE,
    val isReady: Boolean = false,
    val maxSamples: Int = MockInspectionScenario.TOTAL_SAMPLES,
    val lastSample: MockSample? = null,
    val inferenceFps: Float = 0f,
    val inferenceLatencyMs: Long = 0L,
    val isRealCameraActive: Boolean = false
) {
    val defectRate: Float
        get() = if (totalInspected > 0) defectiveCount.toFloat() / totalInspected.toFloat() else 0f

    val passRate: Float
        get() = if (totalInspected > 0) passedCount.toFloat() / totalInspected.toFloat() else 0f
}

/**
 * Single authoritative coordinator for the active QC inspection session.
 * Connects the UI layer, mock inference engine, and batch statistics manager.
 */
interface InspectionSession {
    val state: StateFlow<InspectionSessionState>
    val lastResult: StateFlow<InspectionResult?>

    fun startSession(
        mode: InspectionMode = InspectionMode.DEFECT_SORT_2D,
        productName: String = "Biscuit",
        batchId: String = "B-2026-024"
    )

    fun updateLiveDetections(
        detections: List<Detection>,
        fps: Float = 0f,
        latencyMs: Long = 0L
    )

    fun setRealCameraActive(isActive: Boolean)

    fun captureSample(liveDetections: List<Detection>? = null): Boolean
    fun completeInspection(): InspectionResult?
    fun reset()
}

class DefaultInspectionSession(
    private val batchManager: BatchManager,
    private val inferenceEngine: InferenceEngine = MockInferenceEngine()
) : InspectionSession {

    private val _state = MutableStateFlow(InspectionSessionState())
    override val state: StateFlow<InspectionSessionState> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<InspectionResult?>(null)
    override val lastResult: StateFlow<InspectionResult?> = _lastResult.asStateFlow()

    override fun startSession(
        mode: InspectionMode,
        productName: String,
        batchId: String
    ) {
        val resolvedProduct = when (mode) {
            InspectionMode.DEFECT_SORT_2D -> productName
            InspectionMode.UV_AFLATOXIN -> "Peanut & Corn Batch"
            InspectionMode.VOLUME_CHECK_3D -> "Grain Silo Sample"
        }

        batchManager.startNewBatch(productName = resolvedProduct, batchId = batchId)

        _state.value = InspectionSessionState(
            mode = mode,
            productName = resolvedProduct,
            batchId = batchId,
            sampleIndex = 0,
            totalInspected = 0,
            passedCount = 0,
            defectiveCount = 0,
            currentDetections = emptyList(),
            allDetections = emptyList(),
            lifecycle = SessionLifecycle.READY,
            isReady = true,
            maxSamples = MockInspectionScenario.TOTAL_SAMPLES,
            lastSample = null,
            liveDetections = emptyList(),
            inferenceFps = 0f,
            inferenceLatencyMs = 0L,
            isRealCameraActive = false
        )
    }

    override fun updateLiveDetections(
        detections: List<Detection>,
        fps: Float,
        latencyMs: Long
    ) {
        _state.value = _state.value.copy(
            liveDetections = detections,
            inferenceFps = fps,
            inferenceLatencyMs = latencyMs
        )
    }

    override fun setRealCameraActive(isActive: Boolean) {
        _state.value = _state.value.copy(isRealCameraActive = isActive)
    }

    override fun captureSample(liveDetections: List<Detection>?): Boolean {
        val current = _state.value
        if (current.lifecycle == SessionLifecycle.COMPLETED) {
            return false
        }

        if (current.sampleIndex >= MockInspectionScenario.TOTAL_SAMPLES) {
            return false // Clamped at scenario limit of 24 samples
        }

        // If real camera is active or live detections are provided, use real detections
        val useReal = current.isRealCameraActive || liveDetections != null
        val sampleDetections = if (useReal) {
            liveDetections ?: current.liveDetections
        } else {
            MockInspectionScenario.getSample(current.sampleIndex).detections
        }

        val isDefective = if (useReal) {
            // Defect if any detection is "Broken" or "Fracture"
            sampleDetections.any { it.className == "Broken" || it.className == "Fracture" }
        } else {
            MockInspectionScenario.getSample(current.sampleIndex).isDefective
        }

        val newPassed = if (!isDefective) current.passedCount + 1 else current.passedCount
        val newDefective = if (isDefective) current.defectiveCount + 1 else current.defectiveCount
        val newTotal = current.totalInspected + 1
        val newAllDetections = current.allDetections + sampleDetections

        val mockSample = if (!useReal) MockInspectionScenario.getSample(current.sampleIndex) else null

        _state.value = current.copy(
            sampleIndex = current.sampleIndex + 1,
            totalInspected = newTotal,
            passedCount = newPassed,
            defectiveCount = newDefective,
            currentDetections = sampleDetections,
            allDetections = newAllDetections,
            lifecycle = SessionLifecycle.INSPECTING,
            lastSample = mockSample
        )
        return true
    }

    override fun completeInspection(): InspectionResult? {
        val current = _state.value
        // Defensive check: Do not generate invalid results for 0 samples
        if (current.totalInspected == 0) {
            return null
        }

        // Avoid repeated completions generating duplicate contradictory results
        if (current.lifecycle == SessionLifecycle.COMPLETED && _lastResult.value != null) {
            return _lastResult.value
        }

        val defectRate = current.defectiveCount.toFloat() / current.totalInspected.toFloat()
        val status = if (defectRate <= 0.15f) InspectionStatus.PASSED else InspectionStatus.FAILED

        val result = InspectionResult(
            productName = current.productName,
            totalInspected = current.totalInspected,
            passed = current.passedCount,
            failed = current.defectiveCount,
            defectRate = defectRate,
            timestamp = System.currentTimeMillis(),
            status = status,
            detections = current.allDetections
        )

        _lastResult.value = result
        batchManager.recordInspection(result)
        batchManager.completeCurrentBatch()

        _state.value = current.copy(
            lifecycle = SessionLifecycle.COMPLETED
        )

        return result
    }

    override fun reset() {
        _state.value = InspectionSessionState()
        _lastResult.value = null
    }
}
