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
import com.pocketqc.batch.InspectionSession
import com.pocketqc.camera.CameraSource
import com.pocketqc.camera.CameraXCameraSource
import com.pocketqc.sensors.SensorProvider
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

@Composable
fun InspectionScreen(
    session: InspectionSession,
    cameraSource: CameraSource,
    sensorProvider: SensorProvider,
    onCompleteInspection: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Text(
                        text = "←",
                        color = TextWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    val annotatedTitle = buildAnnotatedString {
                        withStyle(SpanStyle(color = TextWhite)) { append("Pocket ") }
                        withStyle(SpanStyle(color = BrandBluePrimary)) { append("QC") }
                    }
                    Text(
                        text = annotatedTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${sessionState.mode.titleLine1} ${sessionState.mode.titleLine2} · ${sessionState.productName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight
                    )
                }
            }

            // Gating Pill Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isGatePassing) BrandBlueContainer else Color(0xFF381010))
                    .border(
                        1.dp,
                        if (isGatePassing) BrandBluePrimary.copy(alpha = 0.5f) else StatusFail,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isGatePassing) "STATION READY" else "STATION HOLD",
                    color = if (isGatePassing) BrandBlueLight else StatusFail,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Dominant Camera Inspection Viewport
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
                            text = "Pocket QC requires camera access for real-time defect inspection and quality verification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        SecondaryActionButton(
                            text = "Grant Camera Permission",
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }

            // Real-time overlay (live detections if real camera active, else current mock detections)
            val activeDetections = if (sessionState.isRealCameraActive) {
                sessionState.liveDetections
            } else {
                sessionState.currentDetections
            }
            InspectionOverlay(detections = activeDetections)

            // Top-left Viewport HUD Tag
            val streamTagText = if (sessionState.isRealCameraActive) {
                val fpsStr = String.format(java.util.Locale.US, "%.1f", sessionState.inferenceFps)
                "CAMERAX STREAM · $fpsStr FPS · SAMPLE ${sessionState.totalInspected}/${sessionState.maxSamples}"
            } else {
                "OPTICAL STREAM · 1080p @ 60 FPS · SAMPLE ${sessionState.totalInspected}/${sessionState.maxSamples}"
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC101214))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = streamTagText,
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Bottom HUD Telemetry
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC101214))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val hasDefect = activeDetections.any { it.className == "Broken" || it.className == "Fracture" }
                val telemetryStatus = if (hasDefect) {
                    val defectName = activeDetections.first { it.className == "Broken" || it.className == "Fracture" }.className
                    "DEFECT DETECTED ($defectName)"
                } else if (activeDetections.isNotEmpty()) {
                    "INTACT DETECTED"
                } else if (sessionState.totalInspected > 0) {
                    "SAMPLE VERIFIED (PASS)"
                } else {
                    "AWAITING SAMPLE CAPTURE"
                }

                val latencyTag = if (sessionState.isRealCameraActive) {
                    "ONNX CPU: ${sessionState.inferenceLatencyMs}ms"
                } else {
                    "NPU LATENCY: ~14ms"
                }

                Text(
                    text = "$latencyTag · $telemetryStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasDefect) StatusFail else if (activeDetections.isNotEmpty()) StatusPass else TextGrey
                )
            }
        }

        // Sensor Gating Telemetry Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardSurfaceDark)
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SensorTelemetryItem(
                label = "Stability",
                value = if (stability.isStable) "STABLE" else "MOTION",
                isOk = stability.isStable
            )
            SensorTelemetryItem(
                label = "Illuminance",
                value = "${lighting.illuminanceLux.toInt()} LUX",
                isOk = lighting.isAdequate
            )
            SensorTelemetryItem(
                label = "Sensor Gate",
                value = if (isGatePassing) "PASS" else "HOLD",
                isOk = isGatePassing
            )
        }

        // Live Inspection HUD Counters (Total, Passed, Defective)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "Total",
                value = "${sessionState.totalInspected}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Passed",
                value = "${sessionState.passedCount}",
                highlightColor = StatusPass,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defective",
                value = "${sessionState.defectiveCount}",
                highlightColor = if (sessionState.defectiveCount > 0) StatusFail else TextWhite,
                modifier = Modifier.weight(1f)
            )
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val canCapture = sessionState.totalInspected < sessionState.maxSamples

            SecondaryActionButton(
                text = if (canCapture) "Capture Sample" else "Quota Reached (24)",
                onClick = { session.captureSample() },
                modifier = Modifier.weight(1f),
                contentColor = if (canCapture) TextWhite else TextGrey
            )

            PrimaryActionButton(
                text = "Complete Inspection",
                enabled = sessionState.totalInspected > 0,
                onClick = {
                    val result = session.completeInspection()
                    if (result != null) {
                        onCompleteInspection()
                    }
                },
                modifier = Modifier.weight(1.3f)
            )
        }
    }
}

@Composable
private fun SensorTelemetryItem(
    label: String,
    value: String,
    isOk: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextGrey
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isOk) StatusPass else StatusFail)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isOk) TextWhite else StatusFail
            )
        }
    }
}
