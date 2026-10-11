package com.pocketqc.autolabel.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketqc.autolabel.dataset.AutoLabelDatasetManager
import com.pocketqc.autolabel.engine.AutoLabelPipeline
import com.pocketqc.autolabel.engine.ProductionAssetBiscuitDetector
import com.pocketqc.autolabel.evaluation.AutoLabelEvaluator
import com.pocketqc.autolabel.export.ExportResult
import com.pocketqc.autolabel.export.YoloDatasetExporter
import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.AutoLabelQualityReport
import com.pocketqc.autolabel.model.ProposalSource
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.autolabel.workspace.AutoLabelWorkspace
import com.pocketqc.core.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class AutoLabelTab {
    COLLECT,
    REVIEW,
    EVALUATE_EXPORT
}

data class AutoLabelUiState(
    val activeTab: AutoLabelTab = AutoLabelTab.COLLECT,
    val items: List<AutoLabelImageItem> = emptyList(),
    val selectedItem: AutoLabelImageItem? = null,
    val selectedBoxId: String? = null,
    val isProcessing: Boolean = false,
    val userMessage: String? = null,
    val qualityReport: AutoLabelQualityReport? = null,
    val exportResult: ExportResult? = null,
    val isolationVerified: Boolean = true
)

class AutoLabelPoCViewModel(application: Application) : AndroidViewModel(application) {

    private val workspace = AutoLabelWorkspace(application)
    private val datasetManager = AutoLabelDatasetManager(application, workspace)
    private val detector = ProductionAssetBiscuitDetector(application)
    private val pipeline = AutoLabelPipeline(detector)

