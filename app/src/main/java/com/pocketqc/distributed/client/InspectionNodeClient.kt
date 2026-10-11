package com.pocketqc.distributed.client

import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedProtocol
import com.pocketqc.distributed.model.EventSubmissionResponse
import com.pocketqc.distributed.model.HealthResponse
import com.pocketqc.distributed.model.HeartbeatRequest
import com.pocketqc.distributed.model.HeartbeatResponse
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.model.RegisterRequest
import com.pocketqc.distributed.model.RegisterResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for communicating with the Pocket QC Inspection Server (Device 1).
 * Uses OkHttp for HTTP REST calls and WebSocket connection.
 */
class InspectionNodeClient(
    rawHost: String,
    rawPort: Int = DistributedProtocol.DEFAULT_PORT,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    val serverHost: String
    val serverPort: Int

    init {
        val (normHost, normPort) = com.pocketqc.distributed.server.NetworkUtils.normalizeHostAndPort(rawHost, rawPort)
        serverHost = normHost
        serverPort = normPort
    }

    private val baseUrl = "http://$serverHost:$serverPort"
    private val wsUrl = "ws://$serverHost:$serverPort${DistributedProtocol.PATH_WS}"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private var webSocket: WebSocket? = null

    fun checkHealth(): Result<HealthResponse> {
        return try {
            val req = Request.Builder()
                .url("$baseUrl${DistributedProtocol.PATH_HEALTH}")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    Result.success(
                        HealthResponse(
                            status = json.optString("status", "UP"),
                            protocolVersion = json.optInt("protocolVersion", 1),
                            serverUptimeMs = json.optLong("serverUptimeMs", 0L),
                            serverTimeUtc = json.optLong("serverTimeUtc", System.currentTimeMillis()),
                            activeBatchId = if (json.has("activeBatchId")) json.getString("activeBatchId") else null
                        )
                    )
                } else {
                    Result.failure(IOException("Health check failed with HTTP ${resp.code}"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun register(request: RegisterRequest): Result<RegisterResponse> {
        return try {
            val body = request.toJson().toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$baseUrl${DistributedProtocol.PATH_REGISTER}")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val json = JSONObject(respBody)
                val success = json.optBoolean("success", resp.isSuccessful)
                val sessionId = if (json.has("sessionId")) json.getString("sessionId") else null
                val batchObj = json.optJSONObject("activeBatch")
                val activeBatch = if (batchObj != null) DistributedBatch.fromJson(batchObj) else null
                val msg = json.optString("message", "")

                if (success && sessionId != null) {
                    Result.success(RegisterResponse(success = true, sessionId = sessionId, activeBatch = activeBatch, message = msg))
                } else {
                    Result.failure(IOException(if (msg.isNotBlank()) msg else "Registration rejected by server (HTTP ${resp.code})"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun sendHeartbeat(request: HeartbeatRequest): Result<HeartbeatResponse> {
        return try {
            val body = request.toJson().toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$baseUrl${DistributedProtocol.PATH_HEARTBEAT}")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val json = JSONObject(respBody)
                val success = json.optBoolean("success", resp.isSuccessful)
                val batchObj = json.optJSONObject("activeBatch")
                val activeBatch = if (batchObj != null) DistributedBatch.fromJson(batchObj) else null
                val msg = json.optString("message", "")

                if (success) {
                    Result.success(HeartbeatResponse(success = true, activeBatch = activeBatch, message = msg))
                } else {
                    Result.failure(IOException(if (msg.isNotBlank()) msg else "Heartbeat rejected (HTTP ${resp.code})"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun getCurrentBatch(): Result<DistributedBatch?> {
        return try {
            val req = Request.Builder()
                .url("$baseUrl${DistributedProtocol.PATH_CURRENT_BATCH}")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val batchObj = json.optJSONObject("batch")
                    val batch = if (batchObj != null) DistributedBatch.fromJson(batchObj) else null
                    Result.success(batch)
                } else {
                    Result.failure(IOException("Failed to get current batch: HTTP ${resp.code}"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun submitEvent(event: InspectionEvent): Result<EventSubmissionResponse> {
        return try {
            val body = event.toJson().toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("$baseUrl${DistributedProtocol.PATH_INSPECTION_EVENTS}")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val json = JSONObject(respBody)
                val accepted = json.optBoolean("accepted", resp.isSuccessful)
                val isDup = json.optBoolean("isDuplicate", false)
                val evId = json.optString("eventId", event.eventId)
                val msg = json.optString("message", "")

                if (accepted) {
                    Result.success(EventSubmissionResponse(accepted = true, isDuplicate = isDup, eventId = evId, message = msg))
                } else {
                    Result.failure(IOException(if (msg.isNotBlank()) msg else "Event rejected by server (HTTP ${resp.code})"))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun connectWebSocket(onMessage: (type: String, data: JSONObject) -> Unit, onDisconnect: () -> Unit) {
        val req = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    val data = json.optJSONObject("data") ?: json
                    onMessage(type, data)
                } catch (_: Throwable) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnect()
            }
        })
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Node disconnecting")
        webSocket = null
    }
}
