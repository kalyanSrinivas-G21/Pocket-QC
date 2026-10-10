package com.pocketqc.core.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Standard, scanner-compatible QR Code generator using ZXing.
 * Produces machine-readable ISO/IEC 18004 QR codes.
 *
 * Automatically resolves batch payloads to internet-routable URLs
 * pointing directly to the Vercel-hosted Pocket QC web interface (https://qc-phone.vercel.app),
 * enabling immediate browser redirection when scanned by any standard phone camera.
 */
object QrCodeGenerator {

    const val DEFAULT_WEB_BASE_URL = "https://qc-phone.vercel.app"

    @Volatile
    var webBaseUrl: String = DEFAULT_WEB_BASE_URL

    /**
     * Standard JSON payload generator preserved for batch audit logs and unit test assertions.
     */
    fun createPayloadJson(
        app: String = "PocketQC",
        id: String,
        type: String,
        total: Int,
        pass: Int,
        defectOrFlagged: Int,
        rate: Float,
        result: String
    ): String {
        val safeRate = if (rate.isNaN() || rate.isInfinite()) 0f else rate
        return """{"app":"$app","id":"$id","type":"$type","total":$total,"pass":$pass,"defect":$defectOrFlagged,"rate":${String.format(Locale.US, "%.2f", safeRate)},"result":"$result"}"""
    }

    /**
     * Constructs a direct web redirect URL pointing to the Vercel QC Phone application.
     */
    fun createWebRedirectUrl(
        id: String,
        type: String,
        total: Int,
        pass: Int,
        defectOrFlagged: Int,
        rate: Float,
        result: String,
        baseUrl: String = webBaseUrl
    ): String {
        val cleanBase = baseUrl.trimEnd('/')
        val safeRate = if (rate.isNaN() || rate.isInfinite()) 0f else rate
        val encodedId = urlEncode(id)
        val encodedType = urlEncode(type)
        val encodedResult = urlEncode(result)
        val rateStr = String.format(Locale.US, "%.2f", safeRate)
        return "$cleanBase/?id=$encodedId&type=$encodedType&total=$total&pass=$pass&defect=$defectOrFlagged&rate=$rateStr&result=$encodedResult"
    }

    /**
     * Automatically converts any raw payload (JSON audit string, passport pipe string, or batch ID)
     * into a scannable HTTP redirect URL that smartphone cameras automatically open in a browser.
     */
    fun resolveScannableUrl(payload: String, baseUrl: String = webBaseUrl): String {
        val trimmed = payload.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        // 1. JSON audit payload format: {"app":"PocketQC","id":"PQ-...","type":"2D",...}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val id = json.optString("id", "")
                val type = json.optString("type", "2D")
                val total = json.optInt("total", 0)
                val pass = json.optInt("pass", 0)
                val defect = json.optInt("defect", json.optInt("defectOrFlagged", 0))
                val rate = json.optDouble("rate", 0.0).toFloat()
                val result = json.optString("result", "PASS")
                if (id.isNotBlank()) {
                    return createWebRedirectUrl(id, type, total, pass, defect, rate, result, baseUrl)
                }
            } catch (_: Exception) {
                // fall through to query parameter fallback
            }
        }

        // 2. Pipe-delimited passport payload format: POCKET-QC-V1|BATCH:PQ-...|...|SIG:...
        if (trimmed.contains("|") && (trimmed.contains("BATCH:") || trimmed.contains("POCKET-QC"))) {
            try {
                val parts = trimmed.split("|")
                var batchId = ""
                var prod = ""
                var total = 0
                var pass = 0
                var fail = 0
                var rate = 0f
                var sig = ""

                for (p in parts) {
                    val kv = p.split(":", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0]) {
                            "BATCH" -> batchId = kv[1]
                            "PROD" -> prod = kv[1]
                            "COUNT" -> total = kv[1].toIntOrNull() ?: 0
                            "PASS" -> pass = kv[1].toIntOrNull() ?: 0
                            "FAIL" -> fail = kv[1].toIntOrNull() ?: 0
                            "RATE" -> rate = kv[1].toFloatOrNull() ?: 0f
                            "SIG" -> sig = kv[1]
                        }
                    }
                }

                if (batchId.isNotBlank()) {
                    val cleanBase = baseUrl.trimEnd('/')
                    val encodedId = urlEncode(batchId)
                    val encodedProd = urlEncode(prod)
                    val encodedSig = urlEncode(sig)
                    val ratePercent = if (rate <= 1.0f && rate > 0f) rate * 100f else rate
                    val isPass = (fail <= 0) || (ratePercent <= 12f)
                    val resultStr = if (isPass) "PASS" else "REJECT"
                    val rateStr = String.format(Locale.US, "%.2f", ratePercent)
                    return "$cleanBase/?id=$encodedId&prod=$encodedProd&total=$total&pass=$pass&defect=$fail&rate=$rateStr&result=$resultStr&sig=$encodedSig"
                }
            } catch (_: Exception) {
                // fall through
            }
        }

        // 3. Fallback: encode batch ID or payload data
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.matches(Regex("^PQ-[0-9]{8}-[0-9]{4}$"))) {
            "$cleanBase/?id=${urlEncode(trimmed)}"
        } else {
            "$cleanBase/?data=${urlEncode(trimmed)}"
        }
    }

    /**
     * Encodes the resolved scannable URL into an ISO/IEC 18004 QR Code bitmap.
     */
    fun generateQrBitmap(payload: String, sizePx: Int = 512): Bitmap {
        val scannableContent = resolveScannableUrl(payload)
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val bitMatrix = QRCodeWriter().encode(
            scannableContent,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
