package com.pocketqc.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pocketqc.camera.CameraSource
import com.pocketqc.camera.CameraXCameraSource
import com.pocketqc.core.model.Batch
import com.pocketqc.settings.TtsManager
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
import com.pocketqc.uv.UvInspectionEngine
import com.pocketqc.uv.UvInspectionPhase
import com.pocketqc.uv.UvSampleResult
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mode 2: UV Scan — Real Camera Optical Aflatoxin B1 Screening.
 *
 * Real workflow:
 * - CameraX preview aligned to Chilli powder sample under manual 365nm UV illumination
 * - Real-time YOLOv8n Aflatoxin ONNX inference running locally on Android CPU
 * - Live bounding boxes displayed around detected fluorescent contamination spots
 * - Inter-sample countdown with instant [SCAN NOW] trigger
 * - Structured batch statistics and offline QR passport generation
 */
@Composable
fun UvInspectionScreen(
    uvEngine: UvInspectionEngine,
    cameraSource: CameraSource? = null,
    batchManager: BatchManager,
    ttsManager: TtsManager? = null,
    hapticManager: com.pocketqc.settings.HapticManager? = null,
    onBatchCompleted: (Batch) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by uvEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

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

    DisposableEffect(cameraSource) {
        onDispose {
            if (cameraSource is CameraXCameraSource) {
                cameraSource.unbind()
            }
            uvEngine.setFrameProvider { null }
        }
    }

    // TTS announcement & haptic feedback on sample completion
    LaunchedEffect(state.currentSampleResult, state.sampleIndex) {
        if (state.phase == UvInspectionPhase.RECORD_RESULT) {
            when (state.currentSampleResult) {
                UvSampleResult.FLAGGED -> {
                    ttsManager?.announceSampleFlagged()
                    hapticManager?.vibrateDefect()
                }
                UvSampleResult.PASS -> ttsManager?.announceSamplePassed()
                null -> Unit
            }
        }
    }

    val isRunning = state.phase != UvInspectionPhase.IDLE && state.phase != UvInspectionPhase.COMPLETED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("UV ") }
            withStyle(SpanStyle(color = Color(0xFFA78BFA))) { append("Scan") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "UV Scan",
            annotatedTitle = annotatedTitle,
            subtitle = "Aflatoxin B1 Optical Screening · 365nm",
            onNavigateBack = onNavigateBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF24153B))
                        .border(1.dp, Color(0xFF9D65FF).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "MANUAL UV 365nm",
                        color = Color(0xFFD6BCFA),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )

        // Operational Guidance Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF191326))
                .border(1.dp, Color(0xFF3B2556), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFA78BFA))
            )
            Text(
                text = "OPERATOR GUIDANCE: Switch on 365nm UV lamp manually before scanning chilli sample.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD6BCFA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Live Camera Viewport with Real-Time Bounding Box Overlay
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
                                uvEngine.setFrameProvider {
                                    if (Looper.myLooper() == Looper.getMainLooper()) {
                                        try {
                                            if (previewView.isAttachedToWindow && previewView.width > 0 && previewView.height > 0) {
                                                previewView.bitmap
                                            } else {
                                                null
                                            }
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    } else {
                                        var bmp: Bitmap? = null
                                        val latch = CountDownLatch(1)
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                if (previewView.isAttachedToWindow && previewView.width > 0 && previewView.height > 0) {
                                                    bmp = previewView.bitmap
                                                }
                                            } catch (_: Throwable) {
                                                bmp = null
                                            } finally {
                                                latch.countDown()
                                            }
                                        }
                                        try {
                                            latch.await(300, TimeUnit.MILLISECONDS)
                                        } catch (_: InterruptedException) {}
                                        bmp
                                    }
                                }
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
                            color = StatusFail
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Grant camera permission to enable real optical UV inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Fallback simulation viewport when camera hardware is absent
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "UV OPTICAL SCANNER READY",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC7A7FF),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Real-Time YOLO Bounding Box Overlay for Detected Aflatoxin Spots
            InspectionOverlay(
                detections = state.liveDetections,
                modifier = Modifier.fillMaxSize()
            )

            // Top-left HUD Tag
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC101214))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "AFLATOXIN YOLOv8n · QUALCOMM QNN HTP (v81)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC7A7FF),
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Top-right Telemetry HUD Tag
            if (state.inferenceLatencyMs > 0 || state.inferenceFps > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC101214))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${state.inferenceLatencyMs}ms · ${String.format(Locale.US, "%.1f", state.inferenceFps)} FPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom Stage HUD Banner
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xDD0D0F14))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    state.phase == UvInspectionPhase.IDLE -> {
                        Text(
                            text = "Position Sample Under UV Light & Tap START BATCH",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite
                        )
                    }
                    state.phase == UvInspectionPhase.ANALYZING || state.phase == UvInspectionPhase.SCANNING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF9D65FF),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Screening Sample with Aflatoxin Model...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2D1FF)
                            )
                        }
                    }
                    state.phase == UvInspectionPhase.RECORD_RESULT -> {
                        val isFlagged = state.currentSampleResult == UvSampleResult.FLAGGED
                        val resultColor = if (isFlagged) StatusFail else StatusPass
                        val resultText = if (isFlagged) {
                            "⚠️ Sample #${state.sampleIndex}: FLAGGED (${state.currentSpotsCount} spots, ${String.format(Locale.US, "%.0f%%", state.currentConfidence * 100f)} conf)"
                        } else {
                            "✓ Sample #${state.sampleIndex}: PASS (No contamination spots)"
                        }
                        Text(
                            text = resultText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = resultColor
                        )
                    }
                    state.phase == UvInspectionPhase.WAITING_15_SECONDS -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Next sample screening in: ${state.remainingWaitSeconds}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextWhite
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF28193D))
                                    .border(1.dp, Color(0xFF9D65FF), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "SCAN NOW",
                                    color = Color(0xFFD6BCFA),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = "Batch Active · Ready for Next Sample",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextWhite
                        )
                    }
                }
            }
        }

        // Batch Metrics (Total Samples, Passed, Flagged, Flag Rate %)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Total",
                value = "${state.totalInspected}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Passed",
                value = "${state.passedCount}",
                highlightColor = StatusPass,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Flagged",
                value = "${state.flaggedCount}",
                highlightColor = if (state.flaggedCount > 0) StatusFail else TextWhite,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Flag %",
                value = String.format(Locale.US, "%.1f%%", state.flagRate),
                highlightColor = if (state.flagRate > 10f) StatusFail else BrandBlueLight,
                modifier = Modifier.weight(1f)
            )
        }

        // Action Controls: [ START BATCH ] or [ SCAN NOW ] + [ STOP BATCH ]
        if (!isRunning) {
            PrimaryActionButton(
                text = "START BATCH",
                onClick = {
                    uvEngine.startBatch()
                    ttsManager?.announceBatchStarted()
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryActionButton(
                    text = "SCAN NOW",
                    onClick = {
                        uvEngine.triggerImmediateScan()
                    },
                    modifier = Modifier.weight(1f),
                    containerColor = Color(0xFF28193D),
                    contentColor = Color(0xFFD6BCFA)
                )
                SecondaryActionButton(
                    text = "STOP BATCH",
                    onClick = {
                        scope.launch {
                            try {
                                val completedBatch = uvEngine.stopBatch()
                                batchManager.saveBatch(completedBatch)
                                val isApproved = completedBatch.status == com.pocketqc.core.model.BatchStatus.APPROVED
                                ttsManager?.announceBatchCompleted(isApproved)
                                onBatchCompleted(completedBatch)
                            } catch (e: Throwable) {
                                Log.e("UvInspectionScreen", "Failed to stop batch safely", e)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    containerColor = Color(0xFF381010),
                    contentColor = StatusFail
                )
            }
        }
    }
}

