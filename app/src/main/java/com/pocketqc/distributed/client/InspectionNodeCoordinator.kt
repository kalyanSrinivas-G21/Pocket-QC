package com.pocketqc.distributed.client

import android.content.Context
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.DistributedProtocol
import com.pocketqc.distributed.model.HeartbeatRequest
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.model.RegisterRequest
import com.pocketqc.distributed.store.ClientOutboxStore
import com.pocketqc.tracking.TrackedDetection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class NodeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class NodeUiState(
    val connectionState: NodeConnectionState = NodeConnectionState.DISCONNECTED,
    val deviceId: String = "",
    val stationName: String = "Line A Inspector",
    val sessionId: String = "",
    val serverHost: String = "",
    val serverPort: Int = DistributedProtocol.DEFAULT_PORT,
    val activeBatch: DistributedBatch? = null,
    val isInspecting: Boolean = false,
    val localItemCount: Int = 0,
    val localBrokenCount: Int = 0,
    val localFractureCount: Int = 0,
    val localIntactCount: Int = 0,
    val outboxQueueDepth: Int = 0,
    val lastSyncTimeUtc: Long = 0L,
    val errorMessage: String? = null,
    val fps: Float = 0f,
    val latencyMs: Long = 0L
)

/**
 * Coordinates inspection node operations on Devices 2 & 3.
 * Manages persistent device ID, client outbox, server registration, heartbeats,
 * and converts local [TrackedDetection] items into distributed [InspectionEvent] records.
 */
