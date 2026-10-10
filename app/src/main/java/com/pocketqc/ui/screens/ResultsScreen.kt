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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.core.utils.Formatters
import com.pocketqc.ui.components.InspectionStatusBadge
import com.pocketqc.ui.components.MetricCard
import com.pocketqc.ui.components.PrimaryActionButton
import com.pocketqc.ui.components.SecondaryActionButton
import com.pocketqc.ui.components.SectionHeader
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.StatusPassBg
import com.pocketqc.ui.theme.StatusPassBorder
import com.pocketqc.ui.theme.StatusWarning
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun ResultsScreen(
    result: InspectionResult,
    onGeneratePassport: () -> Unit,
    onInspectMore: () -> Unit,
    onReturnHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val annotatedTitle = buildAnnotatedString {
                    withStyle(SpanStyle(color = TextWhite)) { append("Pocket ") }
                    withStyle(SpanStyle(color = BrandBluePrimary)) { append("QC") }
                }
                Text(
                    text = annotatedTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
                Text(
                    text = "Quality Verification Report",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextGrey
                )
            }

            InspectionStatusBadge(status = result.status)
        }

        // Dominant Overall QC Verdict Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (result.status == InspectionStatus.PASSED) StatusPassBg else Color(0xFF381010))
                .border(
                    width = 1.5.dp,
                    color = if (result.status == InspectionStatus.PASSED) StatusPassBorder else StatusFail,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "QUALITY RESULT VERDICT",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.status == InspectionStatus.PASSED) StatusPass else StatusFail,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = if (result.status == InspectionStatus.PASSED) "PASS — TOLERANCE MET" else "REVIEW REQUIRED",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = TextWhite
                )

                Text(
                    text = "Product: ${result.productName} · Batch #B-2026-024\n" +
                            "Inspection timestamp: ${Formatters.formatTimestamp(result.timestamp)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextGrey,
                    lineHeight = 18.sp
                )
            }
        }

        // Quantitative Metrics Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "Evaluated",
                value = "${result.totalInspected}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Accepted",
                value = "${result.passed}",
                highlightColor = StatusPass,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defective",
                value = "${result.failed}",
                highlightColor = StatusFail,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Defect Rate",
                value = Formatters.formatPercent(result.defectRate),
                highlightColor = if (result.defectRate <= 0.15f) StatusWarning else StatusFail,
                modifier = Modifier.weight(1.2f)
            )
        }

        // Classified Defect Breakdown List
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(
                title = "Defect Categories",
                subtitle = "Classification breakdown from on-device inspection."
            )

            val defectGroups = result.detections.groupBy { it.className }
            if (defectGroups.isEmpty()) {
                DefectClassCard(
                    category = "Zero Defects Detected",
                    count = 0,
                    percentage = "0.0%",
                    severity = "Nominal"
                )
            } else {
                defectGroups.forEach { (category, detections) ->
                    val count = detections.size
                    val percent = if (result.totalInspected > 0) count.toFloat() / result.totalInspected.toFloat() else 0f
                    val severity = when (category) {
                        "Broken Edge" -> "Minor"
                        "Burnt Surface" -> "Moderate"
                        "Deformed Contour" -> "Moderate"
                        else -> "Minor"
                    }
                    DefectClassCard(
                        category = category,
                        count = count,
                        percentage = Formatters.formatPercent(percent),
                        severity = severity
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Primary Action: View Batch Passport
        PrimaryActionButton(
            text = "View Batch Passport",
            onClick = onGeneratePassport,
            leadingIcon = {
                Text(text = "★", color = TextWhite, fontSize = 15.sp)
            }
        )

        // Secondary Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryActionButton(
                text = "Inspect More",
                onClick = onInspectMore,
                modifier = Modifier.weight(1f)
            )

            SecondaryActionButton(
                text = "Return Home",
                onClick = onReturnHome,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DefectClassCard(
    category: String,
    count: Int,
    percentage: String,
    severity: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = category,
                style = MaterialTheme.typography.titleMedium,
                color = TextWhite,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Severity: $severity",
                style = MaterialTheme.typography.labelSmall,
                color = TextGrey
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$count units",
                style = MaterialTheme.typography.labelMedium,
                color = StatusFail,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1B1E22))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = percentage,
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandBlueLight,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
