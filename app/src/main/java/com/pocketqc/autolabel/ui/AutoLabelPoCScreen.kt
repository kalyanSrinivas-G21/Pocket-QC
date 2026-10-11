package com.pocketqc.autolabel.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketqc.autolabel.export.ExportResult
import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.AutoLabelQualityReport
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.ui.components.PocketQcTopBar
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.StatusWarning
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite
import java.io.File

@Composable
fun AutoLabelPoCScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AutoLabelPoCViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showPurgeConfirm by remember { mutableStateOf(false) }

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { bmp ->
                viewModel.ingestBitmap(bmp, source = "IMPORT")
            }
        }
    }

    // Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            viewModel.ingestBitmap(bmp, source = "CAMERA")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        // Top App Bar
        PocketQcTopBar(
            title = "Auto-Labeling PoC",
            subtitle = "Experimental Automated Dataset Creator & Evaluator",
            onNavigateBack = onNavigateBack,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // Mandatory Isolation & Security Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF162319))
                .border(1.dp, StatusPass, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "100% ISOLATED EXPERIMENTAL WORKSPACE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusPass
                    )
                    Text(
                        text = "Production model (biscuits_final.onnx) & inspection records are immutable and untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA5D6A7),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Notification Banner
        state.userMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1A2A3A))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, color = Color(0xFF90CAF9), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "✕",
                        color = TextGrey,
                        modifier = Modifier
                            .clickable { viewModel.dismissUserMessage() }
                            .padding(4.dp)
                    )
                }
            }
        }

        // Navigation Tabs
        ScrollableTabRow(
            selectedTabIndex = state.activeTab.ordinal,
            containerColor = ObsidianBg,
            contentColor = BrandBluePrimary,
            edgePadding = 16.dp,
            divider = {}
        ) {
            Tab(
                selected = state.activeTab == AutoLabelTab.COLLECT,
                onClick = { viewModel.selectTab(AutoLabelTab.COLLECT) },
                text = { Text("1. Ingest & Classify", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = state.activeTab == AutoLabelTab.REVIEW,
                onClick = { viewModel.selectTab(AutoLabelTab.REVIEW) },
                text = { Text("2. Review & Annotate", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = state.activeTab == AutoLabelTab.EVALUATE_EXPORT,
                onClick = { viewModel.selectTab(AutoLabelTab.EVALUATE_EXPORT) },
                text = { Text("3. Quality & YOLO Export", fontWeight = FontWeight.SemiBold) }
            )
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (state.activeTab) {
                AutoLabelTab.COLLECT -> IngestTabContent(
                    items = state.items,
                    onLaunchCamera = { cameraLauncher.launch(null) },
                    onLaunchPicker = { imagePickerLauncher.launch("image/*") },
                    onImportDemo = { viewModel.importDemoBiscuits() },
                    onSelectItem = { viewModel.selectItemForReview(it) },
                    onRequestPurge = { showPurgeConfirm = true }
                )
                AutoLabelTab.REVIEW -> ReviewTabContent(
                    selectedItem = state.selectedItem,
                    selectedBoxId = state.selectedBoxId,
                    onSelectBox = { viewModel.selectBox(it) },
                    onUpdateBoxClass = { boxId, cls -> viewModel.updateBoxClass(boxId, cls) },
                    onUpdateCoordinates = { boxId, box -> viewModel.updateBoxCoordinates(boxId, box) },
                    onAddNewBox = { box, cls -> viewModel.addNewBox(box, cls) },
                    onRemoveBox = { boxId -> viewModel.removeBox(boxId) },
                    onApprove = { viewModel.approveSelectedItem() },
                    onReject = { viewModel.rejectSelectedItem() },
                    onMarkReviewRequired = { viewModel.markSelectedReviewRequired() },
                    onDelete = { viewModel.deleteSelectedItem() },
                    onReRunPipeline = { itemId -> viewModel.runPipelineOnItem(itemId) },
                    onProceedToExport = { viewModel.selectTab(AutoLabelTab.EVALUATE_EXPORT) }
                )
                AutoLabelTab.EVALUATE_EXPORT -> QualityAndExportTabContent(
                    qualityReport = state.qualityReport,
                    exportResult = state.exportResult,
                    items = state.items,
                    onExportYolo = { viewModel.exportToYolo() },
                    onRefreshMetrics = { viewModel.refreshState() },
                    onBackToReview = { viewModel.selectTab(AutoLabelTab.REVIEW) }
                )
            }

            if (state.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandBluePrimary)
                }
            }
        }
    }

    // Purge Workspace Confirmation Dialog
    if (showPurgeConfirm) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirm = false },
            title = { Text("Purge Experimental Dataset?") },
            text = { Text("This will delete all images, annotations, and YOLO exports in files/experimental_autolabel/. Production models and inspection records will remain completely untouched.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearExperimentalWorkspace()
                        showPurgeConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusFail)
                ) {
                    Text("Purge Workspace")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirm = false }) {
                    Text("Cancel", color = TextGrey)
                }
            }
        )
    }
}

