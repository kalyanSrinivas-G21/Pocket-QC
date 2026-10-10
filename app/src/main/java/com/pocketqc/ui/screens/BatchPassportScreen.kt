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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.core.utils.Formatters
import com.pocketqc.passport.BatchPassport
import com.pocketqc.ui.components.PrimaryActionButton
import com.pocketqc.ui.components.QrCodeView
import com.pocketqc.ui.components.SecondaryActionButton
import com.pocketqc.ui.theme.BrandBlueContainer
import com.pocketqc.ui.theme.BrandBlueLight
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceBorder
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.StatusPassBg
import com.pocketqc.ui.theme.StatusPassBorder
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite

@Composable
fun BatchPassportScreen(
    passport: BatchPassport,
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
                    text = "Digital Batch Passport",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextGrey
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(StatusPassBg)
                    .border(1.dp, StatusPassBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "PASSPORT ACTIVE",
                    color = StatusPass,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Industrial Quality Certificate Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardSurfaceDark)
                .border(1.dp, BrandBluePrimary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Certificate Banner Tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OFFLINE QC PASSPORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlueLight,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${passport.passportId.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextGrey,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Verifiable QR Matrix Pattern with Center Emblem
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    QrCodeView(payload = passport.qrPayload)
                }

                Text(
                    text = "Scan with any phone camera or Google Lens to view certified passport on web (qc-phone.vercel.app)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGrey,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Audit Details Table
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1B1E22))
                        .border(1.dp, CardSurfaceBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PassportDataRow(label = "Batch Number", value = passport.batchId)
                    PassportDataRow(label = "Product Name", value = passport.productName)
                    PassportDataRow(label = "Inspected Units", value = "${passport.inspectionCount} items")
                    PassportDataRow(
                        label = "Passing Rate",
                        value = Formatters.formatPercent(passport.passRate),
                        highlightColor = StatusPass
                    )
                    PassportDataRow(
                        label = "Defect Rate",
                        value = Formatters.formatPercent(passport.defectRate)
                    )
                    PassportDataRow(
                        label = "Inspection Time",
                        value = Formatters.formatTimestamp(passport.timestamp)
                    )
                    PassportDataRow(
                        label = "SHA-256 Signature",
                        value = passport.verificationHash,
                        isMonospace = true,
                        highlightColor = BrandBlueLight
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Actions
        val context = androidx.compose.ui.platform.LocalContext.current
        val webUrl = androidx.compose.runtime.remember(passport.qrPayload) {
            com.pocketqc.core.utils.QrCodeGenerator.resolveScannableUrl(passport.qrPayload)
        }

        SecondaryActionButton(
            text = "Open Web Passport (qc-phone.vercel.app) ↗",
            contentColor = BrandBlueLight,
            borderColor = BrandBlueLight.copy(alpha = 0.5f),
            onClick = {
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(webUrl)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        )

        PrimaryActionButton(
            text = "Return to Dashboard",
            onClick = onReturnHome
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PassportDataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlightColor: Color = TextWhite,
    isMonospace: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextGrey
        )
        Text(
            text = value,
            style = if (isMonospace) {
                MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = highlightColor
        )
    }
}
