package com.pocketqc.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pocketqc.batch.BatchManager
import com.pocketqc.batch.InspectionSession
import com.pocketqc.camera.CameraSource
import com.pocketqc.camera.CameraXCameraSource
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.Detection
import com.pocketqc.sensors.SensorProvider
import com.pocketqc.settings.TtsManager
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.components.InspectionOverlay
import com.pocketqc.ui.components.MetricCard
import com.pocketqc.ui.components.PrimaryActionButton
import com.pocketqc.ui.components.SecondaryActionButton
import com.pocketqc.ui.theme.BrandBlueContainer
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite
import com.pocketqc.ui.viewmodel.Inspection2DViewModel
import java.util.Locale

/**
 * Mode 1: 2D Defect Sort — Biscuit Conveyor/Bulk Batch Inspection.
 *
 * Implements continuous camera feed + live YOLO bounding boxes + automatic spatial tracking/debouncing.
 * A biscuit moving across multiple frames is counted exactly ONCE.
 * Primary action: [START BATCH] / [STOP BATCH].
 */
@Composable
fun InspectionScreen2D(
    session: InspectionSession,
    cameraSource: CameraSource,
    sensorProvider: SensorProvider,
    batchManager: BatchManager,
    ttsManager: TtsManager? = null,
    hapticManager: com.pocketqc.settings.HapticManager? = null,
    viewModel: Inspection2DViewModel = remember(batchManager, ttsManager, hapticManager) {
        Inspection2DViewModel(batchManager, ttsManager = ttsManager, hapticManager = hapticManager)
    },
    onBatchCompleted: (Batch) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (cameraSource is CameraXCameraSource && !hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val isRealCameraActive = cameraSource is CameraXCameraSource && hasCameraPermission

    LaunchedEffect(isRealCameraActive) {
        session.setRealCameraActive(isRealCameraActive)
    }

    DisposableEffect(cameraSource) {
        onDispose {
            if (cameraSource is CameraXCameraSource) {
                cameraSource.unbind()
            }
            session.setRealCameraActive(false)
        }
    }

    val sessionState by session.state.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // Pass incoming live telemetry from CameraX analyzer to ViewModel
    LaunchedEffect(sessionState.liveDetections, sessionState.inferenceFps, sessionState.inferenceLatencyMs) {
        viewModel.onLiveTelemetryUpdated(
            detections = sessionState.liveDetections,
            fps = sessionState.inferenceFps,
            latencyMs = sessionState.inferenceLatencyMs
        )
    }

    // React to batch completion from ViewModel
    LaunchedEffect(uiState.completedBatch) {
        uiState.completedBatch?.let { completed ->
            viewModel.clearCompletedBatch()
            onBatchCompleted(completed)
        }
    }

    val isBatchRunning = uiState.isBatchRunning
    val activeBatchId = uiState.activeBatchId
    val liveDetections = uiState.liveDetections
    val liveFps = uiState.fps
    val liveLatencyMs = uiState.latencyMs
    val totalCount = uiState.totalInspected
    val passCount = uiState.passedCount
    val defectCount = uiState.defectiveCount
    val defectRatePercent = uiState.defectRate

    val stability by sensorProvider.stabilityState.collectAsState()
    val lighting by sensorProvider.lightingState.collectAsState()
    val isGatePassing = stability.isStable && lighting.isAdequate

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Screen Header Bar
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("2D Defect ") }
            withStyle(SpanStyle(color = BrandBluePrimary)) { append("Sort") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "2D Defect Sort",
            annotatedTitle = annotatedTitle,
            subtitle = if (isBatchRunning) "CONVEYOR BATCH: $activeBatchId" else "CONVEYOR BATCH IDLE",
            onNavigateBack = onNavigateBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isBatchRunning) Color(0xFF0F2E1E) else BrandBlueContainer)
                        .border(
                            1.dp,
                            if (isBatchRunning) StatusPass else BrandBluePrimary.copy(alpha = 0.5f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isBatchRunning) StatusPass else BrandBlueLight)
                        )
                        Text(
                            text = if (isBatchRunning) "RUNNING" else "READY",
                            color = if (isBatchRunning) StatusPass else BrandBlueLight,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        )

        // Live Camera Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF090A0D))
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(20.dp))
        ) {
            if (cameraSource is CameraXCameraSource) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }.also { previewView ->
                                cameraSource.bindPreview(previewView, lifecycleOwner)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "CAMERA ACCESS REQUIRED",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Pocket QC requires camera access for real-time 2D conveyor inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        SecondaryActionButton(
                            text = "Grant Permission",
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }

            // Real-time YOLO Bounding Boxes
            InspectionOverlay(detections = liveDetections)

            // Top-left Viewport HUD Tag
            val fpsStr = String.format(Locale.US, "%.1f", liveFps)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xDD0F131A))
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(StatusPass)
                    )
                    Text(
                        text = "QNN HTP NPU · $fpsStr FPS · YOLOv8n",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom-left HUD Telemetry
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xDD0F131A))
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                val hasDefect = liveDetections.any { it.className == "Broken" || it.className == "Fracture" }
                val defectStatus = if (hasDefect) {
                    val name = liveDetections.first { it.className == "Broken" || it.className == "Fracture" }.className
                    "DEFECT DETECTED ($name)"
                } else if (liveDetections.isNotEmpty()) {
                    "INTACT DETECTED"
                } else {
                    "CONVEYOR ACTIVE"
                }

                Text(
                    text = "HTP Latency: ${liveLatencyMs}ms · $defectStatus",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasDefect) StatusFail else if (liveDetections.isNotEmpty()) StatusPass else TextGrey
                )
            }
        }

        // Live Batch Metrics (Total, Passed, Defective, Defect %)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Total",
                value = "$totalCount",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Passed",
                value = "$passCount",
                highlightColor = StatusPass,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defective",
                value = "$defectCount",
                highlightColor = if (defectCount > 0) StatusFail else TextWhite,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defect %",
                value = String.format(Locale.US, "%.1f%%", defectRatePercent),
                highlightColor = if (defectRatePercent > 10f) StatusFail else BrandBlueLight,
                modifier = Modifier.weight(1f)
            )
        }

        // Primary Action: [ START BATCH ] / [ STOP BATCH ]
        if (!isBatchRunning) {
            PrimaryActionButton(
                text = "START BATCH",
                onClick = {
                    viewModel.startBatch()
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            SecondaryActionButton(
                text = "STOP BATCH",
                onClick = {
                    viewModel.stopBatch()
                },
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color(0xFF2E1010),
                contentColor = StatusFail,
                borderColor = Color(0xFF5A1D1D)
            )
        }
    }
}
