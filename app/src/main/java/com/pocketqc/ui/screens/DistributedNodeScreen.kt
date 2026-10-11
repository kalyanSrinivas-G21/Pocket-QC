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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pocketqc.batch.InspectionSession
import com.pocketqc.camera.CameraSource
import com.pocketqc.camera.CameraXCameraSource
import com.pocketqc.distributed.client.InspectionNodeCoordinator
import com.pocketqc.distributed.client.NodeConnectionState
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.tracking.CentroidDetectionTracker
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.components.InspectionOverlay
import com.pocketqc.ui.components.PocketQcTopBar
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
import com.pocketqc.volume3d.CalibrationRoi
import com.pocketqc.volume3d.ContainerGeometryType
import com.pocketqc.volume3d.ContainerProfile
import com.pocketqc.volume3d.MeasurementStatus
import com.pocketqc.volume3d.Volume3dInspectionEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DistributedNodeScreen(
    coordinator: InspectionNodeCoordinator,
    cameraSource: CameraSource,
    session: InspectionSession,
    cameraSourceUv: CameraSource? = null,
    uvEngine: UvInspectionEngine? = null,
    volumeEngine: Volume3dInspectionEngine? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val uiState by coordinator.uiState.collectAsState()
    val sessionState by session.state.collectAsState()
    val uvState = uvEngine?.state?.collectAsState()?.value
    val volumeState = volumeEngine?.state?.collectAsState()?.value

    var selectedMode by remember { mutableStateOf(InspectionMode.DEFECT_SORT_2D) }
    var serverIpInput by remember { mutableStateOf(uiState.serverHost) }
    var serverPortInput by remember { mutableStateOf(uiState.serverPort.toString()) }
    var stationNameInput by remember { mutableStateOf(uiState.stationName) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isAnalyzingVolume by remember { mutableStateOf(false) }

    val tracker2D = remember { CentroidDetectionTracker() }
    val trackerUv = remember { CentroidDetectionTracker() }

    val activeCameraSource = when (selectedMode) {
        InspectionMode.DEFECT_SORT_2D -> cameraSource
        InspectionMode.UV_AFLATOXIN -> cameraSourceUv ?: cameraSource
        InspectionMode.VOLUME_CHECK_3D -> cameraSource
    }

    // Camera permission
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
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Set tracker batch state when inspecting toggles
    LaunchedEffect(uiState.isInspecting) {
        if (uiState.isInspecting) {
            tracker2D.startBatch()
            trackerUv.startBatch()
        } else {
            tracker2D.stopBatch()
            trackerUv.stopBatch()
        }
    }

    // 2D frame ingestion
    LaunchedEffect(sessionState.liveDetections, sessionState.inferenceFps, sessionState.inferenceLatencyMs, selectedMode) {
        if (selectedMode == InspectionMode.DEFECT_SORT_2D) {
            coordinator.updateTelemetry(sessionState.inferenceFps, sessionState.inferenceLatencyMs)
            if (uiState.isInspecting && sessionState.liveDetections.isNotEmpty()) {
                val newlyCounted = tracker2D.processFrame(sessionState.liveDetections)
                if (newlyCounted.isNotEmpty()) {
                    coordinator.onNewlyCountedItems(newlyCounted, inspectionMode = "2D_DEFECT_SORT", modelId = "biscuits_final")
                }
            }
        }
    }

    // UV frame ingestion
    LaunchedEffect(uvState?.liveDetections, uvState?.inferenceFps, uvState?.inferenceLatencyMs, selectedMode) {
        if (selectedMode == InspectionMode.UV_AFLATOXIN && uvState != null) {
            coordinator.updateTelemetry(uvState.inferenceFps, uvState.inferenceLatencyMs)
            if (uiState.isInspecting && uvState.liveDetections.isNotEmpty()) {
                val newlyCounted = trackerUv.processFrame(uvState.liveDetections)
                if (newlyCounted.isNotEmpty()) {
                    coordinator.onNewlyCountedItems(newlyCounted, inspectionMode = "UV_AFLATOXIN", modelId = "aflatoxin_b1")
                }
            }
        }
    }

    // 3D volume frame provider setup
    LaunchedEffect(previewViewRef, selectedMode) {
        val pv = previewViewRef
        if (pv != null && volumeEngine != null && selectedMode == InspectionMode.VOLUME_CHECK_3D) {
            volumeEngine.setFrameProvider { pv.bitmap }
        }
    }

    DisposableEffect(activeCameraSource) {
        onDispose {
            if (activeCameraSource is CameraXCameraSource) {
                activeCameraSource.unbind()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        PocketQcTopBar(
            title = "Inspection Node Mode",
            onNavigateBack = {
                coordinator.disconnect()
                onNavigateBack()
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // 1. Server Connection Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SERVER CONNECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlueLight,
                            fontWeight = FontWeight.Bold
                        )

                        val statusColor = when (uiState.connectionState) {
                            NodeConnectionState.CONNECTED -> StatusPass
                            NodeConnectionState.CONNECTING, NodeConnectionState.RECONNECTING -> Color(0xFFFBBF24)
                            NodeConnectionState.ERROR -> StatusFail
                            NodeConnectionState.DISCONNECTED -> TextGrey
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = uiState.connectionState.name,
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (uiState.connectionState != NodeConnectionState.CONNECTED) {
                        OutlinedTextField(
                            value = stationNameInput,
                            onValueChange = { stationNameInput = it },
                            label = { Text("Station Name (e.g. Line A Inspector)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandBluePrimary,
                                unfocusedBorderColor = CardSurfaceBorder,
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite
                            ),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = serverIpInput,
                                onValueChange = { serverIpInput = it },
                                label = { Text("Server LAN IP") },
                                placeholder = { Text("e.g. 192.168.43.1") },
                                modifier = Modifier.weight(2f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandBluePrimary,
                                    unfocusedBorderColor = CardSurfaceBorder,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = serverPortInput,
                                onValueChange = { serverPortInput = it },
                                label = { Text("Port") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandBluePrimary,
                                    unfocusedBorderColor = CardSurfaceBorder,
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite
                                ),
                                singleLine = true
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandBlueContainer)
                                    .border(1.dp, BrandBluePrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                                        if (clipText.isNotBlank()) {
                                            val (cleanHost, cleanPort) = com.pocketqc.distributed.server.NetworkUtils.normalizeHostAndPort(clipText, 8080)
                                            serverIpInput = cleanHost
                                            serverPortInput = cleanPort.toString()
                                            coordinator.connect(cleanHost, cleanPort, stationNameInput)
                                        } else {
                                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "📋 Paste & Connect",
                                    color = BrandBluePrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E2536))
                                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        serverIpInput = "192.168.43.1"
                                        serverPortInput = "8080"
                                        coordinator.connect("192.168.43.1", 8080, stationNameInput)
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "🔥 Hotspot (43.1)",
                                    color = BrandBlueLight,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Text(
                            text = "💡 Tip: If connected to Server phone's Hotspot, tap 'Hotspot (43.1)' to connect instantly.",
                            color = TextGrey,
                            fontSize = 11.sp
                        )

                        PrimaryActionButton(
                            text = if (uiState.connectionState == NodeConnectionState.CONNECTING) "CONNECTING..." else "CONNECT TO SERVER",
                            onClick = {
                                val port = serverPortInput.toIntOrNull() ?: 8080
                                coordinator.connect(serverIpInput, port, stationNameInput)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Station: ${uiState.stationName} (ID: ${uiState.deviceId})",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Connected to http://${uiState.serverHost}:${uiState.serverPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        SecondaryActionButton(
                            text = "DISCONNECT FROM SERVER",
                            onClick = { coordinator.disconnect() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = StatusFail,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Shared Batch State Banner
            val activeBatch = uiState.activeBatch
            val isBatchRunning = activeBatch?.state == DistributedBatchState.RUNNING

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isBatchRunning) Color(0xFF10281E) else CardSurfaceDark)
                    .border(1.dp, if (isBatchRunning) StatusPass.copy(alpha = 0.4f) else CardSurfaceBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "SERVER BATCH",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBatchRunning) StatusPass else TextGrey,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = activeBatch?.batchId ?: "No active batch running on server",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isBatchRunning) StatusPass.copy(alpha = 0.2f) else Color.DarkGray)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = activeBatch?.state?.name ?: "IDLE",
                            color = if (isBatchRunning) StatusPass else TextGrey,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inspection Type Selection Chips
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "INSPECTION TYPE (STATION MODE)",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            InspectionMode.DEFECT_SORT_2D to "2D Defect Sort",
                            InspectionMode.UV_AFLATOXIN to "UV Inspection",
                            InspectionMode.VOLUME_CHECK_3D to "3D Volume Check"
                        ).forEach { (mode, label) ->
                            val isSelected = selectedMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) BrandBluePrimary else Color(0xFF1E2430))
                                    .border(1.dp, if (isSelected) BrandBlueLight else CardSurfaceBorder, RoundedCornerShape(8.dp))
                                    .clickable(enabled = !uiState.isInspecting) {
                                        selectedMode = mode
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) TextWhite else TextGrey,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 3D Volume Container Profile & Width Configuration
            if (selectedMode == InspectionMode.VOLUME_CHECK_3D && volumeState != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CONTAINER PROFILE & WIDTH ADJUSTMENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.Bold
                    )

                    // Profile selection chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ContainerProfile.ALL_PROFILES.forEach { prof ->
                            val isSel = volumeState.selectedProfile.id == prof.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) BrandBluePrimary else Color(0xFF181C24))
                                    .border(1.dp, if (isSel) BrandBlueLight else Color(0xFF2E3848), RoundedCornerShape(8.dp))
                                    .clickable(enabled = !uiState.isInspecting) {
                                        volumeEngine?.selectProfile(prof)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = prof.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSel) TextWhite else TextGrey,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Target fill level controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TARGET FILL: ${volumeState.requiredFillPercent.toInt()}% · TOL: ±${String.format(Locale.US, "%.1f", volumeState.tolerancePercent)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )

                        if (!uiState.isInspecting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { volumeEngine?.setRequiredFillLevel(volumeState.requiredFillPercent - 5f) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text(text = "−", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { volumeEngine?.setRequiredFillLevel(volumeState.requiredFillPercent + 5f) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text(text = "+", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Custom Container Width adjustment controls
                    if (volumeState.selectedProfile.geometryType == ContainerGeometryType.CUSTOM_CONTAINER) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "CONTAINER WIDTH: ${volumeState.selectedProfile.containerWidthPercent.toInt()}%",
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

                            if (!uiState.isInspecting) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            volumeEngine?.setContainerWidthPercent(volumeState.selectedProfile.containerWidthPercent - 5f)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Text(text = "−", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            volumeEngine?.setContainerWidthPercent(volumeState.selectedProfile.containerWidthPercent + 5f)
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
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Camera Viewport & Live Overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(18.dp))
            ) {
                if (hasCameraPermission && activeCameraSource is CameraXCameraSource) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }.also { previewView ->
                                previewViewRef = previewView
                                activeCameraSource.bindPreview(previewView, lifecycleOwner)
                                if (selectedMode == InspectionMode.VOLUME_CHECK_3D && volumeEngine != null) {
                                    volumeEngine.setFrameProvider { previewView.bitmap }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                when (selectedMode) {
                    InspectionMode.DEFECT_SORT_2D -> {
                        // 2D YOLO bounding box overlay
                        InspectionOverlay(
                            detections = sessionState.liveDetections,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    InspectionMode.UV_AFLATOXIN -> {
                        // UV YOLO bounding box overlay
                        InspectionOverlay(
                            detections = uvState?.liveDetections ?: emptyList(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    InspectionMode.VOLUME_CHECK_3D -> {
                        // Real-Time Container Alignment Reticle & Detection Lines
                        val roi = volumeState?.calibrationRoi ?: CalibrationRoi.DEFAULT
                        val targetPercent = volumeState?.requiredFillPercent ?: 80f
                        val tolPercent = volumeState?.tolerancePercent ?: 3f
                        val latest = volumeState?.latestResult

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
                                style = Stroke(
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

                                drawLine(
                                    color = if (latest.isPass) Color(0xFF00E676) else Color(0xFFFF5252),
                                    start = Offset(leftX - 10f, meniscusY),
                                    end = Offset(rightX + 10f, meniscusY),
                                    strokeWidth = 3.dp.toPx()
                                )
                            }
                        }
                    }
                }

                // Top telemetry overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    val teleText = when (selectedMode) {
                        InspectionMode.DEFECT_SORT_2D -> "2D YOLOv8n · ${String.format(Locale.US, "%.1f", uiState.fps)} FPS · ${uiState.latencyMs}ms"
                        InspectionMode.UV_AFLATOXIN -> "UV YOLOv8n · ${String.format(Locale.US, "%.1f", uvState?.inferenceFps ?: uiState.fps)} FPS · ${uvState?.inferenceLatencyMs ?: uiState.latencyMs}ms"
                        InspectionMode.VOLUME_CHECK_3D -> "3D Fill Optical · Target: ${volumeState?.requiredFillPercent?.toInt() ?: 80}% · Width: ${volumeState?.selectedProfile?.containerWidthPercent?.toInt() ?: 50}%"
                    }
                    Text(
                        text = teleText,
                        color = BrandBlueLight,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Inspecting indicator badge
                if (uiState.isInspecting) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StatusPass.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "LIVE STREAMING",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3D Volume on-demand scan trigger button
            if (selectedMode == InspectionMode.VOLUME_CHECK_3D && uiState.isInspecting) {
                Spacer(modifier = Modifier.height(10.dp))
                PrimaryActionButton(
                    text = if (isAnalyzingVolume) "ANALYZING LIQUID FILL..." else "⚡ TRIGGER CONTAINER SCAN",
                    onClick = {
                        coroutineScope.launch {
                            isAnalyzingVolume = true
                            val bmp = previewViewRef?.bitmap
                            val res = volumeEngine?.triggerImmediateScan(bmp)
                            if (res != null) {
                                val isPass = res.status == MeasurementStatus.PASS
                                coordinator.recordInspectionEvent(
                                    className = if (isPass) "PASS" else "OUT_OF_TOLERANCE",
                                    classId = if (isPass) 1 else 0,
                                    confidence = res.confidence,
                                    inspectionMode = "VOLUME_CHECK_3D",
                                    modelId = "geometric_fill_analyzer",
                                    itemId = "${uiState.deviceId}-bottle-${System.currentTimeMillis()}"
                                )
                            }
                            isAnalyzingVolume = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inspection Start / Stop action
            if (!uiState.isInspecting) {
                PrimaryActionButton(
                    text = "START NODE INSPECTION",
                    onClick = { coordinator.startInspection() },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                SecondaryActionButton(
                    text = "STOP NODE INSPECTION",
                    onClick = { coordinator.stopInspection() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 4. Local Station Metrics & Outbox Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LOCAL STATION METRICS",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E2536))
                        .clickable {
                            coordinator.clearLocalMetricsAndOutbox()
                            Toast.makeText(context, "Station metrics cleared", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Reset Counts",
                        color = TextGrey,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "COUNTED ITEMS", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(text = uiState.localItemCount.toString(), color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "OUTBOX PENDING", color = TextGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        val queueColor = if (uiState.outboxQueueDepth == 0) StatusPass else Color(0xFFFBBF24)
                        Text(text = uiState.outboxQueueDepth.toString(), color = queueColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val localBreakdownText = if (selectedMode == InspectionMode.VOLUME_CHECK_3D) {
                        "Local 3D Fill Breakdown: In Tolerance: ${uiState.localIntactCount} · Out of Tolerance: ${uiState.localBrokenCount}"
                    } else if (selectedMode == InspectionMode.UV_AFLATOXIN) {
                        "Local UV Breakdown: Passed: ${uiState.localIntactCount} · Flagged: ${uiState.localBrokenCount}"
                    } else {
                        "Local 2D Breakdown: Intact: ${uiState.localIntactCount} · Broken: ${uiState.localBrokenCount} · Fracture: ${uiState.localFractureCount}"
                    }
                    Text(
                        text = localBreakdownText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextGrey,
                        fontSize = 11.sp
                    )
                    if (uiState.lastSyncTimeUtc > 0) {
                        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                        Text(
                            text = "Last synced to server: ${fmt.format(Date(uiState.lastSyncTimeUtc))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusPass,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