/**
 * Tab 1: Image Ingestion & Dataset Class Management
 */
@Composable
private fun IngestTabContent(
    items: List<AutoLabelImageItem>,
    onLaunchCamera: () -> Unit,
    onLaunchPicker: () -> Unit,
    onImportDemo: () -> Unit,
    onSelectItem: (AutoLabelImageItem) -> Unit,
    onRequestPurge: () -> Unit
) {
    val intactCount = items.count { it.operatorAssignedClass == AutoLabelClass.INTACT || it.annotations.any { a -> a.assignedClass == AutoLabelClass.INTACT } }
    val fractureCount = items.count { it.operatorAssignedClass == AutoLabelClass.FRACTURE || it.annotations.any { a -> a.assignedClass == AutoLabelClass.FRACTURE } }
    val brokenCount = items.count { it.operatorAssignedClass == AutoLabelClass.BROKEN || it.annotations.any { a -> a.assignedClass == AutoLabelClass.BROKEN } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Action Card: Capture or Import
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "📥 Ingest Biscuit Imagery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Acquire high-resolution biscuit photos via camera, file gallery, or factory benchmark samples.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onLaunchCamera,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📷 Photo")
                        }
                        Button(
                            onClick = onLaunchPicker,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📁 Import")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onImportDemo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🧪 Ingest Standard Factory Benchmark Set", color = BrandBluePrimary)
                    }
                }
            }
        }

        // Summary Card: Class Distributions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dataset Catalog (${items.size} Images)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "Classes: 0=Intact, 1=Frac, 2=Brk",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextGrey,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ClassCountBadge(name = "0: Intact", count = intactCount, color = StatusPass)
                        ClassCountBadge(name = "1: Fracture", count = fractureCount, color = StatusWarning)
                        ClassCountBadge(name = "2: Broken", count = brokenCount, color = StatusFail)
                    }
                }
            }
        }

        // List of items
        if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No biscuit images in workspace yet.\nCapture a photo or import demo samples above.",
                        color = TextGrey,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            items(items, key = { it.id }) { item ->
                ImageItemCard(
                    item = item,
                    onClick = { onSelectItem(item) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onRequestPurge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🗑️ Clear Experimental Workspace", color = StatusFail)
                }
            }
        }
    }
}

/**
 * Tab 2: Annotation Review & Bounding Box Editor
 */
