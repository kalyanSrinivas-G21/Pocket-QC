package com.pocketqc.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.server.InspectionServerManager
import com.pocketqc.distributed.server.ServerRunState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DistributedServerScreen(
    serverManager: InspectionServerManager,
    onViewBatchDetail: (String) -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val serverState by serverManager.serverState.collectAsState()
    val serverIp by serverManager.serverIp.collectAsState()
    val serverPort by serverManager.serverPort.collectAsState()
    val activeBatch by serverManager.activeBatch.collectAsState()
    val devices by serverManager.devices.collectAsState()
    val summary by serverManager.currentSummary.collectAsState()
    val recentEvents by serverManager.recentEvents.collectAsState()
    val lastError by serverManager.lastError.collectAsState()
    val lastCompletedBatch by serverManager.lastCompletedBatch.collectAsState()

    var showCreateBatchDialog by remember { mutableStateOf(false) }
    var newBatchLotName by remember { mutableStateOf("Combined QC Lot #101") }
    var newBatchMode by remember { mutableStateOf("COMBINED_INSPECTION") }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val connectionUrl = "http://$serverIp:$serverPort"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        PocketQcTopBar(
            title = "Server Mode Dashboard",
            onNavigateBack = onNavigateBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // 1. Server Network Banner Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dotColor = when (serverState) {
                                ServerRunState.RUNNING -> StatusPass
                                ServerRunState.STARTING -> Color(0xFFFBBF24)
                                ServerRunState.ERROR -> StatusFail
                                ServerRunState.STOPPED -> TextGrey
                            }
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = "SERVER STATUS: ${serverState.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandBlueLight,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // Toggle server action
                        if (serverState == ServerRunState.RUNNING) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusFail.copy(alpha = 0.15f))
                                    .border(1.dp, StatusFail.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable { serverManager.stopServer() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "STOP SERVER",
                                    color = StatusFail,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrandBlueContainer)
                                    .border(1.dp, BrandBluePrimary, RoundedCornerShape(8.dp))
                                    .clickable { serverManager.startServer() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "START SERVER",
                                    color = BrandBluePrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (serverState == ServerRunState.RUNNING) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Server Network Address:",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "$serverIp : $serverPort",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextWhite,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    Text(
                                        text = connectionUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(BrandBlueContainer)
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(serverIp))
                                                Toast.makeText(context, "Copied IP: $serverIp", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "Copy IP",
                                            color = BrandBluePrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF1E2536))
                                            .clickable {
                                                serverManager.refreshNetworkAddress()
                                                Toast.makeText(context, "Network IP refreshed", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "Refresh",
                                            color = BrandBlueLight,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            if (serverIp == "127.0.0.1") {
                                Text(
                                    text = "⚠️ No active Wi-Fi or Hotspot IP detected. Connect to Wi-Fi or enable Hotspot, then tap 'Refresh'.",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp
                                )
                            } else {
                                Text(
                                    text = "💡 Node Phones: Connect to this phone's Wi-Fi / Hotspot and enter IP $serverIp",
                                    color = StatusPass,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    if (lastError != null) {
                        Text(
                            text = lastError ?: "",
                            color = StatusFail,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Shared Batch Management Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SHARED BATCH LIFECYCLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlueLight,
                            fontWeight = FontWeight.Bold
                        )

                        val stateColor = when (activeBatch?.state) {
                            DistributedBatchState.RUNNING -> StatusPass
                            DistributedBatchState.CREATED -> Color(0xFFFBBF24)
                            DistributedBatchState.STOPPED, null -> TextGrey
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(stateColor.copy(alpha = 0.15f))
                                .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = activeBatch?.state?.name ?: "NO ACTIVE BATCH",
                                color = stateColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (activeBatch != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "Active Batch ID: ${activeBatch?.batchId}",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            val modeDesc = when (activeBatch?.inspectionMode) {
                                "COMBINED_INSPECTION", "COMBINED_2D_3D", "MULTI_STATION_QC" -> "Multi-Station Network (2D Defect + 3D Volume)"
                                "VOLUME_CHECK_3D" -> "3D Volume Inspection"
                                "UV_AFLATOXIN" -> "UV Aflatoxin Screening"
                                else -> "2D Defect Sort"
                            }
                            Text(
                                text = "Lot: ${activeBatch?.productName} · $modeDesc",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandBlueLight
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (activeBatch?.state == DistributedBatchState.CREATED) {
                                PrimaryActionButton(
                                    text = "START SHARED BATCH",
                                    onClick = { activeBatch?.let { serverManager.startBatch(it.batchId) } },
                                    modifier = Modifier.weight(1f)
                                )
                            } else if (activeBatch?.state == DistributedBatchState.RUNNING) {
                                SecondaryActionButton(
                                    text = "STOP SHARED BATCH",
                                    onClick = { activeBatch?.let { serverManager.stopBatch(it.batchId) } },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Create a shared inspection batch for connected nodes to start streaming defect events.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey
                        )

                        PrimaryActionButton(
                            text = "+ CREATE NEW BATCH",
                            onClick = { showCreateBatchDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Reset / Clear Test Data Action
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusFail.copy(alpha = 0.1f))
                            .border(1.dp, StatusFail.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .clickable { showResetConfirmDialog = true }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "RESET ALL DISTRIBUTED DATA",
                            color = StatusFail,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            if (lastCompletedBatch != null) {
                val completed = lastCompletedBatch!!
                val isApproved = completed.status == BatchStatus.APPROVED
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardSurfaceDark)
                        .border(1.5.dp, if (isApproved) StatusPass.copy(alpha = 0.6f) else StatusFail.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "BATCH PASSPORT PRODUCED (QR)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandBlueLight,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = completed.batchId,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isApproved) Color(0xFF0F2E1E) else Color(0xFF381010))
                                    .border(1.dp, if (isApproved) StatusPass else StatusFail, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isApproved) "PASS" else "REJECT",
                                    color = if (isApproved) StatusPass else StatusFail,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "Batch '${completed.productName}' completed with ${completed.inspectionCount} units inspected (${completed.failCount} defects, ${String.format(Locale.US, "%.1f", completed.defectRate * 100f)}% defect rate). Certified QR code generated and saved to Log History and Passport Screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            fontSize = 12.sp
                        )

                        PrimaryActionButton(
                            text = "VIEW BATCH QR & PASSPORT ↗",
                            onClick = { onViewBatchDetail(completed.batchId) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Connected Inspection Nodes Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONNECTED INSPECTION NODES (${devices.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "📡", fontSize = 24.sp)
                        Text(
                            text = "Waiting for Nodes to Register",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "On Phone 2 & Phone 3, select Node Mode and enter IP: $serverIp Port: $serverPort",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGrey,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.forEach { dev ->
                        val isOnline = dev.isOnline()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CardSurfaceDark)
                                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (isOnline) StatusPass else StatusFail)
                                        )
                                        Text(
                                            text = dev.deviceName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TextWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (dev.isInspecting) Color(0xFF10B981).copy(alpha = 0.15f) else Color.DarkGray)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (dev.isInspecting) "INSPECTING" else if (isOnline) "IDLE" else "OFFLINE",
                                            color = if (dev.isInspecting) StatusPass else TextGrey,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "ID: ${dev.deviceId} (${dev.clientIp})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextGrey,
                                        fontSize = 11.sp
                                    )
                                    if (dev.isInspecting) {
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", dev.fps)} FPS · ${dev.inferenceLatencyMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandBlueLight,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Aggregated QC Metrics
            Text(
                text = "NETWORK AGGREGATE TOTALS",
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlueLight,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 4-metric grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    title = "TOTAL",
                    value = summary.totalInspected.toString(),
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "PASSED",
                    value = summary.passedCount.toString(),
                    color = StatusPass,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "DEFECTS",
                    value = summary.defectiveCount.toString(),
                    color = StatusFail,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "RATE",
                    value = "${String.format(Locale.US, "%.1f", summary.defectRate)}%",
                    color = if (summary.defectRate <= 12f) StatusPass else StatusFail,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-Station Streams & Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Stream 1: 2D Biscuit Defect Sort Stream
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔍 STATION 1: 2D BISCUIT DEFECT SORT",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandBlueLight,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${summary.count2dTotal} units",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "✓ Intact: ${summary.intactCount}",
                                color = StatusPass,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "✗ Broken: ${summary.brokenCount}",
                                color = if (summary.brokenCount > 0) StatusFail else TextGrey,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "⚠ Fracture: ${summary.fractureCount}",
                                color = if (summary.fractureCount > 0) Color(0xFFFBBF24) else TextGrey,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val d2dRate = if (summary.count2dTotal > 0) (summary.count2dDefect.toFloat() / summary.count2dTotal.toFloat()) * 100f else 0f
                        Text(
                            text = "2D Defect Rate: ${String.format(Locale.US, "%.1f", d2dRate)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d2dRate <= 12f) StatusPass else StatusFail,
                            fontSize = 11.sp
                        )
                    }
                }

                // Stream 2: 3D Volume & Fill Check Stream
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardSurfaceDark)
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🧪 STATION 2: 3D VOLUME & FILL-LEVEL",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF26B6FF),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${summary.count3dTotal} bottles",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "✓ In Tolerance: ${summary.count3dPass}",
                                color = StatusPass,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "✗ Out of Tolerance: ${summary.count3dDefect}",
                                color = if (summary.count3dDefect > 0) StatusFail else TextGrey,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (summary.count3dInconclusive > 0) {
                                Text(
                                    text = "⚠ Inconclusive: ${summary.count3dInconclusive}",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        val p3dRate = if (summary.count3dTotal > 0) (summary.count3dPass.toFloat() / summary.count3dTotal.toFloat()) * 100f else 0f
                        Text(
                            text = "3D Pass Rate: ${String.format(Locale.US, "%.1f", p3dRate)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p3dRate >= 88f) StatusPass else Color(0xFFFBBF24),
                            fontSize = 11.sp
                        )
                    }
                }

                // Station Contributions
                if (summary.deviceCounts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardSurfaceDark)
                            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "STATION NODE CONTRIBUTIONS:",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandBlueLight,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            summary.deviceCounts.forEach { (stn, cnt) ->
                                val defects = summary.deviceDefectCounts[stn] ?: 0
                                val mode = summary.deviceModes[stn]?.let {
                                    if (it.contains("VOLUME", ignoreCase = true) || it.contains("3D", ignoreCase = true)) "3D Volume"
                                    else if (it.contains("UV", ignoreCase = true)) "UV Scan"
                                    else "2D Defect"
                                } ?: "Node"

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF1E2838))
                                                .border(1.dp, BrandBlueLight.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = mode, color = BrandBlueLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(text = stn, style = MaterialTheme.typography.bodySmall, color = TextWhite, fontWeight = FontWeight.SemiBold)
                                    }

                                    Text(
                                        text = "$cnt items · $defects def",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (defects > 0) StatusFail else StatusPass,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. Live Inspection Event Feed
            Text(
                text = "LIVE INSPECTION EVENTS (${recentEvents.size})",
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlueLight,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (recentEvents.isEmpty()) {
                Text(
                    text = "No events received yet. Start the batch on Server and inspection on Nodes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey
                )
            } else {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentEvents.take(15).forEach { ev ->
                        val isDef = ev.isDefect
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardSurfaceDark)
                                .border(1.dp, if (isDef) StatusFail.copy(alpha = 0.3f) else CardSurfaceBorder, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val modeTag = when {
                                            ev.inspectionMode.contains("VOLUME", ignoreCase = true) || ev.inspectionMode.contains("3D", ignoreCase = true) || ev.modelId.contains("fill", ignoreCase = true) -> "3D"
                                            ev.inspectionMode.contains("UV", ignoreCase = true) -> "UV"
                                            else -> "2D"
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (modeTag == "3D") Color(0xFF0F2636) else Color(0xFF192538))
                                                .border(1.dp, if (modeTag == "3D") Color(0xFF26B6FF) else BrandBlueLight, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(text = modeTag, color = if (modeTag == "3D") Color(0xFF26B6FF) else BrandBlueLight, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(
                                            text = ev.className,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isDef) StatusFail else StatusPass,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "(${String.format(Locale.US, "%.0f", ev.confidence * 100)}%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextGrey,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = "${ev.stationName} · Item: ${ev.itemId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextGrey,
                                        fontSize = 11.sp
                                    )
                                }

                                Text(
                                    text = timeFormat.format(Date(ev.clientTimestampUtc)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandBlueLight,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showCreateBatchDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBatchDialog = false },
            title = {
                Text(
                    text = "Create Shared Inspection Batch",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the Lot or Product Name for all connected inspection stations:",
                        color = TextGrey,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newBatchLotName,
                        onValueChange = { newBatchLotName = it },
                        label = { Text("Lot / Batch Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBluePrimary,
                            unfocusedBorderColor = CardSurfaceBorder,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Inspection Scope:",
                        color = TextWhite,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Multi-Station Network: Station 1 inspects 2D biscuit defects, Station 2 inspects 3D bottle volume. Server collects, aggregates, and analyzes both streams simultaneously.",
                        color = BrandBlueLight,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "COMBINED_INSPECTION" to "Combined (2D + 3D)",
                            "2D_DEFECT_SORT" to "2D Only",
                            "VOLUME_CHECK_3D" to "3D Only",
                            "UV_AFLATOXIN" to "UV Only"
                        ).forEach { (modeKey, modeLabel) ->
                            val isSel = newBatchMode == modeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) BrandBluePrimary else Color(0xFF1E2430))
                                    .border(1.dp, if (isSel) BrandBlueLight else CardSurfaceBorder, RoundedCornerShape(8.dp))
                                    .clickable { newBatchMode = modeKey }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = modeLabel,
                                    color = if (isSel) TextWhite else TextGrey,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBatchLotName.trim().ifBlank {
                            when (newBatchMode) {
                                "UV_AFLATOXIN" -> "Chilli Lot #101"
                                "VOLUME_CHECK_3D" -> "Bottle Lot #101"
                                "COMBINED_INSPECTION" -> "Combined QC Lot #101"
                                else -> "Biscuit Lot #101"
                            }
                        }
                        serverManager.createBatch(name, newBatchMode)
                        showCreateBatchDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBluePrimary)
                ) {
                    Text("Create Batch", color = TextWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBatchDialog = false }) {
                    Text("Cancel", color = TextGrey)
                }
            },
            containerColor = CardSurfaceDark
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Text(
                    text = "Reset Distributed Inspection Data?",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will erase all recorded batches, registered nodes, and defect event logs stored on this phone, giving you a fresh clean slate.",
                    color = TextGrey,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        serverManager.clearAllData()
                        showResetConfirmDialog = false
                        Toast.makeText(context, "All distributed data cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusFail)
                ) {
                    Text("Clear All Data", color = TextWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel", color = TextGrey)
                }
            },
            containerColor = CardSurfaceDark
        )
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextGrey,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
