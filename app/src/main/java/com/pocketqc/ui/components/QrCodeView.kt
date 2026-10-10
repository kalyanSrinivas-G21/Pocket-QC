package com.pocketqc.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.ui.theme.BrandBluePrimary

/**
 * Standard, scanner-compatible QR View.
 * Encodes actual machine-readable JSON data using ZXing ISO/IEC 18004.
 * Readable by Google Lens and any smartphone camera.
 */
@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier
) {
    val qrBitmap = remember(payload) {
        runCatching {
            QrCodeGenerator.generateQrBitmap(payload, sizePx = 512)
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(2.dp, BrandBluePrimary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Standard Scannable QR Code",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