@Composable
private fun ReviewTabContent(
    selectedItem: AutoLabelImageItem?,
    selectedBoxId: String?,
    onSelectBox: (String) -> Unit,
    onUpdateBoxClass: (String, AutoLabelClass) -> Unit,
    onUpdateCoordinates: (String, BoundingBox) -> Unit,
    onAddNewBox: (BoundingBox, AutoLabelClass) -> Unit,
    onRemoveBox: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onMarkReviewRequired: () -> Unit,
    onDelete: () -> Unit,
    onReRunPipeline: (String) -> Unit,
    onProceedToExport: () -> Unit
) {
    if (selectedItem == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select an image from the catalog to review annotations.", color = TextGrey)
        }
        return
    }

    val bitmap = remember(selectedItem.filePath) {
        val f = File(selectedItem.filePath)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Image Review Viewport Card with Proposed Box Overlay
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = selectedItem.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "Source: ${selectedItem.source} · ${selectedItem.imageWidth}x${selectedItem.imageHeight} px",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey
                            )
                        }
                        ReviewStatusBadge(status = selectedItem.reviewStatus)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Image Viewport with Bounding Box Canvas
                    if (bitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(8.dp))
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Biscuit Review Image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            // Bounding Box Render Canvas
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                for (box in selectedItem.annotations) {
                                    val isSelected = box.boxId == selectedBoxId
                                    val b = box.boundingBox

                                    val leftPx = b.left * size.width
                                    val topPx = b.top * size.height
                                    val widthPx = (b.right - b.left) * size.width
                                    val heightPx = (b.bottom - b.top) * size.height

                                    val boxColor = when (box.assignedClass) {
                                        AutoLabelClass.INTACT -> StatusPass
                                        AutoLabelClass.FRACTURE -> StatusWarning
                                        AutoLabelClass.BROKEN -> StatusFail
                                    }

                                    // Machine-proposed = dashed border; Human-verified = solid
                                    val pathEffect = if (!box.isHumanVerified) {
                                        PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    } else null

                                    drawRect(
                                        color = if (isSelected) Color.White else boxColor,
                                        topLeft = Offset(leftPx, topPx),
                                        size = Size(widthPx, heightPx),
                                        style = Stroke(
                                            width = if (isSelected) 4.dp.toPx() else 2.5.dp.toPx(),
                                            pathEffect = pathEffect
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Legend & Box Origin Indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "--- Machine Proposed",
                            color = Color(0xFF90CAF9),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "━━ Human Verified",
                            color = StatusPass,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${selectedItem.annotations.size} Boxes Detected",
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Action Toolbar: Approve / Review Required / Reject
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Decision & Quality Gating",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onApprove,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusPass),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✓ Approve")
                        }
                        Button(
                            onClick = onMarkReviewRequired,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⚠️ Review")
                        }
                        Button(
                            onClick = onReject,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusFail),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✕ Reject")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { onReRunPipeline(selectedItem.id) }) {
                            Text("🔄 Re-run Detector", color = BrandBluePrimary, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            onAddNewBox(
                                BoundingBox(0.25f, 0.25f, 0.75f, 0.75f),
                                AutoLabelClass.INTACT
                            )
                        }) {
                            Text("+ Add Box", color = Color(0xFFA78BFA), fontSize = 12.sp)
                        }
                        TextButton(onClick = onDelete) {
                            Text("Delete Sample", color = TextGrey, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Detected Bounding Boxes & Crop Previews
        item {
            Text(
                text = "Detected Biscuits (${selectedItem.annotations.size}):",
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
        }

        items(selectedItem.annotations, key = { it.boxId }) { box ->
            BoxEditorCard(
                box = box,
                isSelected = box.boxId == selectedBoxId,
                parentBitmap = bitmap,
                onSelect = { onSelectBox(box.boxId) },
                onClassChanged = { newCls -> onUpdateBoxClass(box.boxId, newCls) },
                onDeleteBox = { onRemoveBox(box.boxId) },
                onCoordinatesChanged = { newBBox -> onUpdateCoordinates(box.boxId, newBBox) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onProceedToExport,
                colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("➡️ Proceed to YOLO Export & Evaluation", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Card for an individual bounding box annotation with crop preview and editor controls.
 */
@Composable
private fun BoxEditorCard(
    box: ProposedBoxAnnotation,
    isSelected: Boolean,
    parentBitmap: Bitmap?,
    onSelect: () -> Unit,
    onClassChanged: (AutoLabelClass) -> Unit,
    onDeleteBox: () -> Unit,
    onCoordinatesChanged: (BoundingBox) -> Unit
) {
    var expandedDropdown by remember { mutableStateOf(false) }
    var showCoordEditor by remember { mutableStateOf(false) }

    // Crop Preview Extraction
    val cropBitmap = remember(parentBitmap, box.boundingBox) {
        if (parentBitmap != null) {
            val bW = parentBitmap.width
            val bH = parentBitmap.height
            val x = (box.boundingBox.left * bW).toInt().coerceIn(0, bW - 1)
            val y = (box.boundingBox.top * bH).toInt().coerceIn(0, bH - 1)
            val w = ((box.boundingBox.right - box.boundingBox.left) * bW).toInt().coerceIn(1, bW - x)
            val h = ((box.boundingBox.bottom - box.boundingBox.top) * bH).toInt().coerceIn(1, bH - y)
            try {
                Bitmap.createBitmap(parentBitmap, x, y, w, h)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF1E2838) else CardSurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isSelected) BrandBluePrimary else CardSurfaceBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Crop Preview
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(8.dp))
                ) {
                    if (cropBitmap != null) {
                        Image(
                            bitmap = cropBitmap.asImageBitmap(),
                            contentDescription = "Biscuit Crop Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Class: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey
                        )
                        Box {
                            Text(
                                text = "${box.assignedClass.displayName} (ID: ${box.assignedClass.classId}) ▼",
                                color = when (box.assignedClass) {
                                    AutoLabelClass.INTACT -> StatusPass
                                    AutoLabelClass.FRACTURE -> StatusWarning
                                    AutoLabelClass.BROKEN -> StatusFail
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF263238))
                                    .clickable { expandedDropdown = true }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )

                            DropdownMenu(
                                expanded = expandedDropdown,
                                onDismissRequest = { expandedDropdown = false }
                            ) {
                                AutoLabelClass.entries.forEach { cls ->
                                    DropdownMenuItem(
                                        text = { Text("${cls.displayName} (Class ${cls.classId})") },
                                        onClick = {
                                            onClassChanged(cls)
                                            expandedDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Score: %.1f%% · Source: %s".format(box.confidence * 100f, box.source.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey,
                        fontSize = 11.sp
                    )

                    if (box.isHumanVerified) {
                        Text(
                            text = "✓ Human Verified",
                            color = StatusPass,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "🤖 Machine Proposed (Unverified)",
                            color = Color(0xFF90CAF9),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Delete Box Action
                TextButton(onClick = onDeleteBox) {
                    Text("✕", color = StatusFail, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Quality Notes Banner
            box.reviewNotes?.let { note ->
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2E1A05))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "⚠️ $note",
                        color = Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Quick Coordinate Editor Toggle
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Box: [%.2f, %.2f, %.2f, %.2f]".format(
                        box.boundingBox.left,
                        box.boundingBox.top,
                        box.boundingBox.right,
                        box.boundingBox.bottom
                    ),
                    color = TextGrey,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(onClick = { showCoordEditor = !showCoordEditor }) {
                    Text(if (showCoordEditor) "Close" else "Nudge Box", fontSize = 11.sp, color = BrandBluePrimary)
                }
            }

            if (showCoordEditor) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Box Size / Position Nudge", style = MaterialTheme.typography.labelSmall, color = TextGrey)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val b = box.boundingBox
                                onCoordinatesChanged(
                                    BoundingBox(
                                        (b.left - 0.02f).coerceAtLeast(0f),
                                        b.top,
                                        (b.right - 0.02f).coerceAtLeast(0.05f),
                                        b.bottom
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("← Left", fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                val b = box.boundingBox
                                onCoordinatesChanged(
                                    BoundingBox(
                                        (b.left + 0.02f).coerceAtMost(0.95f),
                                        b.top,
                                        (b.right + 0.02f).coerceAtMost(1f),
                                        b.bottom
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Right →", fontSize = 10.sp)
                        }
                        Button(
                            onClick = {
                                val b = box.boundingBox
                                onCoordinatesChanged(
                                    BoundingBox(
                                        (b.left - 0.02f).coerceAtLeast(0f),
                                        (b.top - 0.02f).coerceAtLeast(0f),
                                        (b.right + 0.02f).coerceAtMost(1f),
                                        (b.bottom + 0.02f).coerceAtMost(1f)
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+ Expand", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Objective Quality Evaluation & YOLO Dataset Export
 */
@Composable
private fun QualityAndExportTabContent(
    qualityReport: AutoLabelQualityReport?,
    exportResult: ExportResult?,
    items: List<AutoLabelImageItem>,
    onExportYolo: () -> Unit,
    onRefreshMetrics: () -> Unit,
    onBackToReview: () -> Unit
) {
    val approvedCount = items.count { it.reviewStatus == AnnotationStatus.APPROVED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Export Action Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "📦 YOLOv8 Dataset Exporter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Exports verified biscuit images, normalized YOLO label files, dataset.yaml, and audit manifests to app-private storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Approved Images: $approvedCount / ${items.size}",
                            color = if (approvedCount > 0) StatusPass else StatusWarning,
                            fontWeight = FontWeight.Bold
                        )
                        if (approvedCount == 0) {
                            Text("Approve ≥ 1 image to export", color = StatusWarning, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onExportYolo,
                        enabled = approvedCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("🚀 Export Approved Samples to YOLO", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Export Outcome Card
        if (exportResult != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14241B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "✓ YOLO Dataset Export Success",
                            style = MaterialTheme.typography.titleSmall,
                            color = StatusPass,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Images: ${exportResult.exportedImageCount} | Labels: ${exportResult.exportedBoxCount} boxes",
                            color = TextWhite,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Location: ${exportResult.datasetYamlFile.parentFile?.absolutePath}",
                            color = TextGrey,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Generated files: dataset.yaml, manifest.json, quality_report.json, images/, labels/",
                            color = Color(0xFFA5D6A7),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Quality Report Card
        if (qualityReport != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📊 Pipeline Quality Metrics",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            TextButton(onClick = onRefreshMetrics) {
                                Text("Refresh", color = BrandBluePrimary, fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        MetricRow("Total Images Evaluated", "${qualityReport.totalImages}")
                        MetricRow("Images with No Detections", "${qualityReport.imagesWithNoDetections}")
                        MetricRow("Total Proposed Boxes", "${qualityReport.totalProposedBoxes}")
                        MetricRow("Approved Samples", "${qualityReport.approvedCount}")
                        MetricRow("Review Required Samples", "${qualityReport.reviewRequiredCount}")
                        MetricRow("Rejected Samples", "${qualityReport.rejectedCount}")
                        MetricRow("Invalid Boxes Detected", "${qualityReport.invalidBoxCount}")
                        MetricRow("Duplicate Boxes Filtered", "${qualityReport.duplicateBoxCount}")
                        MetricRow("Mean Processing Latency", "%.1f ms".format(qualityReport.meanProcessingTimeMs))
                        MetricRow("Human Correction Rate", "%.1f%%".format(qualityReport.humanCorrectionRate * 100f))

                        qualityReport.benchmarkEvaluation?.let { bm ->
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = CardSurfaceBorder)
                            Text(
                                text = "🎯 Benchmark Quality (Ground Truth Validated):",
                                color = Color(0xFFA78BFA),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            MetricRow("Benchmark Mean IoU", "%.3f".format(bm.meanIoU))
                            MetricRow("Localization Precision", "%.1f%%".format(bm.localizationPrecision * 100f))
                            MetricRow("Localization Recall", "%.1f%%".format(bm.localizationRecall * 100f))
                            MetricRow("Classification Agreement", "%.1f%%".format(bm.classificationAgreementRate * 100f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextGrey, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextWhite, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ClassCountBadge(name: String, count: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E2430))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = TextGrey, fontSize = 10.sp)
            Text("$count", color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ReviewStatusBadge(status: AnnotationStatus) {
    val (color, text) = when (status) {
        AnnotationStatus.APPROVED -> Pair(StatusPass, "APPROVED")
        AnnotationStatus.REVIEW_REQUIRED -> Pair(Color(0xFFD97706), "REVIEW REQ")
        AnnotationStatus.REJECTED -> Pair(StatusFail, "REJECTED")
        AnnotationStatus.PENDING -> Pair(Color(0xFF90CAF9), "PENDING")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ImageItemCard(
    item: AutoLabelImageItem,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            val bmp = remember(item.filePath) {
                val f = File(item.filePath)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }

            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextWhite,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.annotations.size} boxes · ${item.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey,
                    fontSize = 11.sp
                )
            }

            ReviewStatusBadge(status = item.reviewStatus)
        }
    }
}
