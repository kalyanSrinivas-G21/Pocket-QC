package com.pocketqc.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.lab.dataset.ExperimentalDatasetManager
import com.pocketqc.lab.evaluation.ExperimentalEvaluationEngine
import com.pocketqc.lab.model.BiscuitLabClass
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.ExperimentalEvaluationReport
import com.pocketqc.lab.model.ExperimentalModelMetadata
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.lab.model.IsolationAuditResult
import com.pocketqc.lab.model.TrainingConfig
import com.pocketqc.lab.model.TrainingProgress
import com.pocketqc.lab.registry.ExperimentalModelRegistry
import com.pocketqc.lab.training.ExperimentalModel
import com.pocketqc.lab.training.OnDeviceTrainingEngine
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class ExperimentalLabTab {
    DATASET,
    TRAINING,
    EVALUATION,
    ISOLATION_AUDIT
}

data class LiveTestResult(
    val bitmap: Bitmap,
    val predictedClass: BiscuitLabClass,
    val confidence: Float,
    val probabilities: FloatArray,
    val timestamp: Long
)

data class ExperimentalLabUiState(
    val selectedTab: ExperimentalLabTab = ExperimentalLabTab.DATASET,
    val samples: List<ExperimentalSample> = emptyList(),
    val availableModels: List<ExperimentalModelMetadata> = emptyList(),
    val selectedModel: ExperimentalModel? = null,
    val isTraining: Boolean = false,
    val trainingProgress: TrainingProgress? = null,
    val trainingLogs: List<String> = emptyList(),
    val trainingConfig: TrainingConfig = TrainingConfig(),
    val evaluationReport: ExperimentalEvaluationReport? = null,
    val isEvaluating: Boolean = false,
    val auditResult: IsolationAuditResult? = null,
    val userMessage: String? = null,
    val capturedImageForLabeling: Bitmap? = null,
    val liveTestResult: LiveTestResult? = null
)

class ExperimentalLabViewModel(application: Application) : AndroidViewModel(application) {

    private val workspace = ExperimentalWorkspace(application)
    private val datasetManager = ExperimentalDatasetManager(application, workspace)
    private val trainingEngine = OnDeviceTrainingEngine(workspace)
    private val evaluationEngine = ExperimentalEvaluationEngine(workspace)
    private val registry = ExperimentalModelRegistry(workspace)

