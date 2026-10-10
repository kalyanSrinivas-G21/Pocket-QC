package com.pocketqc.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.R
import com.pocketqc.batch.BatchManager
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.components.ModeSelectionRow
import com.pocketqc.ui.components.PrimaryActionButton
import com.pocketqc.ui.components.SectionHeader
import com.pocketqc.ui.theme.BrandBlueContainer
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun HomeScreen(
    batchManager: BatchManager,
    onStartInspection: (InspectionMode) -> Unit,
    onViewPassport: () -> Unit = {},
    onNavigateHistory: () -> Unit = onViewPassport,
    onNavigateSettings: () -> Unit = {},
    onNavigateLab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedMode by remember { mutableStateOf(InspectionMode.DEFECT_SORT_2D) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // App Header Row (Brand Title, Hardware Status Badge, Settings Action)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val annotatedTitle = buildAnnotatedString {
                    withStyle(SpanStyle(color = TextWhite)) {
                        append("Pocket ")
                    }
                    withStyle(SpanStyle(color = BrandBluePrimary)) {
                        append("QC")
                    }
                }
                Text(
                    text = annotatedTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Pulsing green NPU operational dot
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StatusPass)
                    )
                    Text(
                        text = "Snapdragon 8 Elite · QNN NPU Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Circular Settings Action Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, CircleShape)
                    .clickable { onNavigateSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings),
                    contentDescription = "Operator Settings",
                    tint = TextWhite,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Hero Banner Card with crisp container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, CardSurfaceBorder, RoundedCornerShape(20.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.banner_quality_control),
                contentDescription = "Affordable Quality Control",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        // Section: "Select Inspection Mode"
        SectionHeader(
            title = "Select Inspection Mode",
            subtitle = "Choose an AI module to initiate quality screening."
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 3 Unified Mode Tiles: 2D Defect Sort | UV Aflatoxin Scan | 3D Volume Check
        ModeSelectionRow(
            selectedMode = selectedMode,
            onModeSelected = { selectedMode = it }
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Primary Action: Start Inspection Button
        PrimaryActionButton(
            text = "START ${when (selectedMode) {
                InspectionMode.DEFECT_SORT_2D -> "2D DEFECT SORT"
                InspectionMode.UV_AFLATOXIN -> "UV AFLATOXIN SCAN"
                InspectionMode.VOLUME_CHECK_3D -> "3D VOLUME CHECK"
            }}",
            onClick = { onStartInspection(selectedMode) },
            leadingIcon = {
                Text(
                    text = "▶",
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Section Header for Management Tools
        Text(
            text = "MANAGEMENT & LAB TOOLS",
            style = MaterialTheme.typography.labelSmall,
            color = BrandBlueLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Feature Action Card 1: Batch History & Passports
        DashboardActionCard(
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_list),
                    contentDescription = null,
                    tint = BrandBlueLight,
                    modifier = Modifier.size(20.dp)
                )
            },
            iconBgColor = BrandBlueContainer,
            title = "Batch History & Passports",
            subtitle = "View offline audit logs & Google Lens QR codes",
            onClick = { onNavigateHistory() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Feature Action Card 2: Experimental Model Lab
        DashboardActionCard(
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_flask),
                    contentDescription = null,
                    tint = Color(0xFFA78BFA),
                    modifier = Modifier.size(20.dp)
                )
            },
            iconBgColor = Color(0xFF261D3B),
            title = "Experimental Model Lab",
            subtitle = "On-device active learning & fine-tuning playground",
            onClick = { onNavigateLab() }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DashboardActionCard(
    icon: @Composable () -> Unit,
    iconBgColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Container
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = TextWhite
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextGrey,
                fontSize = 12.sp
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = TextGrey,
            modifier = Modifier.size(18.dp)
        )
    }
}
