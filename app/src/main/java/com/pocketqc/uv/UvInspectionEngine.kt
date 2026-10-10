package com.pocketqc.uv

import android.graphics.Bitmap
import com.pocketqc.batch.BatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.Detection
import com.pocketqc.core.utils.BatchIdGenerator
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.inference.InferenceEngine
import com.pocketqc.ui.components.InspectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time coordinator for Chilli sample optical Aflatoxin screening.
 * Integrates camera acquisition, real YOLOv8n Aflatoxin ONNX inference,
 * manual UV illumination guidance, and structured batch reporting.
 *
 * Fully replaces random/mock simulation with actual model evaluation.
 */
class UvInspectionEngine(
    private val uvController: UvLightController? = null,
    private val batchManager: BatchManager,
    private val inferenceEngine: InferenceEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    interSampleWaitSeconds: Int = 10,
    private val analysisDurationMs: Long = 1200L,
    private val liveDetectionsProvider: (() -> List<Detection>)? = null
) {
    var interSampleWaitSeconds: Int = interSampleWaitSeconds
        set(value) {
            field = value.coerceIn(3, 60)
        }

    private val _state = MutableStateFlow(
        UvInspectionState(
            hardwareStatus = uvController?.status ?: UvHardwareStatus.NotConnected()
        )
    )
    val state: StateFlow<UvInspectionState> = _state.asStateFlow()

    private var inspectionJob: Job? = null
    @Volatile
    private var isRunning: Boolean = false
    @Volatile
    private var skipCountdownRequested: Boolean = false

    @Volatile
    private var analysisWindowBestDetections: List<Detection> = emptyList()

    @Volatile
    private var frameProvider: (() -> Bitmap?)? = null

    fun setFrameProvider(provider: () -> Bitmap?) {
        frameProvider = provider
    }

    /**
     * Receives live telemetry and detections streamed from the CameraX analyzer.
     */
    fun updateLiveTelemetry(detections: List<Detection>, fps: Float, latencyMs: Long) {
        if (_state.value.phase == UvInspectionPhase.ANALYZING && detections.isNotEmpty()) {
            synchronized(this) {
                val currentBestMaxConf = analysisWindowBestDetections.maxOfOrNull { it.confidence } ?: 0f
                val newMaxConf = detections.maxOfOrNull { it.confidence } ?: 0f
                if (detections.size > analysisWindowBestDetections.size || newMaxConf > currentBestMaxConf) {
                    analysisWindowBestDetections = detections
                }
            }
        }
        _state.value = _state.value.copy(
            liveDetections = detections,
            inferenceFps = fps,
            inferenceLatencyMs = latencyMs,
            isCameraActive = true
        )
    }

    /**
     * Allows the operator to skip the remaining inter-sample countdown and scan immediately.
     */
    fun triggerImmediateScan() {
        skipCountdownRequested = true
    }

    fun startBatch(productName: String = "Red Chilli Sample Lot"): String {
        inspectionJob?.cancel()
        skipCountdownRequested = false
        isRunning = true

        val batchId = BatchIdGenerator.generate(prefix = "PQ")
        val startTime = System.currentTimeMillis()
        batchManager.startNewBatch(productName, batchId)

        _state.value = UvInspectionState(
            phase = UvInspectionPhase.STARTING,
            sampleIndex = 0,
            totalInspected = 0,
            passedCount = 0,
            flaggedCount = 0,
            sampleRecords = emptyList(),
            batchId = batchId,
            startTime = startTime,
            hardwareStatus = uvController?.status ?: UvHardwareStatus.NotConnected(),
            isCameraActive = _state.value.isCameraActive
        )

        inspectionJob = scope.launch {
            runSampleCycle()
        }

        return batchId
    }

    private suspend fun runSampleCycle() {
        try {
            while (scope.isActive && isRunning) {
                val currentSampleNumber = _state.value.sampleIndex + 1

                if (!isRunning) break
                // 1. Preparation & manual UV light phase
                _state.value = _state.value.copy(
                    phase = UvInspectionPhase.UV_ON,
                    sampleIndex = currentSampleNumber,
                    currentSampleResult = null
                )
                uvController?.turnOn()
                if (!isRunning) break
                _state.value = _state.value.copy(isLightOn = uvController?.isLightOn ?: false)
                delay(300)
                if (!isRunning) break

                // 2. Optical scanning / camera alignment phase
                _state.value = _state.value.copy(phase = UvInspectionPhase.SCANNING)
                delay(400)

                // 3. Real ML Model analysis phase
                synchronized(this) {
                    analysisWindowBestDetections = emptyList()
                }
                _state.value = _state.value.copy(phase = UvInspectionPhase.ANALYZING)
                delay(analysisDurationMs.coerceAtLeast(100L))

                // Query real detections: liveDetectionsProvider > accumulated detections during analysis window > latest liveDetections
                val rawSpots: List<Detection> = liveDetectionsProvider?.invoke()
                    ?: synchronized(this) {
                        if (analysisWindowBestDetections.isNotEmpty()) {
                            analysisWindowBestDetections
                        } else {
                            _state.value.liveDetections
                        }
                    }

                // Direct YOLOv8n Aflatoxin detection - no heuristic suppression algorithms
                val detectedSpots = rawSpots

                val spotsCount = detectedSpots.size
                val maxConf = detectedSpots.maxOfOrNull { it.confidence } ?: 0f
                val isFlagged = spotsCount > 0
                val sampleResult = if (isFlagged) UvSampleResult.FLAGGED else UvSampleResult.PASS
                val fluorescenceScore = if (isFlagged) maxConf * 100f else 0f
                val exposureMs = _state.value.inferenceLatencyMs.coerceAtLeast(analysisDurationMs)

                // 4. Record real sample result
                uvController?.turnOff()
                _state.value = _state.value.copy(
                    phase = UvInspectionPhase.UV_OFF,
                    isLightOn = uvController?.isLightOn ?: false
                )
                delay(100)

                val record = UvSampleRecord(
                    sampleNumber = currentSampleNumber,
                    timestamp = System.currentTimeMillis(),
                    result = sampleResult,
                    fluorescenceScore = fluorescenceScore,
                    exposureTimeMs = exposureMs,
                    spotsDetected = spotsCount,
                    confidence = maxConf,
                    detections = detectedSpots,
                    modelVersion = "Aflatoxin-YOLOv8n-RedChilli-v2"
                )

                val newTotal = _state.value.totalInspected + 1
                val newPassed = if (!isFlagged) _state.value.passedCount + 1 else _state.value.passedCount
                val newFlagged = if (isFlagged) _state.value.flaggedCount + 1 else _state.value.flaggedCount
                val newRecords = _state.value.sampleRecords + record

                _state.value = _state.value.copy(
                    phase = UvInspectionPhase.RECORD_RESULT,
                    currentSampleResult = sampleResult,
                    currentSpotsCount = spotsCount,
                    currentConfidence = maxConf,
                    currentFluorescenceScore = fluorescenceScore,
                    totalInspected = newTotal,
                    passedCount = newPassed,
                    flaggedCount = newFlagged,
                    sampleRecords = newRecords
                )
                delay(1200)

                // 5. Inter-sample countdown wait period (operator can tap SCAN NOW to skip)
                _state.value = _state.value.copy(phase = UvInspectionPhase.WAITING_15_SECONDS)
                val waitSeconds = interSampleWaitSeconds
                for (remaining in waitSeconds downTo 1) {
                    if (!scope.isActive || skipCountdownRequested) break
                    _state.value = _state.value.copy(remainingWaitSeconds = remaining)
                    delay(1000)
                }
                skipCountdownRequested = false
                _state.value = _state.value.copy(remainingWaitSeconds = 0)
            }
        } catch (_: CancellationException) {
            // Normal cancellation on stopBatch() or reset()
        } finally {
            uvController?.turnOff()
            if (isRunning) {
                _state.value = _state.value.copy(isLightOn = false)
            }
        }
    }

    suspend fun stopBatch(): Batch {
        isRunning = false
        inspectionJob?.cancel()
        uvController?.turnOff()

        val current = _state.value
        val endTime = System.currentTimeMillis()
        val total = current.totalInspected
        val passed = current.passedCount
        val flagged = current.flaggedCount
        val defectRate = if (total > 0) flagged.toFloat() / total.toFloat() else 0f
        val status = if (defectRate <= 0.10f && total > 0) BatchStatus.APPROVED else BatchStatus.REJECTED

        val qrPayload = QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = current.batchId,
            type = "UV",
            total = total,
            pass = passed,
            defectOrFlagged = flagged,
            rate = current.flagRate,
            result = if (status == BatchStatus.APPROVED) "PASS" else "REJECT"
        )

        val batch = Batch(
            batchId = current.batchId,
            productName = "Red Chilli Sample Lot",
            inspectionCount = total,
            passCount = passed,
            failCount = flagged,
            defectRate = defectRate,
            timestamp = endTime,
            status = status,
            mode = InspectionMode.UV_AFLATOXIN,
            startTime = current.startTime,
            endTime = endTime,
            flaggedCount = flagged,
            qrPayload = qrPayload
        )

        _state.value = current.copy(
            phase = UvInspectionPhase.COMPLETED,
            isLightOn = false
        )

        return batch
    }

    fun reset() {
        isRunning = false
        inspectionJob?.cancel()
        skipCountdownRequested = false
        scope.launch { uvController?.turnOff() }
        _state.value = UvInspectionState(
            hardwareStatus = uvController?.status ?: UvHardwareStatus.NotConnected()
        )
    }
}