    private val _uiState = MutableStateFlow(ExperimentalLabUiState())
    val uiState: StateFlow<ExperimentalLabUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        runIsolationAudit()
    }

    fun selectTab(tab: ExperimentalLabTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refreshState() {
        val samples = datasetManager.loadSamples()
        val models = registry.listExperimentalModels()
        _uiState.update { current ->
            current.copy(
                samples = samples,
                availableModels = models
            )
        }
    }

    fun onImageCapturedForLabeling(bitmap: Bitmap) {
        _uiState.update { it.copy(capturedImageForLabeling = bitmap) }
    }

    fun dismissCapturedImageForLabeling() {
        _uiState.update { it.copy(capturedImageForLabeling = null) }
    }

    fun confirmCapturedSample(assignedClass: BiscuitLabClass, customBox: BoundingBox? = null) {
        val bitmap = _uiState.value.capturedImageForLabeling ?: return
        viewModelScope.launch {
            val added = datasetManager.addSample(bitmap, assignedClass, customBox, isVerified = true)
            val updated = datasetManager.loadSamples()
            _uiState.update {
                it.copy(
                    samples = updated,
                    capturedImageForLabeling = null,
                    userMessage = "Saved Sample #${updated.size}: ${assignedClass.label} to isolated dataset."
                )
            }
        }
    }

    fun deleteSample(sampleId: String) {
        viewModelScope.launch {
            val updated = datasetManager.deleteSample(sampleId)
            _uiState.update {
                it.copy(
                    samples = updated,
                    userMessage = "Sample removed."
                )
            }
        }
    }

    fun importStandardSamples() {
        viewModelScope.launch {
            val samples = datasetManager.importStandardBiscuitSamples()
            _uiState.update {
                it.copy(
                    samples = samples,
                    userMessage = "Imported ${samples.size} standard biscuit samples with Train/Val/Test splits."
                )
            }
        }
    }

    fun addCapturedSample(bitmap: Bitmap, assignedClass: BiscuitLabClass, customBox: BoundingBox? = null) {
        viewModelScope.launch {
            val added = datasetManager.addSample(bitmap, assignedClass, customBox, isVerified = true)
            val updated = datasetManager.loadSamples()
            _uiState.update {
                it.copy(
                    samples = updated,
                    userMessage = "Added biscuit sample ${added.id} (${assignedClass.label}) to dataset."
                )
            }
        }
    }

    fun updateSample(sampleId: String, newClass: BiscuitLabClass, newBox: BoundingBox) {
        viewModelScope.launch {
            val updated = datasetManager.updateSample(sampleId, newClass, newBox, isVerified = true)
            _uiState.update {
                it.copy(
                    samples = updated,
                    userMessage = "Updated annotation for sample $sampleId."
                )
            }
        }
    }

    fun updateTrainingConfig(config: TrainingConfig) {
        _uiState.update { it.copy(trainingConfig = config) }
    }

    fun startTraining() {
        val samples = _uiState.value.samples
        if (samples.isEmpty()) {
            _uiState.update { it.copy(userMessage = "Please click/capture biscuit images and assign classes first.") }
            return
        }

        if (samples.size < 2) {
            _uiState.update { it.copy(userMessage = "Please capture at least 2 biscuit images (e.g. Intact and Broken) before training.") }
            return
        }

        val config = _uiState.value.trainingConfig
        _uiState.update {
            it.copy(
                isTraining = true,
                trainingProgress = null,
                trainingLogs = listOf("Initiating on-device training session (${config.epochs} epochs)...")
            )
        }

        viewModelScope.launch {
            val result = trainingEngine.train(samples, config) { progress ->
                val logEntry = "Epoch ${progress.currentEpoch}/${progress.totalEpochs} | Loss: ${"%.4f".format(progress.trainLoss)} | Val Acc: ${"%.1f%%".format(progress.valAccuracy * 100)}"
                _uiState.update { current ->
                    current.copy(
                        trainingProgress = progress,
                        trainingLogs = current.trainingLogs + logEntry
                    )
                }
            }

            result.fold(
                onSuccess = { model ->
                    val updatedModels = registry.listExperimentalModels()
                    _uiState.update {
                        it.copy(
                            isTraining = false,
                            selectedModel = model,
                            availableModels = updatedModels,
                            userMessage = "Training complete! Model ${model.metadata.modelId} saved. Now test by clicking an image in Evaluation!",
                            selectedTab = ExperimentalLabTab.EVALUATION
                        )
                    }
                    runIsolationAudit()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isTraining = false,
                            userMessage = "Training failed: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun classifyLiveImage(bitmap: Bitmap) {
        val activeModel = _uiState.value.selectedModel ?: run {
            val latestMeta = _uiState.value.availableModels.firstOrNull()
            if (latestMeta != null) registry.loadExperimentalModel(latestMeta.modelId) else null
        }

        if (activeModel == null) {
            _uiState.update { it.copy(userMessage = "No experimental model available. Please train a model first in Tab 2 (Training).") }
            return
        }

        viewModelScope.launch {
            val result = activeModel.predictImageRegion(bitmap, BoundingBox(0.15f, 0.15f, 0.85f, 0.85f))
            _uiState.update {
                it.copy(
                    selectedModel = activeModel,
                    liveTestResult = LiveTestResult(
                        bitmap = bitmap,
                        predictedClass = result.predictedClass,
                        confidence = result.confidence,
                        probabilities = result.probabilities,
                        timestamp = System.currentTimeMillis()
                    ),
                    userMessage = "Classified: ${result.predictedClass.label.uppercase(Locale.US)} (${"%.1f%%".format(result.confidence * 100)})"
                )
            }
        }
    }

    fun clearLiveTestResult() {
        _uiState.update { it.copy(liveTestResult = null) }
    }

    fun runEvaluation(targetSplit: DatasetSplit = DatasetSplit.TEST) {
        val activeModel = _uiState.value.selectedModel ?: run {
            val latestMeta = _uiState.value.availableModels.firstOrNull()
            if (latestMeta != null) registry.loadExperimentalModel(latestMeta.modelId) else null
        }

        if (activeModel == null) {
            _uiState.update { it.copy(userMessage = "No experimental model found. Train a model first.") }
            return
        }

        _uiState.update { it.copy(isEvaluating = true) }

        viewModelScope.launch {
            val result = evaluationEngine.evaluate(activeModel, _uiState.value.samples, targetSplit)
            result.fold(
                onSuccess = { report ->
                    _uiState.update {
                        it.copy(
                            isEvaluating = false,
                            evaluationReport = report,
                            selectedModel = activeModel,
                            userMessage = "Evaluation complete on ${report.testSampleCount} held-out test samples. Accuracy: ${"%.1f%%".format(report.overallAccuracy * 100)}."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isEvaluating = false,
                            userMessage = "Evaluation failed: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun runIsolationAudit() {
        val audit = workspace.performIsolationAudit()
        _uiState.update { it.copy(auditResult = audit) }
    }

    fun resetWorkspace() {
        workspace.clearWorkspace()
        _uiState.update {
            it.copy(
                samples = emptyList(),
                availableModels = emptyList(),
                selectedModel = null,
                evaluationReport = null,
                trainingProgress = null,
                trainingLogs = emptyList(),
                capturedImageForLabeling = null,
                liveTestResult = null,
                userMessage = "Experimental workspace reset. Production model remains completely untouched."
            )
        }
        runIsolationAudit()
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
