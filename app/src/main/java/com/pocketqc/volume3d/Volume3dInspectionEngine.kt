package com.pocketqc.volume3d

import android.graphics.Bitmap
import com.pocketqc.batch.BatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.utils.BatchIdGenerator
import com.pocketqc.core.utils.QrCodeGenerator
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
 * State machine coordinator for calibrated container fill-level inspection.
 * Supports both continuous automated cycle and on-demand immediate scanning from live camera frames.
 */
class Volume3dInspectionEngine(
    private val volumeAnalyzer: VolumeAnalyzer = RealGeometricFillAnalyzer(),
    private val batchManager: BatchManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    interBottleWaitSeconds: Int = 3
) {
    var interBottleWaitSeconds: Int = interBottleWaitSeconds
        set(value) {
            field = value.coerceIn(1, 10)
        }

    private val _state = MutableStateFlow(Volume3dInspectionState())
    val state: StateFlow<Volume3dInspectionState> = _state.asStateFlow()

    private var inspectionJob: Job? = null
    private var frameProvider: (() -> Bitmap?)? = null

    init {
        // Sync analyzer profile if using RealGeometricFillAnalyzer
        if (volumeAnalyzer is RealGeometricFillAnalyzer) {
            volumeAnalyzer.profile = _state.value.selectedProfile
            volumeAnalyzer.calibrationRoi = _state.value.calibrationRoi
        }
    }

    fun setFrameProvider(provider: (() -> Bitmap?)?) {
        this.frameProvider = provider
    }

    fun selectProfile(profile: ContainerProfile) {
        if (_state.value.phase == Volume3dPhase.IDLE) {
            _state.value = _state.value.copy(
                selectedProfile = profile,
                requiredFillPercent = profile.targetFillPercent,
                tolerancePercent = profile.tolerancePercent
            )
            if (volumeAnalyzer is RealGeometricFillAnalyzer) {
                volumeAnalyzer.profile = profile
            }
        }
    }

    fun setRequiredFillLevel(level: Float) {
        if (_state.value.phase == Volume3dPhase.IDLE) {
            val clamped = level.coerceIn(10f, 100f)
            _state.value = _state.value.copy(requiredFillPercent = clamped)
            if (volumeAnalyzer is RealGeometricFillAnalyzer) {
                volumeAnalyzer.profile = volumeAnalyzer.profile.copy(targetFillPercent = clamped)
            }
        }
    }

    fun setTolerance(tolerance: Float) {
        if (_state.value.phase == Volume3dPhase.IDLE) {
            val clamped = tolerance.coerceIn(0.5f, 15.0f)
            _state.value = _state.value.copy(tolerancePercent = clamped)
            if (volumeAnalyzer is RealGeometricFillAnalyzer) {
                volumeAnalyzer.profile = volumeAnalyzer.profile.copy(tolerancePercent = clamped)
            }
        }
    }

    fun setCalibrationRoi(roi: CalibrationRoi) {
        _state.value = _state.value.copy(calibrationRoi = roi)
        if (volumeAnalyzer is RealGeometricFillAnalyzer) {
            volumeAnalyzer.calibrationRoi = roi
        }
    }

    fun setContainerWidthPercent(widthPercent: Float) {
        if (_state.value.phase == Volume3dPhase.IDLE) {
            val clamped = widthPercent.coerceIn(20f, 85f)
            val left = (0.5f - (clamped / 200f)).coerceIn(0.05f, 0.45f)
            val right = (0.5f + (clamped / 200f)).coerceIn(0.55f, 0.95f)
            val updatedProfile = _state.value.selectedProfile.copy(containerWidthPercent = clamped)
            val updatedRoi = _state.value.calibrationRoi.copy(leftNormalizedX = left, rightNormalizedX = right)
            _state.value = _state.value.copy(
                selectedProfile = updatedProfile,
                calibrationRoi = updatedRoi
            )
            if (volumeAnalyzer is RealGeometricFillAnalyzer) {
                volumeAnalyzer.profile = updatedProfile
                volumeAnalyzer.calibrationRoi = updatedRoi
            }
        }
    }

    fun startBatch(
        requiredFillPercent: Float = _state.value.requiredFillPercent,
        productName: String = _state.value.selectedProfile.name
    ): String {
        inspectionJob?.cancel()

        val batchId = BatchIdGenerator.generate(prefix = "PQ")
        val startTime = System.currentTimeMillis()

        _state.value = _state.value.copy(
            phase = Volume3dPhase.STARTING,
            bottleIndex = 0,
            requiredFillPercent = requiredFillPercent,
            totalInspected = 0,
            passedCount = 0,
            failedCount = 0,
            inconclusiveCount = 0,
            records = emptyList(),
            batchId = batchId,
            startTime = startTime,
            latestResult = null
        )

        inspectionJob = scope.launch {
            runBottleCycle()
        }

        return batchId
    }

    private suspend fun getFrame(): Bitmap? {
        val provider = frameProvider ?: return null
        return try {
            withContext(Dispatchers.Main) {
                provider()
            }
        } catch (_: Exception) {
            try {
                provider()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Triggers an immediate scan using the provided bitmap (or the active frameProvider).
     * If a batch is running, records the measurement.
     * If idle, performs an alignment preview inspection without incrementing batch totals.
     */
    suspend fun triggerImmediateScan(bitmap: Bitmap? = null): VolumeAnalysisResult {
        val frameToInspect = bitmap ?: getFrame()
        val result = volumeAnalyzer.analyze(
            sideFrame = frameToInspect,
            requiredFillPercent = _state.value.requiredFillPercent
        )

        val current = _state.value
        if (current.phase != Volume3dPhase.IDLE && current.phase != Volume3dPhase.COMPLETED) {
            val currentBottleNumber = current.bottleIndex + 1
            val record = BottleRecord(
                bottleNumber = currentBottleNumber,
                requiredFillPercent = result.requiredFillPercent,
                detectedFillPercent = result.detectedFillPercent,
                differencePercent = result.differencePercent,
                isPass = result.isPass,
                timestamp = System.currentTimeMillis(),
                status = result.status,
                measuredHeightMm = result.measuredHeightMm,
                estimatedVolumeMl = result.estimatedVolumeMl,
                diagnostics = result.diagnostics
            )

            val newTotal = current.totalInspected + 1
            val newPassed = if (result.status == MeasurementStatus.PASS) current.passedCount + 1 else current.passedCount
            val newFailed = if (result.status == MeasurementStatus.OUT_OF_TOLERANCE) current.failedCount + 1 else current.failedCount
            val newInconclusive = if (result.status == MeasurementStatus.INCONCLUSIVE || result.status == MeasurementStatus.ERROR) {
                current.inconclusiveCount + 1
            } else {
                current.inconclusiveCount
            }
            val newRecords = current.records + record

            _state.value = current.copy(
                phase = Volume3dPhase.BOTTLE_RESULT,
                bottleIndex = currentBottleNumber,
                latestResult = result,
                totalInspected = newTotal,
                passedCount = newPassed,
                failedCount = newFailed,
                inconclusiveCount = newInconclusive,
                records = newRecords
            )
        } else {
            // Preview alignment mode
            _state.value = current.copy(latestResult = result)
        }

        return result
    }

    private suspend fun runBottleCycle() {
        try {
            while (scope.isActive) {
                val currentBottleNumber = _state.value.bottleIndex + 1

                _state.value = _state.value.copy(
                    phase = Volume3dPhase.ANALYZING_BOTTLE,
                    bottleIndex = currentBottleNumber
                )

                val frameToInspect = getFrame()
                val result = volumeAnalyzer.analyze(
                    sideFrame = frameToInspect,
                    requiredFillPercent = _state.value.requiredFillPercent
                )

                val record = BottleRecord(
                    bottleNumber = currentBottleNumber,
                    requiredFillPercent = result.requiredFillPercent,
                    detectedFillPercent = result.detectedFillPercent,
                    differencePercent = result.differencePercent,
                    isPass = result.isPass,
                    timestamp = System.currentTimeMillis(),
                    status = result.status,
                    measuredHeightMm = result.measuredHeightMm,
                    estimatedVolumeMl = result.estimatedVolumeMl,
                    diagnostics = result.diagnostics
                )

                val current = _state.value
                val newTotal = current.totalInspected + 1
                val newPassed = if (result.status == MeasurementStatus.PASS) current.passedCount + 1 else current.passedCount
                val newFailed = if (result.status == MeasurementStatus.OUT_OF_TOLERANCE) current.failedCount + 1 else current.failedCount
                val newInconclusive = if (result.status == MeasurementStatus.INCONCLUSIVE || result.status == MeasurementStatus.ERROR) {
                    current.inconclusiveCount + 1
                } else {
                    current.inconclusiveCount
                }
                val newRecords = current.records + record

                _state.value = current.copy(
                    phase = Volume3dPhase.BOTTLE_RESULT,
                    latestResult = result,
                    totalInspected = newTotal,
                    passedCount = newPassed,
                    failedCount = newFailed,
                    inconclusiveCount = newInconclusive,
                    records = newRecords
                )

                delay(1200)

                // Inter-bottle countdown for conveyor positioning
                _state.value = _state.value.copy(phase = Volume3dPhase.WAITING_NEXT_BOTTLE)
                for (sec in interBottleWaitSeconds downTo 1) {
                    _state.value = _state.value.copy(remainingWaitSeconds = sec)
                    delay(1000)
                }
                _state.value = _state.value.copy(remainingWaitSeconds = 0)
            }
        } catch (_: CancellationException) {
            // Normal cancellation on stopBatch
        }
    }

    suspend fun stopBatch(): Batch {
        inspectionJob?.cancel()

        val current = _state.value
        val endTime = System.currentTimeMillis()
        val total = current.totalInspected
        val passed = current.passedCount
        // Inconclusive measurements are counted alongside defects as unapproved
        val failed = current.failedCount + current.inconclusiveCount
        val defectRate = if (total > 0) failed.toFloat() / total.toFloat() else 0f
        val status = if (defectRate <= 0.08f && total > 0) BatchStatus.APPROVED else BatchStatus.REJECTED

        val avgFill = current.averageDetectedFillPercent
        val qrPayload = QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = current.batchId,
            type = "3D",
            total = total,
            pass = passed,
            defectOrFlagged = failed,
            rate = if (total > 0) (failed.toFloat() / total.toFloat()) * 100f else 0f,
            result = if (status == BatchStatus.APPROVED) "PASS" else "REJECT"
        )

        val batch = Batch(
            batchId = current.batchId,
            productName = current.selectedProfile.name,
            inspectionCount = total,
            passCount = passed,
            failCount = failed,
            defectRate = defectRate,
            timestamp = endTime,
            status = status,
            mode = InspectionMode.VOLUME_CHECK_3D,
            startTime = current.startTime,
            endTime = endTime,
            requiredFillPercent = current.requiredFillPercent,
            averageDetectedFillPercent = avgFill,
            qrPayload = qrPayload
        )

        _state.value = current.copy(phase = Volume3dPhase.COMPLETED)
        batchManager.saveBatch(batch)
        return batch
    }

    fun reset() {
        inspectionJob?.cancel()
        _state.value = Volume3dInspectionState(
            selectedProfile = _state.value.selectedProfile,
            calibrationRoi = _state.value.calibrationRoi,
            requiredFillPercent = _state.value.selectedProfile.targetFillPercent,
            tolerancePercent = _state.value.selectedProfile.tolerancePercent
        )
    }
}