class InspectionNodeCoordinator(
    private val baseDir: File,
    private val outboxStore: ClientOutboxStore = ClientOutboxStore(baseDir),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    constructor(context: Context) : this(context.filesDir)

    private val deviceIdFile = File(baseDir, "node_device_id.txt")
    val persistentDeviceId: String = getOrCreateDeviceId()

    private val _uiState = MutableStateFlow(
        NodeUiState(
            deviceId = persistentDeviceId,
            outboxQueueDepth = outboxStore.getQueueDepth()
        )
    )
    val uiState: StateFlow<NodeUiState> = _uiState.asStateFlow()

    private var client: InspectionNodeClient? = null
    private var heartbeatJob: Job? = null
    private var outboxFlushJob: Job? = null

    private fun getOrCreateDeviceId(): String {
        return if (deviceIdFile.exists()) {
            deviceIdFile.readText().trim()
        } else {
            val newId = "node-" + UUID.randomUUID().toString().take(8)
            deviceIdFile.writeText(newId)
            newId
        }
    }

    fun connect(serverHost: String, serverPort: Int = DistributedProtocol.DEFAULT_PORT, stationName: String = "Line A Inspector") {
        val (cleanHost, cleanPort) = com.pocketqc.distributed.server.NetworkUtils.normalizeHostAndPort(serverHost, serverPort)
        if (cleanHost.isBlank()) {
            _uiState.value = _uiState.value.copy(
                connectionState = NodeConnectionState.ERROR,
                errorMessage = "Server IP address cannot be blank"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            connectionState = NodeConnectionState.CONNECTING,
            serverHost = cleanHost,
            serverPort = cleanPort,
            stationName = stationName.trim(),
            errorMessage = null
        )

        val nodeClient = InspectionNodeClient(cleanHost, cleanPort)
        client = nodeClient

        scope.launch {
            try {
                // 1. Health check
                val healthResult = nodeClient.checkHealth()
                if (healthResult.isFailure) {
                    val err = healthResult.exceptionOrNull()?.message ?: "Server unreachable"
                    _uiState.value = _uiState.value.copy(
                        connectionState = NodeConnectionState.ERROR,
                        errorMessage = "Cannot reach server at $cleanHost:$cleanPort ($err). Ensure both phones are connected to the same Wi-Fi or phone hotspot."
                    )
                    return@launch
                }

                // 2. Register
                val regReq = RegisterRequest(
                    deviceId = persistentDeviceId,
                    deviceName = stationName,
                    supportedInspectionModes = listOf("2D_DEFECT_SORT"),
                    modelId = "biscuits_final",
                    modelVersion = "1.0",
                    capabilities = listOf("YOLOv8n", "CentroidTracker", "CameraX")
                )
                val regResult = nodeClient.register(regReq)
                if (regResult.isFailure) {
                    val err = regResult.exceptionOrNull()?.message ?: "Registration rejected"
                    _uiState.value = _uiState.value.copy(
                        connectionState = NodeConnectionState.ERROR,
                        errorMessage = err
                    )
                    return@launch
                }

                val regResp = regResult.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    connectionState = NodeConnectionState.CONNECTED,
                    sessionId = regResp.sessionId ?: "",
                    activeBatch = regResp.activeBatch,
                    errorMessage = null
                )

                // 3. Connect WebSocket for live state changes
                nodeClient.connectWebSocket(
                    onMessage = { type, data ->
                        when (type) {
                            DistributedProtocol.WS_BATCH_CREATED -> {
                                val batch = DistributedBatch.fromJson(data)
                                _uiState.value = _uiState.value.copy(activeBatch = batch)
                            }
                            DistributedProtocol.WS_BATCH_STARTED -> {
                                val batch = DistributedBatch.fromJson(data)
                                _uiState.value = _uiState.value.copy(activeBatch = batch)
                            }
                            DistributedProtocol.WS_BATCH_STOPPED -> {
                                val batch = DistributedBatch.fromJson(data)
                                _uiState.value = _uiState.value.copy(
                                    activeBatch = batch,
                                    isInspecting = false
                                )
                            }
                        }
                    },
                    onDisconnect = {
                        // Will be handled by heartbeat reconnect
                    }
                )

                // 4. Start heartbeat and outbox drain loops
                startHeartbeatLoop()
                startOutboxDrainLoop()

                // Trigger immediate flush of any stored outbox events
                flushOutbox()

            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    connectionState = NodeConnectionState.ERROR,
                    errorMessage = "Connection error: ${t.message}"
                )
            }
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        outboxFlushJob?.cancel()
        client?.disconnectWebSocket()
        client = null
        _uiState.value = _uiState.value.copy(
            connectionState = NodeConnectionState.DISCONNECTED,
            isInspecting = false
        )
    }

    fun startInspection(): Boolean {
        val state = _uiState.value
        if (state.connectionState != NodeConnectionState.CONNECTED) {
            _uiState.value = state.copy(errorMessage = "Cannot inspect: Node is not connected to server")
            return false
        }
        val activeBatch = state.activeBatch
        if (activeBatch == null || activeBatch.state != DistributedBatchState.RUNNING) {
            _uiState.value = state.copy(errorMessage = "Cannot inspect: No running batch on server")
            return false
        }

        _uiState.value = state.copy(
            isInspecting = true,
            errorMessage = null
        )
        return true
    }

    fun stopInspection() {
        _uiState.value = _uiState.value.copy(isInspecting = false)
    }

    /**
     * Ingests newly tracked unique items from the [CentroidDetectionTracker].
     * Converts to [InspectionEvent], saves to persistent outbox, and triggers flush.
     */
    fun onNewlyCountedItems(
        items: List<TrackedDetection>,
        inspectionMode: String = "2D_DEFECT_SORT",
        modelId: String = "biscuits_final"
    ) {
        val state = _uiState.value
        val activeBatch = state.activeBatch
        if (!state.isInspecting || activeBatch == null || items.isEmpty()) return

        var newBroken = state.localBrokenCount
        var newFracture = state.localFractureCount
        var newIntact = state.localIntactCount

        for (item in items) {
            val isDef = item.className.equals("Broken", ignoreCase = true) ||
                    item.className.equals("Fracture", ignoreCase = true) ||
                    item.className.contains("Defect", ignoreCase = true) ||
                    item.className.contains("Aflatoxin", ignoreCase = true) ||
                    item.className.contains("FLAGGED", ignoreCase = true)

            if (isDef) {
                if (item.className.equals("Fracture", ignoreCase = true)) {
                    newFracture++
                } else {
                    newBroken++
                }
            } else {
                newIntact++
            }

            val classId = when {
                item.className.equals("Broken", ignoreCase = true) -> 0
                item.className.equals("Fracture", ignoreCase = true) -> 1
                item.className.equals("Intact", ignoreCase = true) -> 2
                item.className.contains("Aflatoxin", ignoreCase = true) -> 0
                item.className.contains("Healthy", ignoreCase = true) -> 1
                else -> 0
            }

            val event = InspectionEvent(
                eventId = UUID.randomUUID().toString(),
                deviceId = persistentDeviceId,
                sessionId = state.sessionId,
                batchId = activeBatch.batchId,
                stationName = state.stationName,
                inspectionMode = inspectionMode,
                modelId = modelId,
                modelVersion = "1.0",
                itemId = "${state.deviceId}-item-${item.trackId}",
                classId = classId,
                className = item.className,
                confidence = item.confidence.coerceIn(0.0f, 1.0f),
                clientTimestampUtc = System.currentTimeMillis()
            )

            outboxStore.enqueue(event)
        }

        _uiState.value = state.copy(
            localItemCount = state.localItemCount + items.size,
            localBrokenCount = newBroken,
            localFractureCount = newFracture,
            localIntactCount = newIntact,
            outboxQueueDepth = outboxStore.getQueueDepth()
        )

        // Trigger immediate flush of pending events
        flushOutbox()
    }

    /**
     * Records a single inspection event (e.g. from 3D Volume or optical screening).
     */
    fun recordInspectionEvent(
        className: String,
        classId: Int,
        confidence: Float,
        inspectionMode: String = "2D_DEFECT_SORT",
        modelId: String = "biscuits_final",
        itemId: String = "${persistentDeviceId}-item-${System.currentTimeMillis()}"
    ) {
        val state = _uiState.value
        val activeBatch = state.activeBatch
        if (!state.isInspecting || activeBatch == null) return

        var newBroken = state.localBrokenCount
        var newFracture = state.localFractureCount
        var newIntact = state.localIntactCount

        val isDefect = className.equals("Broken", ignoreCase = true) ||
                className.equals("Fracture", ignoreCase = true) ||
                className.contains("Defect", ignoreCase = true) ||
                className.contains("Aflatoxin", ignoreCase = true) ||
                className.contains("FLAGGED", ignoreCase = true) ||
                className.contains("OUT_OF_TOLERANCE", ignoreCase = true) ||
                className.contains("REJECT", ignoreCase = true)

        if (isDefect) {
            newBroken++
        } else {
            newIntact++
        }

        val event = InspectionEvent(
            eventId = UUID.randomUUID().toString(),
            deviceId = persistentDeviceId,
            sessionId = state.sessionId,
            batchId = activeBatch.batchId,
            stationName = state.stationName,
            inspectionMode = inspectionMode,
            modelId = modelId,
            modelVersion = "1.0",
            itemId = itemId,
            classId = classId,
            className = className,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            clientTimestampUtc = System.currentTimeMillis()
        )

        outboxStore.enqueue(event)

        _uiState.value = state.copy(
            localItemCount = state.localItemCount + 1,
            localBrokenCount = newBroken,
            localFractureCount = newFracture,
            localIntactCount = newIntact,
            outboxQueueDepth = outboxStore.getQueueDepth()
        )

        flushOutbox()
    }

    fun updateTelemetry(fps: Float, latencyMs: Long) {
        _uiState.value = _uiState.value.copy(fps = fps, latencyMs = latencyMs)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(3000)
                val c = client ?: break
                val s = _uiState.value
                if (s.connectionState == NodeConnectionState.DISCONNECTED) break

                val req = HeartbeatRequest(
                    deviceId = persistentDeviceId,
                    sessionId = s.sessionId,
                    timestamp = System.currentTimeMillis(),
                    isInspecting = s.isInspecting,
                    fps = s.fps,
                    inferenceLatencyMs = s.latencyMs,
                    queueDepth = outboxStore.getQueueDepth()
                )

                val result = c.sendHeartbeat(req)
                if (result.isSuccess) {
                    consecutiveFailures = 0
                    val hb = result.getOrThrow()
                    _uiState.value = _uiState.value.copy(
                        connectionState = NodeConnectionState.CONNECTED,
                        activeBatch = hb.activeBatch,
                        outboxQueueDepth = outboxStore.getQueueDepth(),
                        errorMessage = null
                    )
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = NodeConnectionState.RECONNECTING,
                            errorMessage = "Heartbeat lost (${result.exceptionOrNull()?.message}). Reconnecting..."
                        )
                    }
                }
            }
        }
    }

    private fun startOutboxDrainLoop() {
        outboxFlushJob?.cancel()
        outboxFlushJob = scope.launch {
            while (isActive) {
                delay(4000)
                if (_uiState.value.connectionState == NodeConnectionState.CONNECTED) {
                    flushOutbox()
                }
            }
        }
    }

    fun flushOutbox() {
        val c = client ?: return
        if (_uiState.value.connectionState != NodeConnectionState.CONNECTED) return

        scope.launch {
            val pending = outboxStore.getPendingEvents(limit = 20)
            if (pending.isEmpty()) return@launch

            for (queued in pending) {
                val ev = queued.event
                val res = c.submitEvent(ev)
                if (res.isSuccess) {
                    outboxStore.markAcknowledged(ev.eventId)
                    _uiState.value = _uiState.value.copy(
                        lastSyncTimeUtc = System.currentTimeMillis(),
                        outboxQueueDepth = outboxStore.getQueueDepth()
                    )
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Submission failed"
                    outboxStore.recordAttemptFailed(ev.eventId, err)
                }
            }
        }
    }

    fun resetLocalCounts() {
        _uiState.value = _uiState.value.copy(
            localItemCount = 0,
            localBrokenCount = 0,
            localFractureCount = 0,
            localIntactCount = 0
        )
    }

    fun clearLocalMetricsAndOutbox() {
        outboxStore.clear()
        _uiState.value = _uiState.value.copy(
            localItemCount = 0,
            localBrokenCount = 0,
            localFractureCount = 0,
            localIntactCount = 0,
            outboxQueueDepth = 0
        )
    }
}
