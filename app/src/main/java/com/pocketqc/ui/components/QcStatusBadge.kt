package com.pocketqc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.ui.theme.BrandBlueContainer
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusFailBg
import com.pocketqc.ui.theme.StatusFailBorder
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.StatusPassBg
import com.pocketqc.ui.theme.StatusPassBorder
import com.pocketqc.ui.theme.StatusWarning
import com.pocketqc.ui.theme.StatusWarningBg
import com.pocketqc.ui.theme.StatusWarningBorder
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun InspectionStatusBadge(
    status: InspectionStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, borderColor, textColor, text) = when (status) {
        InspectionStatus.PASSED -> StatusConfig(StatusPassBg, StatusPassBorder, StatusPass, "PASSED")
        InspectionStatus.FAILED -> StatusConfig(StatusFailBg, StatusFailBorder, StatusFail, "FAILED")
        InspectionStatus.WARNING -> StatusConfig(StatusWarningBg, StatusWarningBorder, StatusWarning, "WARNING")
        InspectionStatus.PENDING -> StatusConfig(CardSurfaceDark, CardSurfaceBorder, TextGrey, "PENDING")
    }

    StatusPill(bgColor = bgColor, borderColor = borderColor, textColor = textColor, text = text, modifier = modifier)
}

@Composable
fun BatchStatusBadge(
    status: BatchStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, borderColor, textColor, text) = when (status) {
        BatchStatus.APPROVED -> StatusConfig(StatusPassBg, StatusPassBorder, StatusPass, "APPROVED")
        BatchStatus.REJECTED -> StatusConfig(StatusFailBg, StatusFailBorder, StatusFail, "REJECTED")
        BatchStatus.IN_PROGRESS -> StatusConfig(BrandBlueContainer, BrandBluePrimary, BrandBlueLight, "IN PROGRESS")
        BatchStatus.COMPLETED -> StatusConfig(CardSurfaceDark, CardSurfaceBorder, TextWhite, "COMPLETED")
    }

    StatusPill(bgColor = bgColor, borderColor = borderColor, textColor = textColor, text = text, modifier = modifier)
}

@Composable
private fun StatusPill(
    bgColor: Color,
    borderColor: Color,
    textColor: Color,
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class StatusConfig(val bg: Color, val border: Color, val textCol: Color, val label: String)
