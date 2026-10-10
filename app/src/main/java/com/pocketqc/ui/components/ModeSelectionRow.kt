package com.pocketqc.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.R
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSelectedBorder
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

enum class InspectionMode(
    val titleLine1: String,
    val titleLine2: String,
    @param:DrawableRes val iconRes: Int
) {
    DEFECT_SORT_2D("2D Defect", "Sort", R.drawable.ic_mode_2d_defect),
    UV_AFLATOXIN("UV Aflatoxin", "Scan", R.drawable.ic_mode_uv_aflatoxin),
    VOLUME_CHECK_3D("3D Volume", "Check", R.drawable.ic_mode_3d_volume)
}

@Composable
fun ModeSelectionRow(
    selectedMode: InspectionMode,
    onModeSelected: (InspectionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InspectionMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            ModeCardItem(
                mode = mode,
                isSelected = isSelected,
                onClick = { onModeSelected(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeCardItem(
    mode: InspectionMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = com.pocketqc.ui.theme.LocalPocketQcColors.current.isDark
    val cardBackground = if (isDark) {
        if (isSelected) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF142442),
                    CardSurfaceDark
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    CardSurfaceDark,
                    Color(0xFF1C1D21)
                )
            )
        }
    } else {
        if (isSelected) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFFE0EDFF),
                    CardSurfaceDark
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    CardSurfaceDark,
                    Color(0xFFF1F5F9)
                )
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(cardBackground)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) CardSelectedBorder else CardSurfaceBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Selection glowing indicator dot
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(BrandBlueLight)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(id = mode.iconRes),
                contentDescription = "${mode.titleLine1} ${mode.titleLine2}",
                modifier = Modifier.size(54.dp),
                contentScale = ContentScale.Fit
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = mode.titleLine1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) TextWhite else TextGrey,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = mode.titleLine2,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) BrandBlueLight else Color(0xFF6B7A8C),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
