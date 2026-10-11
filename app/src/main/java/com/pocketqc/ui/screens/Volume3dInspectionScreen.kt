package com.pocketqc.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.settings.TtsManager
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
import com.pocketqc.volume3d.ContainerGeometryType
import com.pocketqc.volume3d.ContainerProfile
import com.pocketqc.volume3d.MeasurementStatus
import com.pocketqc.volume3d.Volume3dInspectionEngine
import com.pocketqc.volume3d.Volume3dPhase
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mode 3: Calibrated Fill-Level & Container Volume Inspection.
 *
 * Real workflow:
 * - Single-camera optical frame acquisition with CameraX preview
 * - Geometric alignment reticle (Container base, target fill, tolerance zone, top cap)
 * - Real-time 1D gradient meniscus surface detection (Level A fill-height ratio)
 * - Calibrated volumetric estimation for uniform cylindrical containers (Level B)
 * - Ambiguity rejection (returns INCONCLUSIVE on flat, occluded, or low-contrast frames)
 * - Batch statistics, persistent history records, and Digital Batch Passport QR generation
 */
@Composable
fun Volume3dInspectionScreen(
    volumeEngine: Volume3dInspectionEngine,
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
    val state by volumeEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

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
            volumeEngine.setFrameProvider(null)
        }
    }

    LaunchedEffect(state.latestResult, state.bottleIndex) {
        if (state.phase == Volume3dPhase.BOTTLE_RESULT && state.latestResult?.status == MeasurementStatus.OUT_OF_TOLERANCE) {
            hapticManager?.vibrateDefect()
        }
    }

    val isRunning = state.phase != Volume3dPhase.IDLE && state.phase != Volume3dPhase.COMPLETED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header Row
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("Fill Level & ") }
            withStyle(SpanStyle(color = Color(0xFF26B6FF))) { append("Volume") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Fill Level & Volume",
            annotatedTitle = annotatedTitle,
            subtitle = "Single-Camera Calibrated Inspection · ±${String.format(Locale.US, "%.1f", state.tolerancePercent)}% Tol",
            onNavigateBack = onNavigateBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isRunning) Color(0xFF0F2E1E) else BrandBlueContainer)
                        .border(
                            1.dp,
                            if (isRunning) StatusPass else BrandBluePrimary.copy(alpha = 0.5f),
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
                                .background(if (isRunning) StatusPass else BrandBlueLight)
                        )
                        Text(
                            text = if (isRunning) "ACTIVE" else "READY",
                            color = if (isRunning) StatusPass else BrandBlueLight,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        )

        // 2. Scientific Honesty Notice Banner
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D1E2D))
                .border(1.dp, Color(0xFF1B4268), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF26B6FF))
            )
            Text(
                text = "CALIBRATED 2D OPTICAL SCREENING: Level A fill ratio with Level B volume estimation.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBCE1FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 3. Container Profile & Configuration Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurfaceDark)
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Profile Selection Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContainerProfile.ALL_PROFILES.forEach { prof ->
                    val isSelected = state.selectedProfile.id == prof.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) BrandBluePrimary else Color(0xFF181C24))
                            .border(1.dp, if (isSelected) BrandBlueLight else Color(0xFF2E3848), RoundedCornerShape(8.dp))
                            .clickable(enabled = !isRunning) {
                                volumeEngine.selectProfile(prof)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = prof.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) TextWhite else TextGrey,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Target Fill and Tolerance Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TARGET FILL: ${state.requiredFillPercent.toInt()}% · TOL: ±${String.format(Locale.US, "%.1f", state.tolerancePercent)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    val geomDesc = when (state.selectedProfile.geometryType) {
                        ContainerGeometryType.UNIFORM_CYLINDER -> "Uniform Cylinder (Ref: ${state.selectedProfile.referenceHeightMm.toInt()}mm, Vol supported)"
                        ContainerGeometryType.TAPERED_BOTTLE -> "Tapered Neck (Ref: ${state.selectedProfile.referenceHeightMm.toInt()}mm, Height % only)"
                        ContainerGeometryType.CUSTOM_CONTAINER -> "Custom Container (Ref: ${state.selectedProfile.referenceHeightMm.toInt()}mm)"
                    }
                    Text(
                        text = geomDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey,
                        fontSize = 10.sp
                    )
                }

                if (!isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { volumeEngine.setRequiredFillLevel(state.requiredFillPercent - 5f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(text = "−", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { volumeEngine.setRequiredFillLevel(state.requiredFillPercent + 5f) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(text = "+", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Custom Container Width Adjustment
            if (state.selectedProfile.geometryType == ContainerGeometryType.CUSTOM_CONTAINER) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CONTAINER WIDTH: ${state.selectedProfile.containerWidthPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF26B6FF),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Adjust reticle scan width to fit custom bottle",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextGrey,
                            fontSize = 10.sp
                        )
                    }

                    if (!isRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    volumeEngine.setContainerWidthPercent(state.selectedProfile.containerWidthPercent - 5f)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text(text = "−", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    volumeEngine.setContainerWidthPercent(state.selectedProfile.containerWidthPercent + 5f)
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text(text = "+", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 4. Real CameraX Viewport with Container Alignment Reticle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF090A0D))
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
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
                                previewViewRef = previewView
                                cameraSource.bindPreview(previewView, lifecycleOwner)
                                volumeEngine.setFrameProvider { previewView.bitmap }
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
                            text = "CAMERA PERMISSION REQUIRED",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusFail
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Grant camera permission to enable real optical fill inspection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OPTICAL INSPECTION READY",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF90D8FF),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Real-Time Container Alignment Reticle & Detection Lines
            val roi = state.calibrationRoi
            val targetPercent = state.requiredFillPercent
            val tolPercent = state.tolerancePercent
            val latest = state.latestResult

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val leftX = w * roi.leftNormalizedX
                val rightX = w * roi.rightNormalizedX
                val topY = h * roi.topNormalizedY
                val baseY = h * roi.baseNormalizedY

                val targetY = baseY - ((baseY - topY) * (targetPercent / 100f))
                val tolTopY = baseY - ((baseY - topY) * ((targetPercent + tolPercent) / 100f))
                val tolBottomY = baseY - ((baseY - topY) * ((targetPercent - tolPercent) / 100f))

                // 1. Container Boundary Box (Dashed Cyan)
                drawRect(
                    color = Color(0xFF26B6FF).copy(alpha = 0.5f),
                    topLeft = Offset(leftX, topY),
                    size = Size(rightX - leftX, baseY - topY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f))
                    )
                )

                // 2. Tolerance Shaded Region
                drawRect(
                    color = Color(0x3300FF88),
                    topLeft = Offset(leftX, tolTopY),
                    size = Size(rightX - leftX, tolBottomY - tolTopY)
                )

                // 3. Target Fill Line (Solid Cyan)
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(leftX - 10f, targetY),
                    end = Offset(rightX + 10f, targetY),
                    strokeWidth = 2.5.dp.toPx()
                )

                // 4. Top Reference Line (Cap 100%)
                drawLine(
                    color = Color(0xFFFFB74D),
                    start = Offset(leftX - 8f, topY),
                    end = Offset(rightX + 8f, topY),
                    strokeWidth = 2.dp.toPx()
                )

                // 5. Bottom Reference Line (Base 0%)
                drawLine(
                    color = Color(0xFF4FC3F7),
                    start = Offset(leftX - 8f, baseY),
                    end = Offset(rightX + 8f, baseY),
                    strokeWidth = 2.5.dp.toPx()
                )

                // 6. Detected Meniscus Line if available
                if (latest != null && latest.status != MeasurementStatus.ERROR) {
                    val meniscusY = if (latest.meniscusYNormalized > 0f) {
                        h * latest.meniscusYNormalized
                    } else {
                        baseY - ((baseY - topY) * (latest.detectedFillPercent / 100f))
                    }

                    val lineColor = when (latest.status) {
                        MeasurementStatus.PASS -> StatusPass
                        MeasurementStatus.OUT_OF_TOLERANCE -> StatusFail
                        MeasurementStatus.INCONCLUSIVE -> Color(0xFFFFB74D)
                        MeasurementStatus.ERROR -> Color.Gray
                    }

                    drawLine(
                        color = lineColor,
                        start = Offset(leftX - 15f, meniscusY),
                        end = Offset(rightX + 15f, meniscusY),
                        strokeWidth = 3.5.dp.toPx()
                    )
                }
            }

            // Guidance Overlay Badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xDD090A0D))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "ALIGN: Base on BOTTOM line · Cap on TOP line",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE0E0E0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 5. Active Sample Verdict Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardSurfaceDark)
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val bottleTitle = if (state.bottleIndex > 0) {
                    "Sample #${String.format(Locale.US, "%03d", state.bottleIndex)}"
                } else {
                    "Optical Alignment Mode"
                }
                Text(
                    text = bottleTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )

                val latest = state.latestResult
                val resultSubtitle = if (latest != null) {
                    when (latest.status) {
                        MeasurementStatus.PASS, MeasurementStatus.OUT_OF_TOLERANCE -> {
                            val sign = if (latest.differencePercent >= 0) "+" else ""
                            val hStr = latest.measuredHeightMm?.let { String.format(Locale.US, " | %.1f mm", it) } ?: ""
                            val vStr = latest.estimatedVolumeMl?.let { String.format(Locale.US, " | %.1f mL", it) } ?: ""
                            "Fill: ${String.format(Locale.US, "%.1f%%", latest.detectedFillPercent)} ($sign${String.format(Locale.US, "%.1f%%", latest.differencePercent)})$hStr$vStr"
                        }
                        MeasurementStatus.INCONCLUSIVE -> "⚠ ${latest.diagnostics}"
                        MeasurementStatus.ERROR -> "✗ Error: ${latest.diagnostics}"
                    }
                } else if (isRunning) {
                    "Evaluating container surface..."
                } else {
                    "Position container & press TEST ALIGNMENT or START BATCH"
                }

                Text(
                    text = resultSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (latest?.status == MeasurementStatus.INCONCLUSIVE) Color(0xFFFFB74D) else TextGrey,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (state.phase == Volume3dPhase.WAITING_NEXT_BOTTLE) {
                Text(
                    text = "Next in ${state.remainingWaitSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandBluePrimary,
                    fontWeight = FontWeight.Bold
                )
            } else if (state.latestResult != null) {
                val status = state.latestResult!!.status
                val badgeColor = when (status) {
                    MeasurementStatus.PASS -> Color(0xFF133820)
                    MeasurementStatus.OUT_OF_TOLERANCE -> Color(0xFF3D1414)
                    MeasurementStatus.INCONCLUSIVE -> Color(0xFF382A0F)
                    MeasurementStatus.ERROR -> Color(0xFF262626)
                }
                val textColor = when (status) {
                    MeasurementStatus.PASS -> StatusPass
                    MeasurementStatus.OUT_OF_TOLERANCE -> StatusFail
                    MeasurementStatus.INCONCLUSIVE -> Color(0xFFFFB74D)
                    MeasurementStatus.ERROR -> Color.LightGray
                }
                val textLabel = when (status) {
                    MeasurementStatus.PASS -> "✓ PASS"
                    MeasurementStatus.OUT_OF_TOLERANCE -> "✗ OUT OF TOL"
                    MeasurementStatus.INCONCLUSIVE -> "⚠ INCONCLUSIVE"
                    MeasurementStatus.ERROR -> "ERROR"
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = textLabel,
                        color = textColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 6. Metrics Summary Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(title = "Total", value = "${state.totalInspected}", modifier = Modifier.weight(1f))
            MetricCard(title = "Passed", value = "${state.passedCount}", highlightColor = StatusPass, modifier = Modifier.weight(1f))
            MetricCard(title = "Rejected", value = "${state.failedCount + state.inconclusiveCount}", highlightColor = if ((state.failedCount + state.inconclusiveCount) > 0) StatusFail else TextWhite, modifier = Modifier.weight(1f))
            MetricCard(title = "Avg Fill", value = String.format(Locale.US, "%.1f%%", state.averageDetectedFillPercent), highlightColor = BrandBlueLight, modifier = Modifier.weight(1f))
        }

        // 7. Action Bar
        if (!isRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryActionButton(
                    text = "TEST ALIGNMENT",
                    onClick = {
                        scope.launch {
                            val bmp = previewViewRef?.bitmap
                            volumeEngine.triggerImmediateScan(bmp)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                PrimaryActionButton(
                    text = "START BATCH",
                    onClick = {
                        volumeEngine.startBatch()
                        ttsManager?.announceBatchStarted()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrimaryActionButton(
                    text = "INSPECT BOTTLE",
                    onClick = {
                        scope.launch {
                            val bmp = previewViewRef?.bitmap
                            val res = volumeEngine.triggerImmediateScan(bmp)
                            if (res.status == MeasurementStatus.PASS) {
                                ttsManager?.announceSamplePassed()
                            } else if (res.status == MeasurementStatus.OUT_OF_TOLERANCE) {
                                ttsManager?.announceSampleFlagged()
                                hapticManager?.vibrateDefect()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                SecondaryActionButton(
                    text = "STOP BATCH",
                    onClick = {
                        scope.launch {
                            val completedBatch = volumeEngine.stopBatch()
                            batchManager.saveBatch(completedBatch)
                            ttsManager?.announceBatchCompleted(completedBatch.status == BatchStatus.APPROVED)
                            onBatchCompleted(completedBatch)
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
