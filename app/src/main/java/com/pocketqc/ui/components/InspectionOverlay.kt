package com.pocketqc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketqc.core.model.Detection
import com.pocketqc.core.utils.Formatters
import com.pocketqc.ui.theme.BrandBluePrimary
import com.pocketqc.ui.theme.StatusFail
import com.pocketqc.ui.theme.TextWhite
import kotlin.math.roundToInt

@Composable
fun InspectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // High-precision HUD Viewfinder Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.5.dp.toPx()
            val bracketColor = BrandBluePrimary
            val cornerLength = 28.dp.toPx()

            // Viewport boundary with corner accents
            val inset = 18.dp.toPx()
            val w = size.width - inset * 2
            val h = size.height - inset * 2

            // Top-left bracket
            drawLine(bracketColor, Offset(inset, inset), Offset(inset + cornerLength, inset), strokeWidth)
            drawLine(bracketColor, Offset(inset, inset), Offset(inset, inset + cornerLength), strokeWidth)

            // Top-right bracket
            drawLine(bracketColor, Offset(inset + w, inset), Offset(inset + w - cornerLength, inset), strokeWidth)
            drawLine(bracketColor, Offset(inset + w, inset), Offset(inset + w, inset + cornerLength), strokeWidth)

            // Bottom-left bracket
            drawLine(bracketColor, Offset(inset, inset + h), Offset(inset + cornerLength, inset + h), strokeWidth)
            drawLine(bracketColor, Offset(inset, inset + h), Offset(inset, inset + h - cornerLength), strokeWidth)

            // Bottom-right bracket
            drawLine(bracketColor, Offset(inset + w, inset + h), Offset(inset + w - cornerLength, inset + h), strokeWidth)
            drawLine(bracketColor, Offset(inset + w, inset + h), Offset(inset + w, inset + cornerLength), strokeWidth)

            // Center targeting reticle
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val crossSize = 14.dp.toPx()
            val reticleColor = BrandBluePrimary.copy(alpha = 0.6f)
            drawLine(reticleColor, Offset(centerX - crossSize, centerY), Offset(centerX + crossSize, centerY), 1.5.dp.toPx())
            drawLine(reticleColor, Offset(centerX, centerY - crossSize), Offset(centerX, centerY + crossSize), 1.5.dp.toPx())

            // Detection bounding boxes
            for (det in detections) {
                val box = det.boundingBox
                val leftPx = box.left * size.width
                val topPx = box.top * size.height
                val boxW = box.width * size.width
                val boxH = box.height * size.height

                val isIntact = det.className.equals("Intact", ignoreCase = true)
                val boxColor = if (isIntact) com.pocketqc.ui.theme.StatusPass else StatusFail

                // Bounding box outline
                drawRect(
                    color = boxColor,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Translucent fill
                drawRect(
                    color = boxColor.copy(alpha = 0.12f),
                    topLeft = Offset(leftPx, topPx),
                    size = Size(boxW, boxH)
                )
            }
        }

        // Detection Classification Labels positioned via constraints
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewW = maxWidth
            val viewH = maxHeight

            for (det in detections) {
                val box = det.boundingBox
                val isIntact = det.className.equals("Intact", ignoreCase = true)
                val labelColor = if (isIntact) com.pocketqc.ui.theme.StatusPass else StatusFail

                val posX = (viewW * box.left).coerceAtLeast(8.dp)
                val posY = ((viewH * box.top) - 24.dp).coerceAtLeast(8.dp)

                Box(
                    modifier = Modifier
                        .offset(x = posX, y = posY)
                        .clip(RoundedCornerShape(6.dp))
                        .background(labelColor)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${det.className} ${Formatters.formatConfidence(det.confidence)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextWhite,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
