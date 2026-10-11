package com.pocketqc.distributed.model

import org.json.JSONArray
import org.json.JSONObject

enum class DeviceConnectionStatus {
    ONLINE,
    OFFLINE,
    DISCONNECTED
}

/**
 * Inspection Node registered on the Server (Device 1).
 */
data class DistributedDevice(
    val deviceId: String,
    val deviceName: String,
    val sessionId: String,
    val appVersion: String = "1.0.0",
    val supportedInspectionModes: List<String> = listOf("2D_DEFECT_SORT"),
    val modelId: String = "biscuits_final",
    val modelVersion: String = "1.0",
    val capabilities: List<String> = listOf("YOLOv8n", "CameraX"),
    val registeredTimeUtc: Long = System.currentTimeMillis(),
    val lastSeenTimeUtc: Long = System.currentTimeMillis(),
    val isInspecting: Boolean = false,
    val fps: Float = 0f,
    val inferenceLatencyMs: Long = 0L,
    val queueDepth: Int = 0,
    val clientIp: String = ""
) {
    fun isOnline(timeoutMs: Long = 12000L): Boolean {
        return (System.currentTimeMillis() - lastSeenTimeUtc) < timeoutMs
    }

    val status: DeviceConnectionStatus
        get() = if (isOnline()) DeviceConnectionStatus.ONLINE else DeviceConnectionStatus.OFFLINE

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("sessionId", sessionId)
            put("appVersion", appVersion)
            put("supportedInspectionModes", JSONArray(supportedInspectionModes))
            put("modelId", modelId)
            put("modelVersion", modelVersion)
            put("capabilities", JSONArray(capabilities))
            put("registeredTimeUtc", registeredTimeUtc)
            put("lastSeenTimeUtc", lastSeenTimeUtc)
            put("isInspecting", isInspecting)
            put("fps", fps.toDouble())
            put("inferenceLatencyMs", inferenceLatencyMs)
            put("queueDepth", queueDepth)
            put("clientIp", clientIp)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DistributedDevice {
            val modes = mutableListOf<String>()
            val modesJson = json.optJSONArray("supportedInspectionModes")
            if (modesJson != null) {
                for (i in 0 until modesJson.length()) {
                    modes.add(modesJson.getString(i))
                }
            } else {
                modes.add("2D_DEFECT_SORT")
            }

            val caps = mutableListOf<String>()
            val capsJson = json.optJSONArray("capabilities")
            if (capsJson != null) {
                for (i in 0 until capsJson.length()) {
                    caps.add(capsJson.getString(i))
                }
            }

            return DistributedDevice(
                deviceId = json.getString("deviceId"),
                deviceName = json.optString("deviceName", "Inspection Node"),
                sessionId = json.getString("sessionId"),
                appVersion = json.optString("appVersion", "1.0.0"),
                supportedInspectionModes = modes,
                modelId = json.optString("modelId", "biscuits_final"),
                modelVersion = json.optString("modelVersion", "1.0"),
                capabilities = caps,
                registeredTimeUtc = json.optLong("registeredTimeUtc", System.currentTimeMillis()),
                lastSeenTimeUtc = json.optLong("lastSeenTimeUtc", System.currentTimeMillis()),
                isInspecting = json.optBoolean("isInspecting", false),
                fps = json.optDouble("fps", 0.0).toFloat(),
                inferenceLatencyMs = json.optLong("inferenceLatencyMs", 0L),
                queueDepth = json.optInt("queueDepth", 0),
                clientIp = json.optString("clientIp", "")
            )
        }
    }
}
