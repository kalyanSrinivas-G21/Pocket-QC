package com.pocketqc.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.utils.Formatters
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.components.MetricCard
import com.pocketqc.ui.components.PrimaryActionButton
import com.pocketqc.ui.components.QrCodeView
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
import java.util.Locale

/**
 * Batch Detail Screen:
 * Displays full quantitative audit breakdown for a completed batch and
 * prominently renders a standard, machine-readable QR code scannable by Google Lens.
 */
@Composable
fun BatchDetailScreen(
    batch: Batch?,
    onReturnHome: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (batch == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Batch Not Found",
            onNavigateBack = onNavigateBack
        )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "NO BATCH AVAILABLE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusFail
                    )
                    Text(
                        text = "No batch record was found for this identifier. Start a new inspection session to generate audit logs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextGrey,
                        textAlign = TextAlign.Center
                    )
                }
            }

            PrimaryActionButton(
                text = "RETURN TO HOME",
                onClick = onReturnHome,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    val scrollState = rememberScrollState()
    val isApproved = batch.status == BatchStatus.APPROVED

    val resolvedQrPayload = if (batch.qrPayload.isNotBlank()) {
        batch.qrPayload
    } else {
        QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = batch.batchId,
            type = when (batch.mode) {
                InspectionMode.DEFECT_SORT_2D -> "2D"
                InspectionMode.UV_AFLATOXIN -> "UV"
                InspectionMode.VOLUME_CHECK_3D -> "3D"
            },
            total = batch.inspectionCount,
            pass = batch.passCount,
            defectOrFlagged = batch.failCount,
            rate = batch.defectRate * 100f,
            result = if (isApproved) "PASS" else "REJECT"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("Batch ") }
            withStyle(SpanStyle(color = BrandBluePrimary)) { append("Details") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Batch Details",
            annotatedTitle = annotatedTitle,
            subtitle = batch.batchId,
            onNavigateBack = onNavigateBack,
            actions = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isApproved) Color(0xFF0F2E1E) else Color(0xFF381010))
                        .border(1.dp, if (isApproved) StatusPass else StatusFail, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isApproved) "PASS" else "REJECT",
                        color = if (isApproved) StatusPass else StatusFail,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        )

        // Prominent Scanner-Compatible QR Code Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardSurfaceDark)
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "DIGITAL BATCH PASSPORT (QR)",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.Bold
                )

                QrCodeView(
                    payload = resolvedQrPayload,
                    modifier = Modifier.size(220.dp)
                )

                Text(
                    text = "Scan with any phone camera or Google Lens to view certified batch audit directly at qc-phone.vercel.app",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGrey,
                    textAlign = TextAlign.Center
                )

                val context = androidx.compose.ui.platform.LocalContext.current
                val webUrl = androidx.compose.runtime.remember(resolvedQrPayload) {
                    QrCodeGenerator.resolveScannableUrl(resolvedQrPayload)
                }

                SecondaryActionButton(
                    text = "OPEN PASSPORT ON WEB ↗",
                    contentColor = BrandBlueLight,
                    borderColor = BrandBlueLight.copy(alpha = 0.5f),
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webUrl)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Aggregate Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Total",
                value = "${batch.inspectionCount}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Passed",
                value = "${batch.passCount}",
                highlightColor = StatusPass,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defect/Flag",
                value = "${batch.failCount}",
                highlightColor = if (batch.failCount > 0) StatusFail else TextWhite,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defect %",
                value = String.format(Locale.US, "%.1f%%", batch.defectRate * 100f),
                highlightColor = if (batch.defectRate > 0.1f) StatusFail else BrandBlueLight,
                modifier = Modifier.weight(1f)
            )
        }

        // Mode-Specific Breakdown Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardSurfaceDark)
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "MODE BREAKDOWN (${batch.mode.titleLine1} ${batch.mode.titleLine2})",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.Bold
                )

                when (batch.mode) {
                    InspectionMode.DEFECT_SORT_2D -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Intact (Passed):", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.intactCount}", color = StatusPass, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Broken Edges:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.brokenCount}", color = StatusFail, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Fracture Lines:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.fractureCount}", color = StatusFail, fontWeight = FontWeight.Bold)
                        }
                    }
                    InspectionMode.UV_AFLATOXIN -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Passed Samples:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.passCount}", color = StatusPass, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Fluorescence Flagged:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.flaggedCount}", color = StatusFail, fontWeight = FontWeight.Bold)
                        }
                    }
                    InspectionMode.VOLUME_CHECK_3D -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Target Fill Level:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.requiredFillPercent.toInt()}%", color = BrandBlueLight, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Avg Detected Fill:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = String.format(Locale.US, "%.1f%%", batch.averageDetectedFillPercent), color = TextWhite, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Passed Bottles:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.passCount}", color = StatusPass, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "Rejected Bottles:", color = TextWhite, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${batch.failCount}", color = StatusFail, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Inspection Timestamp:", color = TextGrey, style = MaterialTheme.typography.labelSmall)
                    Text(text = Formatters.formatTimestamp(batch.timestamp), color = TextWhite, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Primary Action
        PrimaryActionButton(
            text = "Return to Dashboard",
            onClick = onReturnHome,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
