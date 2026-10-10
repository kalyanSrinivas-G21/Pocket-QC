package com.pocketqc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pocketqc.inference.DiagnosticReport
import com.pocketqc.inference.StaticModelDiagnostic
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.CardSurfaceDark
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.StatusPass
import com.pocketqc.ui.theme.TextGrey
import com.pocketqc.ui.theme.TextWhite
import java.util.Locale

@Composable
fun ModelDiagnosticDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            report = StaticModelDiagnostic.runDiagnostic(context)
        } catch (e: Exception) {
            errorMsg = e.message ?: "Unknown diagnostic failure"
        } finally {
            isLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardSurfaceDark,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "YOLOv8n Static Diagnostic",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "ONNX Runtime Mobile (CPU Baseline)",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandBluePrimary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator(
                        color = BrandBluePrimary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading model & executing inference...",
                        color = TextGrey,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (errorMsg != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusFail.copy(alpha = 0.15f))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Error: $errorMsg",
                            color = StatusFail,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else if (report != null) {
                    val r = report!!
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusPass.copy(alpha = 0.12f))
                            .border(1.dp, StatusPass.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Model: ${r.modelName}",
                                color = TextWhite,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Status: ${r.successfulInferences}/${r.totalSamplesTested} Inferences Passed (${r.averageInferenceMs} ms avg)",
                                color = StatusPass,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    r.results.forEach { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBg)
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.assetFileName,
                                        color = TextWhite,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val statusColor = if (item.isSuccess) StatusPass else StatusFail
                                    Text(
                                        text = if (item.isSuccess) "PASS" else "FAIL",
                                        color = statusColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                val detText = if (item.detections.isNotEmpty()) {
                                    item.detections.joinToString { d ->
                                        "${d.className} (${String.format(Locale.US, "%.1f", d.confidence * 100)}%)"
                                    }
                                } else {
                                    "No detections"
                                }

                                Text(
                                    text = "Detections: $detText",
                                    color = BrandBluePrimary,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "Latency: ${item.benchmark.inferenceTimeMs} ms (Prep: ${item.benchmark.preprocessTimeMs}ms, Post: ${item.benchmark.postprocessTimeMs}ms)",
                                    color = TextGrey,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                PrimaryActionButton(
                    text = "Close Diagnostic",
                    onClick = onDismiss
                )
            }
        }
    }
}
