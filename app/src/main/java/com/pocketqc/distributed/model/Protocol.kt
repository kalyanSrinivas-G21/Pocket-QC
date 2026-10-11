package com.pocketqc.distributed.model

import org.json.JSONObject

/**
 * Versioned protocol specifications and DTOs for Local Distributed Inspection.
 */
object DistributedProtocol {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 8080
    const val API_PREFIX = "/api/v1"

    // REST Endpoint Paths
    const val PATH_HEALTH = "$API_PREFIX/health"
    const val PATH_REGISTER = "$API_PREFIX/devices/register"
    const val PATH_HEARTBEAT = "$API_PREFIX/devices/heartbeat"
    const val PATH_CURRENT_BATCH = "$API_PREFIX/batches/current"
    const val PATH_BATCHES = "$API_PREFIX/batches"
    const val PATH_INSPECTION_EVENTS = "$API_PREFIX/inspection-events"
    const val PATH_WS = "/ws"

    // WebSocket Event Types
    const val WS_DEVICE_REGISTERED = "DEVICE_REGISTERED"
    const val WS_DEVICE_HEARTBEAT = "DEVICE_HEARTBEAT"
    const val WS_BATCH_CREATED = "BATCH_CREATED"
    const val WS_BATCH_STARTED = "BATCH_STARTED"
    const val WS_BATCH_STOPPED = "BATCH_STOPPED"
    const val WS_EVENT_ACCEPTED = "EVENT_ACCEPTED"
    const val WS_COUNTS_UPDATED = "COUNTS_UPDATED"
}

data class HealthResponse(
    val status: String = "UP",
    val protocolVersion: Int = DistributedProtocol.PROTOCOL_VERSION,
    val serverUptimeMs: Long,
    val serverTimeUtc: Long = System.currentTimeMillis(),
    val activeBatchId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("protocolVersion", protocolVersion)
        put("serverUptimeMs", serverUptimeMs)
        put("serverTimeUtc", serverTimeUtc)
        if (activeBatchId != null) put("activeBatchId", activeBatchId)
    }
}

data class RegisterRequest(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String = "1.0.0",
    val supportedInspectionModes: List<String> = listOf("2D_DEFECT_SORT"),
    val modelId: String = "biscuits_final",
    val modelVersion: String = "1.0",
    val capabilities: List<String> = listOf("YOLOv8n", "CameraX"),
    val protocolVersion: Int = DistributedProtocol.PROTOCOL_VERSION
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("appVersion", appVersion)
        put("supportedInspectionModes", supportedInspectionModes)
        put("modelId", modelId)
        put("modelVersion", modelVersion)
        put("capabilities", capabilities)
        put("protocolVersion", protocolVersion)
    }

    companion object {
        fun fromJson(json: JSONObject): RegisterRequest {
            return RegisterRequest(
                deviceId = json.getString("deviceId"),
                deviceName = json.optString("deviceName", "Inspection Node"),
                appVersion = json.optString("appVersion", "1.0.0"),
                modelId = json.optString("modelId", "biscuits_final"),
                modelVersion = json.optString("modelVersion", "1.0"),
                protocolVersion = json.optInt("protocolVersion", DistributedProtocol.PROTOCOL_VERSION)
            )
        }
    }
}

data class RegisterResponse(
    val success: Boolean,
    val sessionId: String? = null,
    val activeBatch: DistributedBatch? = null,
    val message: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        if (sessionId != null) put("sessionId", sessionId)
        if (activeBatch != null) put("activeBatch", activeBatch.toJson())
        put("message", message)
    }
}

data class HeartbeatRequest(
    val deviceId: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isInspecting: Boolean = false,
    val fps: Float = 0f,
    val inferenceLatencyMs: Long = 0L,
    val queueDepth: Int = 0,
    val networkStatus: String = "ONLINE"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("sessionId", sessionId)
        put("timestamp", timestamp)
        put("isInspecting", isInspecting)
        put("fps", fps.toDouble())
        put("inferenceLatencyMs", inferenceLatencyMs)
        put("queueDepth", queueDepth)
        put("networkStatus", networkStatus)
    }

    companion object {
        fun fromJson(json: JSONObject): HeartbeatRequest {
            return HeartbeatRequest(
                deviceId = json.getString("deviceId"),
                sessionId = json.getString("sessionId"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                isInspecting = json.optBoolean("isInspecting", false),
                fps = json.optDouble("fps", 0.0).toFloat(),
                inferenceLatencyMs = json.optLong("inferenceLatencyMs", 0L),
                queueDepth = json.optInt("queueDepth", 0),
                networkStatus = json.optString("networkStatus", "ONLINE")
            )
        }
    }
}

data class HeartbeatResponse(
    val success: Boolean,
    val activeBatch: DistributedBatch? = null,
    val message: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("success", success)
        if (activeBatch != null) put("activeBatch", activeBatch.toJson())
        put("message", message)
    }
}

data class EventSubmissionResponse(
    val accepted: Boolean,
    val isDuplicate: Boolean = false,
    val eventId: String,
    val message: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("accepted", accepted)
        put("isDuplicate", isDuplicate)
        put("eventId", eventId)
        put("message", message)
    }
}
