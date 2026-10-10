package com.pocketqc.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.batch.BatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.utils.Formatters
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.components.PrimaryActionButton
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
 * Batch History Redesign:
 * Prominently presents the latest completed QC batch in a hero card,
 * with scrollable access to all historical records.
 */
@Composable
fun BatchHistoryScreen(
    batchManager: BatchManager,
    onSelectBatch: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentBatch by batchManager.currentBatch.collectAsState()
    val allBatches = remember(currentBatch) { batchManager.getRecentBatches() }
    val latestBatch = allBatches.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        val annotatedTitle = buildAnnotatedString {
            withStyle(SpanStyle(color = TextWhite)) { append("Batch ") }
            withStyle(SpanStyle(color = BrandBluePrimary)) { append("History") }
        }

        com.pocketqc.ui.components.PocketQcTopBar(
            title = "Batch History",
            annotatedTitle = annotatedTitle,
            subtitle = "Local Offline Inspection Audit Log",
            onNavigateBack = onNavigateBack
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero: Latest Prominent Batch Card
            if (latestBatch != null) {
                item {
                    Text(
                        text = "LATEST INSPECTION BATCH",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FeaturedBatchCard(
                        batch = latestBatch,
                        onViewDetails = { onSelectBatch(latestBatch.batchId) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "PREVIOUS BATCHES",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Older batches
            val olderBatches = if (allBatches.size > 1) allBatches.drop(1) else emptyList()
            items(olderBatches) { batch ->
                HistoricalBatchRow(
                    batch = batch,
                    onClick = { onSelectBatch(batch.batchId) }
                )
            }

            if (allBatches.isEmpty()) {
                item {
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
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "NO BATCHES RECORDED",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "No inspection batches have been recorded yet. Start an inspection from the Home screen to generate audit logs and digital passports.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextGrey,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedBatchCard(
    batch: Batch,
    onViewDetails: () -> Unit
) {
    val isApproved = batch.status == BatchStatus.APPROVED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardSurfaceDark)
            .border(1.5.dp, if (isApproved) StatusPass.copy(alpha = 0.5f) else StatusFail.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = batch.batchId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                    Text(
                        text = "${batch.mode.titleLine1} ${batch.mode.titleLine2} · ${batch.productName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight
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

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "INSPECTED", style = MaterialTheme.typography.labelSmall, color = TextGrey)
                    Text(text = "${batch.inspectionCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextWhite)
                }
                Column {
                    Text(text = "PASSED", style = MaterialTheme.typography.labelSmall, color = TextGrey)
                    Text(text = "${batch.passCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = StatusPass)
                }
                Column {
                    Text(text = "DEFECTIVE", style = MaterialTheme.typography.labelSmall, color = TextGrey)
                    Text(text = "${batch.failCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (batch.failCount > 0) StatusFail else TextWhite)
                }
                Column {
                    Text(text = "DEFECT RATE", style = MaterialTheme.typography.labelSmall, color = TextGrey)
                    Text(text = String.format(Locale.US, "%.1f%%", batch.defectRate * 100f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (batch.defectRate > 0.1f) StatusFail else BrandBlueLight)
                }
            }

            PrimaryActionButton(
                text = "VIEW BATCH DETAILS",
                onClick = onViewDetails,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HistoricalBatchRow(
    batch: Batch,
    onClick: () -> Unit
) {
    val isApproved = batch.status == BatchStatus.APPROVED

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = batch.batchId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Text(
                    text = "${batch.mode.titleLine1} ${batch.mode.titleLine2} · ${batch.inspectionCount} units · ${Formatters.formatTimestamp(batch.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGrey
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isApproved) Color(0xFF0F2E1E) else Color(0xFF381010))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isApproved) "PASS" else "REJECT",
                    color = if (isApproved) StatusPass else StatusFail,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
