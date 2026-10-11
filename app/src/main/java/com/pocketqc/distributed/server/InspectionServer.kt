package com.pocketqc.distributed.server

import com.pocketqc.distributed.model.DistributedProtocol
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Embedded HTTP + WebSocket server powered by NanoHTTPD / NanoWSD.
 * Exposes REST endpoints on [/api/v1] and WebSocket live broadcast on [/ws].
 */
class InspectionServer(
    port: Int = DistributedProtocol.DEFAULT_PORT,
    private val manager: InspectionServerManager
) : NanoWSD(port) {

    private val connectedSockets = CopyOnWriteArrayList<DashboardWebSocket>()

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val ws = DashboardWebSocket(handshake)
        connectedSockets.add(ws)
        return ws
    }

    public override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        fun responseWithCors(status: Response.IStatus, mime: String, text: String): Response {
            val resp = NanoHTTPD.newFixedLengthResponse(status, mime, text)
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            return resp
        }

        if (method == Method.OPTIONS) {
            return responseWithCors(Response.Status.OK, "text/plain", "OK")
        }

        val clientIp = session.remoteIpAddress ?: "unknown"

        return try {
            when {
                // 1. GET /api/v1/health
                method == Method.GET && uri == DistributedProtocol.PATH_HEALTH -> {
                    val health = manager.getHealth()
                    responseWithCors(Response.Status.OK, "application/json", health.toJson().toString())
                }

                // 2. POST /api/v1/devices/register
                method == Method.POST && uri == DistributedProtocol.PATH_REGISTER -> {
                    val body = parseBody(session)
                    val json = JSONObject(body)
                    val result = manager.registerDeviceFromJson(json, clientIp)
                    val status = if (result.success) Response.Status.OK else Response.Status.BAD_REQUEST
                    responseWithCors(status, "application/json", result.toJson().toString())
                }

                // 3. POST /api/v1/devices/heartbeat
                method == Method.POST && uri == DistributedProtocol.PATH_HEARTBEAT -> {
                    val body = parseBody(session)
                    val json = JSONObject(body)
                    val result = manager.handleHeartbeatFromJson(json, clientIp)
                    val status = if (result.success) Response.Status.OK else Response.Status.BAD_REQUEST
                    responseWithCors(status, "application/json", result.toJson().toString())
                }

                // 4. GET /api/v1/batches/current
                method == Method.GET && uri == DistributedProtocol.PATH_CURRENT_BATCH -> {
                    val batch = manager.getActiveBatch()
                    val obj = JSONObject().apply {
                        if (batch != null) put("batch", batch.toJson()) else put("batch", JSONObject.NULL)
                    }
                    responseWithCors(Response.Status.OK, "application/json", obj.toString())
                }

                // 5. POST /api/v1/batches (Create Batch)
                method == Method.POST && uri == DistributedProtocol.PATH_BATCHES -> {
                    val body = parseBody(session)
                    val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                    val productName = json.optString("productName", "Biscuit Conveyor Lot")
                    val batch = manager.createBatch(productName)
                    responseWithCors(Response.Status.CREATED, "application/json", batch.toJson().toString())
                }

                // 6. POST /api/v1/batches/{batchId}/start
                method == Method.POST && uri.matches(Regex(".*/batches/[^/]+/start")) -> {
                    val batchId = uri.substringAfter("/batches/").substringBefore("/start")
                    val success = manager.startBatch(batchId)
                    if (success) {
                        val batch = manager.getBatch(batchId)
                        responseWithCors(Response.Status.OK, "application/json", batch?.toJson()?.toString() ?: "{}")
                    } else {
                        responseWithCors(Response.Status.BAD_REQUEST, "application/json", """{"error":"Unable to start batch"}""")
                    }
                }

                // 7. POST /api/v1/batches/{batchId}/stop
                method == Method.POST && uri.matches(Regex(".*/batches/[^/]+/stop")) -> {
                    val batchId = uri.substringAfter("/batches/").substringBefore("/stop")
                    val stopped = manager.stopBatch(batchId)
                    if (stopped != null) {
                        responseWithCors(Response.Status.OK, "application/json", stopped.toJson().toString())
                    } else {
                        responseWithCors(Response.Status.BAD_REQUEST, "application/json", """{"error":"Batch not found or not running"}""")
                    }
                }

                // 8. GET /api/v1/batches/{batchId}/summary
                method == Method.GET && uri.matches(Regex(".*/batches/[^/]+/summary")) -> {
                    val batchId = uri.substringAfter("/batches/").substringBefore("/summary")
                    val summary = manager.getBatchSummary(batchId)
                    responseWithCors(Response.Status.OK, "application/json", summary.toJson().toString())
                }

                // 9. POST /api/v1/inspection-events
                method == Method.POST && uri == DistributedProtocol.PATH_INSPECTION_EVENTS -> {
                    val body = parseBody(session)
                    val json = JSONObject(body)
                    val result = manager.acceptEventFromJson(json)
                    val status = when {
                        result.accepted -> Response.Status.OK
                        result.message.contains("Conflict", ignoreCase = true) -> Response.Status.CONFLICT
                        else -> Response.Status.BAD_REQUEST
                    }
                    responseWithCors(status, "application/json", result.toJson().toString())
                }

                else -> {
                    responseWithCors(Response.Status.NOT_FOUND, "application/json", """{"error":"Route not found: $uri"}""")
                }
            }
        } catch (t: Throwable) {
            responseWithCors(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", t.message ?: "Internal Server Error").toString()
            )
        }
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    fun broadcast(type: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", type)
            put("timestamp", System.currentTimeMillis())
            put("data", data)
        }.toString()

        for (ws in connectedSockets) {
            try {
                if (ws.isOpen) {
                    ws.send(message)
                }
            } catch (_: IOException) {
                connectedSockets.remove(ws)
            }
        }
    }

    inner class DashboardWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        public override fun onOpen() {
            try {
                val initMsg = JSONObject().apply {
                    put("type", "INIT")
                    put("health", manager.getHealth().toJson())
                    val activeBatch = manager.getActiveBatch()
                    if (activeBatch != null) put("activeBatch", activeBatch.toJson())
                }.toString()
                send(initMsg)
            } catch (_: IOException) {}
        }

        public override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            connectedSockets.remove(this)
        }

        public override fun onMessage(message: WebSocketFrame?) {
            // Client dashboard query / ping
        }

        public override fun onPong(pong: WebSocketFrame?) {}
        public override fun onException(exception: IOException?) {
            connectedSockets.remove(this)
        }
    }
}
