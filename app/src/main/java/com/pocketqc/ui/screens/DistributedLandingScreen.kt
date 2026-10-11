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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.R
import com.pocketqc.ui.components.PocketQcTopBar
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
fun DistributedLandingScreen(
    onSelectServerMode: () -> Unit,
    onSelectNodeMode: () -> Unit,
    onSelectStandaloneMode: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .statusBarsPadding()
    ) {
        PocketQcTopBar(
            title = "Distributed Inspection",
            onNavigateBack = onNavigateBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardSurfaceDark)
                    .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(StatusPass)
                        )
                        Text(
                            text = "AIR-GAPPED LOCAL WI-FI MESH",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandBlueLight,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Text(
                        text = "3-Phone Industrial QC Network",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Coordinate multiple independent Pocket QC stations over your local Wi-Fi or phone hotspot. No internet, cloud accounts, or external servers required.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextGrey,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "SELECT OPERATING MODE",
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlueLight,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Mode A: Server Mode (Device 1)
            ModeCard(
                tag = "DEVICE 1",
                tagColor = BrandBluePrimary,
                title = "Server Mode",
                subtitle = "Host the inspection network, manage shared batches, and view real-time aggregated telemetry across all lines.",
                icon = {
                    Text(text = "🖥️", fontSize = 22.sp)
                },
                badgeText = "CENTRAL HUB",
                onClick = onSelectServerMode
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Mode B: Inspection Node Mode (Devices 2 & 3)
            ModeCard(
                tag = "DEVICES 2 & 3",
                tagColor = Color(0xFF10B981),
                title = "Inspection Node Mode",
                subtitle = "Execute real-time CameraX + YOLOv8n defect detection. Track physical items and stream certified records to the server.",
                icon = {
                    Text(text = "📱", fontSize = 22.sp)
                },
                badgeText = "INSPECTION STATION",
                onClick = onSelectNodeMode
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Mode C: Standalone Mode
            ModeCard(
                tag = "LOCAL ONLY",
                tagColor = Color(0xFFA78BFA),
                title = "Standalone Mode",
                subtitle = "Operate independently without network synchronization. Full 2D conveyor inspection, local passports, and history.",
                icon = {
                    Text(text = "🔒", fontSize = 22.sp)
                },
                badgeText = "STANDALONE",
                onClick = onSelectStandaloneMode
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ModeCard(
    tag: String,
    tagColor: Color,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    badgeText: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurfaceDark)
            .border(1.dp, CardSurfaceBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF161B26)),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }

                    Column {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = tagColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tagColor.copy(alpha = 0.15f))
                        .border(1.dp, tagColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = tagColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextGrey,
                lineHeight = 18.sp
            )
        }
    }
}