    private val _uiState = MutableStateFlow(AutoLabelUiState())
    val uiState: StateFlow<AutoLabelUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    fun selectTab(tab: AutoLabelTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun refreshState() {
        val loaded = datasetManager.loadItems()
        val isolationOk = workspace.verifyIsolation()
        val report = AutoLabelEvaluator.evaluate(loaded)
        _uiState.update { current ->
            val updatedSelected = current.selectedItem?.let { sel ->
                loaded.firstOrNull { it.id == sel.id }
            } ?: loaded.firstOrNull()

            current.copy(
                items = loaded,
                selectedItem = updatedSelected,
                isolationVerified = isolationOk,
                qualityReport = report
            )
        }
    }

    fun selectItemForReview(item: AutoLabelImageItem) {
        _uiState.update {
            it.copy(
                selectedItem = item,
                selectedBoxId = item.annotations.firstOrNull()?.boxId,
                activeTab = AutoLabelTab.REVIEW
            )
        }
    }

    fun selectBox(boxId: String?) {
        _uiState.update { it.copy(selectedBoxId = boxId) }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun ingestBitmap(bitmap: Bitmap, source: String = "CAMERA", operatorClass: AutoLabelClass? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            val res = withContext(Dispatchers.IO) {
                datasetManager.ingestBitmap(bitmap, source, operatorClass)
            }
            res.onSuccess { item ->
                // Automatically run localization pipeline on new sample
                val processRes = withContext(Dispatchers.Default) {
                    pipeline.processImage(item, bitmap)
                }
                withContext(Dispatchers.IO) {
                    datasetManager.updateItem(processRes.updatedItem)
                }
                _uiState.update {
                    it.copy(
                        userMessage = "Image ingested & analyzed (${processRes.validBoxes} boxes proposed)"
                    )
                }
                refreshState()
                selectItemForReview(processRes.updatedItem)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(userMessage = "Ingestion failed: ${err.message}")
                }
            }
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun importDemoBiscuits() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            val imported = withContext(Dispatchers.IO) {
                datasetManager.importStandardDemoBiscuits()
            }
            _uiState.update {
                it.copy(userMessage = "Imported ${imported.size} standard benchmark biscuit samples")
            }
            refreshState()
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun runPipelineOnItem(itemId: String) {
        viewModelScope.launch {
            val item = _uiState.value.items.firstOrNull { it.id == itemId } ?: return@launch
            val file = File(item.filePath)
            if (!file.exists()) return@launch

            _uiState.update { it.copy(isProcessing = true) }
            val bmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.absolutePath)
            }
            if (bmp != null) {
                val res = withContext(Dispatchers.Default) {
                    pipeline.processImage(item, bmp)
                }
                bmp.recycle()
                withContext(Dispatchers.IO) {
                    datasetManager.updateItem(res.updatedItem)
                }
                _uiState.update {
                    it.copy(userMessage = "Pipeline re-evaluated: ${res.validBoxes} boxes, status: ${res.updatedItem.reviewStatus}")
                }
                refreshState()
            }
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun updateBoxClass(boxId: String, newClass: AutoLabelClass) {
        val currentItem = _uiState.value.selectedItem ?: return
        val updatedBoxes = currentItem.annotations.map { box ->
            if (box.boxId == boxId) {
                box.copy(
                    assignedClass = newClass,
                    isHumanVerified = true,
                    source = ProposalSource.MANUAL_CORRECTION
                )
            } else {
                box
            }
        }
        val updatedItem = currentItem.copy(
            annotations = updatedBoxes,
            hasHumanCorrections = true
        )
        datasetManager.updateItem(updatedItem)
        refreshState()
    }

    fun updateBoxCoordinates(boxId: String, newBoundingBox: BoundingBox) {
        val currentItem = _uiState.value.selectedItem ?: return
        val updatedBoxes = currentItem.annotations.map { box ->
            if (box.boxId == boxId) {
                box.copy(
                    boundingBox = newBoundingBox,
                    isHumanVerified = true,
                    source = ProposalSource.MANUAL_CORRECTION
                )
            } else {
                box
            }
        }
        val updatedItem = currentItem.copy(
            annotations = updatedBoxes,
            hasHumanCorrections = true
        )
        datasetManager.updateItem(updatedItem)
        refreshState()
    }

    fun addNewBox(box: BoundingBox, assignedClass: AutoLabelClass = AutoLabelClass.INTACT) {
        val currentItem = _uiState.value.selectedItem ?: return
        val newBox = ProposedBoxAnnotation(
            boxId = UUID.randomUUID().toString(),
            boundingBox = box,
            predictedClass = assignedClass,
            confidence = 1.0f,
            assignedClass = assignedClass,
            isHumanVerified = true,
            source = ProposalSource.MANUAL_ADDITION
        )
        val updatedItem = currentItem.copy(
            annotations = currentItem.annotations + newBox,
            hasHumanCorrections = true
        )
        datasetManager.updateItem(updatedItem)
        refreshState()
        selectBox(newBox.boxId)
    }

    fun removeBox(boxId: String) {
        val currentItem = _uiState.value.selectedItem ?: return
        val updatedBoxes = currentItem.annotations.filter { it.boxId != boxId }
        val updatedItem = currentItem.copy(
            annotations = updatedBoxes,
            hasHumanCorrections = true
        )
        datasetManager.updateItem(updatedItem)
        refreshState()
    }

    fun approveSelectedItem() {
        val currentItem = _uiState.value.selectedItem ?: return
        // Mark all boxes human-verified upon human approval
        val verifiedBoxes = currentItem.annotations.map { it.copy(isHumanVerified = true) }
        val updatedItem = currentItem.copy(
            annotations = verifiedBoxes,
            reviewStatus = AnnotationStatus.APPROVED,
            rejectionReason = null
        )
        datasetManager.updateItem(updatedItem)
        _uiState.update { it.copy(userMessage = "Approved image with ${verifiedBoxes.size} boxes for YOLO export") }
        refreshState()
    }

    fun rejectSelectedItem(reason: String = "Rejected by operator") {
        val currentItem = _uiState.value.selectedItem ?: return
        val updatedItem = currentItem.copy(
            reviewStatus = AnnotationStatus.REJECTED,
            rejectionReason = reason
        )
        datasetManager.updateItem(updatedItem)
        _uiState.update { it.copy(userMessage = "Sample marked REJECTED") }
        refreshState()
    }

    fun markSelectedReviewRequired() {
        val currentItem = _uiState.value.selectedItem ?: return
        val updatedItem = currentItem.copy(
            reviewStatus = AnnotationStatus.REVIEW_REQUIRED
        )
        datasetManager.updateItem(updatedItem)
        refreshState()
    }

    fun deleteSelectedItem() {
        val currentItem = _uiState.value.selectedItem ?: return
        datasetManager.deleteItem(currentItem.id)
        _uiState.update { it.copy(userMessage = "Sample deleted from experimental workspace") }
        refreshState()
    }

    fun exportToYolo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            val currentItems = datasetManager.loadItems()
            val report = AutoLabelEvaluator.evaluate(currentItems)
            val exportDir = workspace.yoloExportDir

            val result = withContext(Dispatchers.IO) {
                YoloDatasetExporter.exportToYolo(currentItems, exportDir, report)
            }
            _uiState.update {
                it.copy(
                    exportResult = result,
                    userMessage = "Exported ${result.exportedImageCount} approved images (${result.exportedBoxCount} boxes) to YOLO format"
                )
            }
            refreshState()
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    fun clearExperimentalWorkspace() {
        workspace.clearWorkspace()
        _uiState.update {
            it.copy(
                userMessage = "Experimental workspace purged. Production assets and records remain unaffected.",
                selectedItem = null,
                exportResult = null
            )
        }
        refreshState()
    }
}
