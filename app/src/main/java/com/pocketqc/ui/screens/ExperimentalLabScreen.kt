package com.pocketqc.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.lab.model.BiscuitLabClass
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.StatusWarning
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite
import com.pocketqc.ui.viewmodel.ExperimentalLabTab
import com.pocketqc.ui.viewmodel.ExperimentalLabViewModel
import com.pocketqc.ui.viewmodel.LiveTestResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExperimentalLabScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExperimentalLabViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var editingSample by remember { mutableStateOf<ExperimentalSample?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        // App Bar
        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Experimental Model Lab",
            subtitle = "On-Device AI Active Learning Demo (Isolated)",
            onNavigateBack = onNavigateBack,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // Persistent Warning Banner (Mandatory Isolation Notice)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2D1800))
                .border(1.dp, StatusWarning, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "DEMONSTRATION & EXPERIMENTATION ONLY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusWarning
                    )
                    Text(
                        text = "Experimental model only. The production 2D inspection model remains unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD599)
                    )
                }
            }
        }

        // User Message Banner
        state.userMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1B2B3A))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90CAF9))
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
            selectedTabIndex = state.selectedTab.ordinal,
            containerColor = ObsidianBg,
            contentColor = BrandBluePrimary,
            edgePadding = 16.dp,
            divider = {}
        ) {
            Tab(
                selected = state.selectedTab == ExperimentalLabTab.DATASET,
                onClick = { viewModel.selectTab(ExperimentalLabTab.DATASET) },
                text = { Text("1. Capture & Label", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = state.selectedTab == ExperimentalLabTab.TRAINING,
                onClick = { viewModel.selectTab(ExperimentalLabTab.TRAINING) },
                text = { Text("2. Train Model", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = state.selectedTab == ExperimentalLabTab.EVALUATION,
                onClick = { viewModel.selectTab(ExperimentalLabTab.EVALUATION) },
                text = { Text("3. Test & Classify", fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = state.selectedTab == ExperimentalLabTab.ISOLATION_AUDIT,
                onClick = { viewModel.selectTab(ExperimentalLabTab.ISOLATION_AUDIT) },
                text = { Text("4. Security Audit", fontWeight = FontWeight.SemiBold) }
            )
        }

        // Tab Content
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (state.selectedTab) {
                ExperimentalLabTab.DATASET -> CaptureAndLabelStudioTab(
                    samples = state.samples,
                    onImageCaptured = { bitmap -> viewModel.onImageCapturedForLabeling(bitmap) },
                    onDeleteSample = { sampleId -> viewModel.deleteSample(sampleId) },
                    onEditSample = { editingSample = it },
                    onImportStandardSamples = { viewModel.importStandardSamples() },
                    onProceedToTraining = { viewModel.selectTab(ExperimentalLabTab.TRAINING) }
                )
                ExperimentalLabTab.TRAINING -> TrainingStudioTab(
                    state = state,
                    onStartTraining = { viewModel.startTraining() },
                    onUpdateEpochs = { epochs ->
                        viewModel.updateTrainingConfig(state.trainingConfig.copy(epochs = epochs))
                    },
                    onProceedToTesting = { viewModel.selectTab(ExperimentalLabTab.EVALUATION) },
                    onBackToCapture = { viewModel.selectTab(ExperimentalLabTab.DATASET) }
                )
                ExperimentalLabTab.EVALUATION -> TestAndClassifyStudioTab(
                    state = state,
                    onClassifyImage = { bitmap -> viewModel.classifyLiveImage(bitmap) },
                    onClearResult = { viewModel.clearLiveTestResult() },
                    onRunBatchEvaluation = { viewModel.runEvaluation(DatasetSplit.TEST) },
                    onGoToTraining = { viewModel.selectTab(ExperimentalLabTab.TRAINING) }
                )
                ExperimentalLabTab.ISOLATION_AUDIT -> IsolationAuditTab(
                    state = state,
                    onRunAudit = { viewModel.runIsolationAudit() },
                    onRequestReset = { showResetConfirm = true }
                )
            }
        }
    }

    // Modal Sheet: Assign Class to Captured Image
    state.capturedImageForLabeling?.let { capturedBmp ->
        AssignClassDialog(
            bitmap = capturedBmp,
            onDismiss = { viewModel.dismissCapturedImageForLabeling() },
            onAssignClass = { selectedClass ->
                viewModel.confirmCapturedSample(selectedClass)
            }
        )
    }

    // Edit Annotation Dialog
    editingSample?.let { sample ->
        EditAnnotationDialog(
            sample = sample,
            onDismiss = { editingSample = null },
            onSave = { newClass, newBox ->
                viewModel.updateSample(sample.id, newClass, newBox)
                editingSample = null
            }
        )
    }

    // Reset Workspace Confirmation Dialog
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Experimental Workspace?") },
            text = {
                Text("This deletes only experimental training data, checkpoints, and reports in files/experimental_lab/. Production inspection assets and models are completely untouched.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetWorkspace()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusFail)
                ) {
                    Text("Reset Experimental Data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = TextGrey)
                }
            }
        )
    }
}

/**
 * Tab 1: Live Camera Capture + Class Labeling Workflow
 */
@Composable
private fun CaptureAndLabelStudioTab(
    samples: List<ExperimentalSample>,
    onImageCaptured: (Bitmap) -> Unit,
    onDeleteSample: (String) -> Unit,
    onEditSample: (ExperimentalSample) -> Unit,
    onImportStandardSamples: () -> Unit,
    onProceedToTraining: () -> Unit
) {
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val intactCount = samples.count { it.assignedClass == BiscuitLabClass.INTACT }
    val fractureCount = samples.count { it.assignedClass == BiscuitLabClass.FRACTURE }
    val brokenCount = samples.count { it.assignedClass == BiscuitLabClass.BROKEN }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Camera Viewfinder Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📸 Step 1: Click Biscuit Photo",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Live Viewfinder",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusPass
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Position biscuit in center, click photo, and assign defect category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Viewfinder Viewport
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF333842), RoundedCornerShape(8.dp))
                    ) {
                        CameraViewfinder(
                            modifier = Modifier.fillMaxSize(),
                            onPreviewViewReady = { previewViewRef = it }
                        )

                        // Reticle Overlay
                        Box(
                            modifier = Modifier
                                .size(150.dp)
                                .align(Alignment.Center)
                                .border(2.dp, BrandBluePrimary.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Big Capture Button
                    Button(
                        onClick = {
                            val bmp = previewViewRef?.bitmap ?: generateSyntheticFallbackBitmap()
                            onImageCaptured(bmp)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("📸 Click Image & Assign Class", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Dataset Progress Summary & Proceed Button
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Collected Training Samples (${samples.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        if (samples.size >= 2) {
                            Text("Ready to Train", color = StatusPass, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Need ≥ 2 samples", color = StatusWarning, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SplitChip(label = "Intact", count = intactCount, color = StatusPass)
                        SplitChip(label = "Fracture", count = fractureCount, color = Color(0xFFFFA726))
                        SplitChip(label = "Broken", count = brokenCount, color = StatusFail)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (samples.size >= 2) {
                        Button(
                            onClick = onProceedToTraining,
                            colors = ButtonDefaults.buttonColors(containerColor = StatusPass),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("➡️ Proceed to Train Model (${samples.size} Samples)", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onImportStandardSamples,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Or Import Demo Biscuit Samples", color = BrandBluePrimary)
                        }
                    }
                }
            }
        }

        // 3. Thumbnails of Captured Samples
        if (samples.isNotEmpty()) {
            item {
                Text(
                    text = "Captured Images in Isolated Workspace:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextGrey,
                    fontWeight = FontWeight.Bold
                )
            }

            items(samples, key = { it.id }) { s ->
                CapturedSampleCard(
                    sample = s,
                    onEdit = { onEditSample(s) },
                    onDelete = { onDeleteSample(s.id) }
                )
            }
        }
    }
}

/**
 * Tab 2: On-Device Training Studio
 */
@Composable
private fun TrainingStudioTab(
    state: com.pocketqc.ui.viewmodel.ExperimentalLabUiState,
    onStartTraining: () -> Unit,
    onUpdateEpochs: (Int) -> Unit,
    onProceedToTesting: () -> Unit,
    onBackToCapture: () -> Unit
) {
    var epochs by remember { mutableFloatStateOf(state.trainingConfig.epochs.toFloat()) }
    val trainCount = state.samples.count { it.split == DatasetSplit.TRAIN }.let { if (it == 0) state.samples.size else it }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "🚀 Step 2: On-Device Training",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Trains a 2-layer MLP on your captured biscuit images with He initialization, mini-batch SGD, and momentum.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Training Dataset:", color = TextGrey, style = MaterialTheme.typography.bodySmall)
                    Text("$trainCount samples", color = TextWhite, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Epochs Slider
                Text(
                    text = "Training Epochs: ${epochs.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGrey
                )
                Slider(
                    value = epochs,
                    onValueChange = {
                        epochs = it
                        onUpdateEpochs(it.toInt())
                    },
                    valueRange = 5f..35f,
                    steps = 5,
                    enabled = !state.isTraining,
                    colors = SliderDefaults.colors(
                        thumbColor = BrandBluePrimary,
                        activeTrackColor = BrandBluePrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (state.samples.size >= 2) {
                    Button(
                        onClick = onStartTraining,
                        enabled = !state.isTraining,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        if (state.isTraining) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextWhite)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Training On-Device...")
                        } else {
                            Text("Start On-Device Training", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = onBackToCapture,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("← Capture At Least 2 Images First")
                    }
                }

                // If a model is already trained, give quick access to testing
                if (state.selectedModel != null || state.availableModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onProceedToTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("➡️ Proceed to Test Model with Camera", color = StatusPass)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Live Training Progress Bar
        state.trainingProgress?.let { prog ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Epoch ${prog.currentEpoch}/${prog.totalEpochs}",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { prog.currentEpoch.toFloat() / prog.totalEpochs },
                        modifier = Modifier.fillMaxWidth(),
                        color = BrandBluePrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Train Loss: ${"%.4f".format(prog.trainLoss)}", color = TextGrey, style = MaterialTheme.typography.bodySmall)
                        Text("Val Acc: ${"%.1f%%".format(prog.valAccuracy * 100)}", color = StatusPass, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Training Logs Console
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF262C36), RoundedCornerShape(8.dp))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "Training Console Logs",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextGrey,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.trainingLogs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF90CAF9),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tab 3: Interactive Single-Image Live Testing & Evaluation
 * "In evaluation, the user Test by Click a Image and the App classifies correctly. This Should the Workflow."
 */
@Composable
private fun TestAndClassifyStudioTab(
    state: com.pocketqc.ui.viewmodel.ExperimentalLabUiState,
    onClassifyImage: (Bitmap) -> Unit,
    onClearResult: () -> Unit,
    onRunBatchEvaluation: () -> Unit,
    onGoToTraining: () -> Unit
) {
    var testPreviewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var showBatchEvalDetails by remember { mutableStateOf(false) }

    val hasModel = state.selectedModel != null || state.availableModels.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Camera Testing Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ Step 3: Click & Classify",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasModel) {
                            Text("Model Ready", color = StatusPass, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        } else {
                            Text("No Model Yet", color = StatusWarning, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Point camera at a biscuit and tap Click & Classify to test your on-device model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (hasModel) {
                        // Live Viewfinder
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF333842), RoundedCornerShape(8.dp))
                        ) {
                            CameraViewfinder(
                                modifier = Modifier.fillMaxSize(),
                                onPreviewViewReady = { testPreviewViewRef = it }
                            )

                            // Reticle
                            Box(
                                modifier = Modifier
                                    .size(150.dp)
                                    .align(Alignment.Center)
                                    .border(2.dp, Color(0xFF00E676).copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Test Action Button
                        Button(
                            onClick = {
                                val bmp = testPreviewViewRef?.bitmap ?: generateSyntheticFallbackBitmap()
                                onClassifyImage(bmp)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("⚡ Click & Classify Image", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Prompt to train first
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color(0xFF1E222A), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No trained experimental model available", color = TextGrey)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onGoToTraining,
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary)
                                ) {
                                    Text("Go to Tab 2 & Train Model First")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Real-Time Classification Result Card
        state.liveTestResult?.let { result ->
            item {
                val (resultColor, resultTitle, resultDesc) = when (result.predictedClass) {
                    BiscuitLabClass.INTACT -> Triple(StatusPass, "COOKIE INTACT", "No visible cracks or fractures detected.")
                    BiscuitLabClass.FRACTURE -> Triple(Color(0xFFFFA726), "FRACTURE DEFECT", "Crack pattern detected on biscuit surface.")
                    BiscuitLabClass.BROKEN -> Triple(StatusFail, "BROKEN PIECE", "Biscuit broken or missing chunk.")
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, resultColor, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "CLASSIFICATION RESULT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextGrey,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = resultTitle,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = resultColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(resultColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${"%.1f%%".format(result.confidence * 100)} Conf",
                                    color = resultColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = resultDesc, style = MaterialTheme.typography.bodySmall, color = TextWhite)

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tested Image Preview & Probabilities Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = result.bitmap.asImageBitmap(),
                                contentDescription = "Tested biscuit",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF333842), RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                ProbabilityBar(label = "Broken", prob = result.probabilities.getOrElse(0) { 0f }, color = StatusFail)
                                Spacer(modifier = Modifier.height(4.dp))
                                ProbabilityBar(label = "Fracture", prob = result.probabilities.getOrElse(1) { 0f }, color = Color(0xFFFFA726))
                                Spacer(modifier = Modifier.height(4.dp))
                                ProbabilityBar(label = "Intact", prob = result.probabilities.getOrElse(2) { 0f }, color = StatusPass)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Classified strictly by on-device experimental model. Production model remains untouched.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextGrey,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // 3. Batch Evaluation (Test Split & Confusion Matrix) Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Held-Out Batch Evaluation",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showBatchEvalDetails = !showBatchEvalDetails }) {
                            Text(if (showBatchEvalDetails) "Hide" else "Show", color = BrandBluePrimary)
                        }
                    }

                    if (showBatchEvalDetails) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Runs formal batch evaluation on held-out test split, computing Precision, Recall, and Confusion Matrix.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRunBatchEvaluation,
                            enabled = hasModel && !state.isEvaluating,
                            colors = ButtonDefaults.buttonColors(containerColor = CardSurfaceDark),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isEvaluating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextWhite)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Running Batch Evaluation...")
                            } else {
                                Text("Run Batch Evaluation on Test Split")
                            }
                        }

                        state.evaluationReport?.let { report ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Batch Accuracy: ${"%.1f%%".format(report.overallAccuracy * 100)}", color = StatusPass, fontWeight = FontWeight.Bold)
                                Text("Samples: ${report.testSampleCount}", color = TextGrey)
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            for (m in report.perClassMetrics) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(m.className, color = TextWhite, modifier = Modifier.weight(1.2f), fontSize = 12.sp)
                                    Text("P: ${"%.2f".format(m.precision)}", color = TextGrey, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                    Text("R: ${"%.2f".format(m.recall)}", color = TextGrey, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                    Text("F1: ${"%.2f".format(m.f1Score)}", color = StatusPass, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tab 4: Security and Immutability Audit
 */
@Composable
private fun IsolationAuditTab(
    state: com.pocketqc.ui.viewmodel.ExperimentalLabUiState,
    onRunAudit: () -> Unit,
    onRequestReset: () -> Unit
) {
    val audit = state.auditResult

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🔒 Security & Immutability Verification",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verifies SHA-256 hash invariant on biscuits_final.onnx and directory isolation in private filesDir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRunAudit,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Re-verify Security Audit")
                    }
                }
            }
        }

        audit?.let { a ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        AuditItem(
                            label = "Production Model Asset",
                            value = a.productionModelAssetPath,
                            status = if (a.isAssetImmutable) "IMMUTABLE" else "TAMPERED",
                            statusColor = if (a.isAssetImmutable) StatusPass else StatusFail
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AuditItem(
                            label = "Production ONNX SHA-256",
                            value = a.productionModelHashAfter,
                            status = if (a.isHashIdentical) "MATCHED" else "MISMATCH",
                            statusColor = if (a.isHashIdentical) StatusPass else StatusFail
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AuditItem(
                            label = "Experimental Storage Directory",
                            value = a.experimentalStorageDirectory,
                            status = if (a.isDirectoryIsolated) "ISOLATED" else "LEAK DETECTED",
                            statusColor = if (a.isDirectoryIsolated) StatusPass else StatusFail
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Workspace Reset",
                        style = MaterialTheme.typography.titleSmall,
                        color = StatusFail,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Permanently wipes all captured images, annotations, checkpoints, and evaluations in files/experimental_lab/. Production inspection model remains untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRequestReset,
                        colors = ButtonDefaults.buttonColors(containerColor = StatusFail),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear All Experimental Data")
                    }
                }
            }
        }
    }
}

/**
 * Camera Viewfinder Composable
 */
@Composable
private fun CameraViewfinder(
    modifier: Modifier = Modifier,
    onPreviewViewReady: (PreviewView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (_: Exception) {}
        }
    }

    if (hasCameraPermission) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { previewView ->
                    onPreviewViewReady(previewView)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        } catch (e: Exception) {
                            Log.e("LabCamera", "Failed to bind camera preview", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(Color(0xFF1E222A))
                .border(1.dp, Color(0xFF333842), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Grant camera access to capture biscuit photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary)
                ) {
                    Text("Grant Camera Permission")
                }
            }
        }
    }
}

/**
 * Dialog to assign a class to a freshly captured biscuit photo
 */
@Composable
private fun AssignClassDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onAssignClass: (BiscuitLabClass) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📸 Assign Biscuit Class",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Image preview
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured biscuit",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF333842), RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Select defect category for this sample:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey
                )
                Spacer(modifier = Modifier.height(10.dp))

                // 3 Class Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onAssignClass(BiscuitLabClass.INTACT) },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusPass),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Intact", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onAssignClass(BiscuitLabClass.FRACTURE) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA726)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Fracture", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onAssignClass(BiscuitLabClass.BROKEN) },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusFail),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Broken", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Discard Photo", color = TextGrey)
            }
        }
    )
}

/**
 * Card representing a captured biscuit sample with delete option
 */
@Composable
private fun CapturedSampleCard(
    sample: ExperimentalSample,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(sample.imageFilePath) {
        val file = File(sample.imageFilePath)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.DarkGray, RoundedCornerShape(6.dp))
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClassTag(sample.assignedClass)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[${sample.split.name}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = sample.imageFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextWhite,
                    maxLines = 1
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(sample.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGrey,
                    fontSize = 10.sp
                )
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Text("✏️", fontSize = 14.sp)
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Text("🗑️", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ProbabilityBar(label: String, prob: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextGrey,
            fontSize = 11.sp,
            modifier = Modifier.width(55.dp)
        )
        LinearProgressIndicator(
            progress = { prob.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = Color(0xFF262C36)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${"%.1f%%".format(prob * 100)}",
            color = TextWhite,
            fontSize = 11.sp,
            modifier = Modifier.width(42.dp)
        )
    }
}

@Composable
private fun AuditItem(label: String, value: String, status: String, statusColor: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextGrey)
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = TextWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SplitChip(label: String, count: Int, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ClassTag(biscuitClass: BiscuitLabClass) {
    val (color, text) = when (biscuitClass) {
        BiscuitLabClass.BROKEN -> Pair(StatusFail, "Broken")
        BiscuitLabClass.FRACTURE -> Pair(Color(0xFFFFA726), "Fracture")
        BiscuitLabClass.INTACT -> Pair(StatusPass, "Intact")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EditAnnotationDialog(
    sample: ExperimentalSample,
    onDismiss: () -> Unit,
    onSave: (BiscuitLabClass, BoundingBox) -> Unit
) {
    var selectedClass by remember { mutableStateOf(sample.assignedClass) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Biscuit Annotation") },
        text = {
            Column {
                Text("Sample: ${sample.imageFileName}", style = MaterialTheme.typography.bodySmall, color = TextGrey)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Select Class Label:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in BiscuitLabClass.entries) {
                        Button(
                            onClick = { selectedClass = c },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedClass == c) BrandBluePrimary else CardSurfaceDark
                            )
                        ) {
                            Text(c.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selectedClass, sample.boundingBox) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Fallback bitmap generator for automated tests or environments where camera surface is uninitialized
 */
private fun generateSyntheticFallbackBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.DKGRAY)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(205, 133, 63)
        isAntiAlias = true
    }
    canvas.drawCircle(160f, 160f, 110f, paint)
    return bmp
}
