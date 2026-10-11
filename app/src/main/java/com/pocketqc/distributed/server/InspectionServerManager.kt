package com.pocketqc.distributed.server

import com.pocketqc.batch.BatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.utils.BatchIdGenerator
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.distributed.model.BatchSummary
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.DistributedDevice
import com.pocketqc.distributed.model.DistributedProtocol
import com.pocketqc.distributed.model.EventSubmissionResponse
import com.pocketqc.distributed.model.HealthResponse
import com.pocketqc.distributed.model.HeartbeatRequest
import com.pocketqc.distributed.model.HeartbeatResponse
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.model.RegisterRequest
import com.pocketqc.distributed.model.RegisterResponse
import com.pocketqc.distributed.store.DistributedStore
import com.pocketqc.ui.components.InspectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

enum class ServerRunState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * High-level manager coordinating the local inspection server on Device 1.
 * Owns authoritative batch state, session registry, and event ingestion.
 */
class InspectionServerManager(
    val store: DistributedStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val batchManager: BatchManager? = null
) {
    private var server: InspectionServer? = null
    private var startTimeMs: Long = 0L

    private val _serverState = MutableStateFlow(ServerRunState.STOPPED)
    val serverState: StateFlow<ServerRunState> = _serverState.asStateFlow()

    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _serverPort = MutableStateFlow(DistributedProtocol.DEFAULT_PORT)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _activeBatch = MutableStateFlow<DistributedBatch?>(null)
    val activeBatch: StateFlow<DistributedBatch?> = _activeBatch.asStateFlow()

    private val _devices = MutableStateFlow<List<DistributedDevice>>(emptyList())
    val devices: StateFlow<List<DistributedDevice>> = _devices.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<InspectionEvent>>(emptyList())
    val recentEvents: StateFlow<List<InspectionEvent>> = _recentEvents.asStateFlow()

    private val _currentSummary = MutableStateFlow(BatchSummary(batchId = ""))
    val currentSummary: StateFlow<BatchSummary> = _currentSummary.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastCompletedBatch = MutableStateFlow<Batch?>(null)
    val lastCompletedBatch: StateFlow<Batch?> = _lastCompletedBatch.asStateFlow()

    // Active session lookup: sessionId -> deviceId
    private val activeSessions = mutableMapOf<String, String>()

    private var heartbeatMonitorJob: Job? = null

    init {
        // Load existing batches and active batch if one was previously running
        refreshFromStore()
    }

    fun refreshFromStore() {
        val running = store.getActiveBatch()
        _activeBatch.value = running
        _devices.value = store.getAllDevices()
        if (running != null) {
            val events = store.getEventsForBatch(running.batchId)
            _recentEvents.value = events.takeLast(50).reversed()
            _currentSummary.value = store.getSummaryForBatch(running.batchId)
        } else {
            val latestBatch = store.getAllBatches().firstOrNull()
            if (latestBatch != null) {
                _currentSummary.value = store.getSummaryForBatch(latestBatch.batchId)
                _recentEvents.value = store.getEventsForBatch(latestBatch.batchId).takeLast(50).reversed()
            }
        }
    }

    @Synchronized
    fun startServer(port: Int = DistributedProtocol.DEFAULT_PORT): Result<Unit> {
        if (_serverState.value == ServerRunState.RUNNING) {
            return Result.success(Unit)
        }

        return try {
            _serverState.value = ServerRunState.STARTING
            _serverPort.value = port
            _serverIp.value = NetworkUtils.getLocalIpAddress()

            val srv = InspectionServer(port, this)
            srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
            startTimeMs = System.currentTimeMillis()
            _serverState.value = ServerRunState.RUNNING
            _lastError.value = null

            startHeartbeatMonitor()
            Result.success(Unit)
        } catch (t: Throwable) {
            _serverState.value = ServerRunState.ERROR
            _lastError.value = "Failed to start server on port $port: ${t.message}"
            Result.failure(t)
        }
    }

    @Synchronized
    fun stopServer() {
        heartbeatMonitorJob?.cancel()
        try {
            server?.stop()
        } catch (_: Throwable) {}
        server = null
        _serverState.value = ServerRunState.STOPPED
    }

    fun refreshNetworkAddress() {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip.isNotBlank()) {
            _serverIp.value = ip
        }
    }

    @Synchronized
    fun clearAllData() {
        store.clearAll()
        activeSessions.clear()
        _activeBatch.value = null
        _lastCompletedBatch.value = null
        _devices.value = emptyList()
        _recentEvents.value = emptyList()
        _currentSummary.value = BatchSummary(batchId = "")
    }

    private fun startHeartbeatMonitor() {
        heartbeatMonitorJob?.cancel()
        heartbeatMonitorJob = scope.launch {
            while (isActive && _serverState.value == ServerRunState.RUNNING) {
                delay(3000)
                // Periodically update IP if network changed (e.g. hotspot enabled after launch)
                val currentIp = NetworkUtils.getLocalIpAddress()
                if (currentIp != "127.0.0.1" && currentIp != _serverIp.value) {
                    _serverIp.value = currentIp
                }
                // Refresh device online states in UI flow
                _devices.value = store.getAllDevices()
            }
        }
    }

    fun getHealth(): HealthResponse {
        val uptime = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        return HealthResponse(
            status = if (_serverState.value == ServerRunState.RUNNING) "UP" else "STOPPED",
            protocolVersion = DistributedProtocol.PROTOCOL_VERSION,
            serverUptimeMs = uptime,
            activeBatchId = _activeBatch.value?.batchId
        )
    }

    @Synchronized
    fun createBatch(
        productName: String = "Biscuit Conveyor Lot",
        inspectionMode: String = "2D_DEFECT_SORT"
    ): DistributedBatch {
        val newId = BatchIdGenerator.generate(prefix = "PQ-DIST")
        val batch = DistributedBatch(
            batchId = newId,
            productName = productName,
            state = DistributedBatchState.CREATED,
            createdTimeUtc = System.currentTimeMillis(),
            inspectionMode = inspectionMode
        )
        store.saveBatch(batch)
        _activeBatch.value = batch
        _lastCompletedBatch.value = null
        _currentSummary.value = BatchSummary(batchId = newId)
        _recentEvents.value = emptyList()

        broadcast(DistributedProtocol.WS_BATCH_CREATED, batch.toJson())
        return batch
    }

    @Synchronized
    fun startBatch(batchId: String): Boolean {
        val existing = store.getBatch(batchId) ?: return false
        val started = existing.copy(
            state = DistributedBatchState.RUNNING,
            startedTimeUtc = System.currentTimeMillis()
        )
        store.saveBatch(started)
        _activeBatch.value = started
        _currentSummary.value = store.getSummaryForBatch(batchId)

        broadcast(DistributedProtocol.WS_BATCH_STARTED, started.toJson())
        return true
    }

    @Synchronized
    fun stopBatch(batchId: String): DistributedBatch? {
        val existing = store.getBatch(batchId) ?: return null
        if (existing.state == DistributedBatchState.STOPPED) {
            return existing
        }
        val stopped = existing.copy(
            state = DistributedBatchState.STOPPED,
            stoppedTimeUtc = System.currentTimeMillis()
        )
        store.saveBatch(stopped)
        _activeBatch.value = null
        val summary = store.getSummaryForBatch(batchId)
        _currentSummary.value = summary

        // Bridge to Core BatchManager for Log History & QR Passport Screen
        val total = summary.totalInspected
        val passed = summary.passedCount
        val failed = summary.defectiveCount
        val defectRate = if (total > 0) failed.toFloat() / total.toFloat() else 0f
        val isApproved = (total > 0 && defectRate <= 0.12f)
        val status = if (isApproved) BatchStatus.APPROVED else BatchStatus.REJECTED

        val qrType = when (stopped.inspectionMode) {
            "UV_AFLATOXIN" -> "UV"
            "VOLUME_CHECK_3D" -> "3D"
            else -> "2D"
        }

        val qrPayload = QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = batchId,
            type = qrType,
            total = total,
            pass = passed,
            defectOrFlagged = failed,
            rate = defectRate * 100f,
            result = if (isApproved) "PASS" else "REJECT"
        )

        val coreMode = when (stopped.inspectionMode) {
            "UV_AFLATOXIN" -> InspectionMode.UV_AFLATOXIN
            "VOLUME_CHECK_3D" -> InspectionMode.VOLUME_CHECK_3D
            else -> InspectionMode.DEFECT_SORT_2D
        }

        val coreBatch = Batch(
            batchId = batchId,
            productName = stopped.productName,
            inspectionCount = total,
            passCount = passed,
            failCount = failed,
            defectRate = defectRate,
            timestamp = stopped.stoppedTimeUtc ?: System.currentTimeMillis(),
            status = status,
            mode = coreMode,
            startTime = stopped.startedTimeUtc ?: stopped.createdTimeUtc,
            endTime = stopped.stoppedTimeUtc ?: System.currentTimeMillis(),
            brokenCount = summary.brokenCount,
            fractureCount = summary.fractureCount,
            intactCount = summary.intactCount,
            flaggedCount = if (stopped.inspectionMode == "UV_AFLATOXIN") failed else 0,
            requiredFillPercent = if (stopped.inspectionMode == "VOLUME_CHECK_3D") 80f else 0f,
            averageDetectedFillPercent = if (stopped.inspectionMode == "VOLUME_CHECK_3D") 80f else 0f,
            qrPayload = qrPayload
        )

        _lastCompletedBatch.value = coreBatch
        batchManager?.saveBatch(coreBatch)

        broadcast(DistributedProtocol.WS_BATCH_STOPPED, stopped.toJson())
        return stopped
    }

    fun getBatch(batchId: String): DistributedBatch? = store.getBatch(batchId)
    fun getActiveBatch(): DistributedBatch? = _activeBatch.value
    fun getBatchSummary(batchId: String): BatchSummary = store.getSummaryForBatch(batchId)

    @Synchronized
    fun registerDeviceFromJson(json: JSONObject, clientIp: String): RegisterResponse {
        val req = RegisterRequest.fromJson(json)

        // Validate protocol version compatibility
        if (req.protocolVersion != DistributedProtocol.PROTOCOL_VERSION) {
            return RegisterResponse(
                success = false,
                message = "Incompatible protocol version ${req.protocolVersion}. Server requires ${DistributedProtocol.PROTOCOL_VERSION}."
            )
        }

        val sessionId = "sess-" + UUID.randomUUID().toString().take(12)
        activeSessions[sessionId] = req.deviceId

        val device = DistributedDevice(
            deviceId = req.deviceId,
            deviceName = req.deviceName,
            sessionId = sessionId,
            appVersion = req.appVersion,
            supportedInspectionModes = req.supportedInspectionModes,
            modelId = req.modelId,
            modelVersion = req.modelVersion,
            capabilities = req.capabilities,
            registeredTimeUtc = System.currentTimeMillis(),
            lastSeenTimeUtc = System.currentTimeMillis(),
            clientIp = clientIp
        )

        store.saveDevice(device)
        _devices.value = store.getAllDevices()

        broadcast(DistributedProtocol.WS_DEVICE_REGISTERED, device.toJson())

        return RegisterResponse(
            success = true,
            sessionId = sessionId,
            activeBatch = _activeBatch.value,
            message = "Node '${req.deviceName}' registered successfully"
        )
    }

    @Synchronized
    fun handleHeartbeatFromJson(json: JSONObject, clientIp: String): HeartbeatResponse {
        val req = HeartbeatRequest.fromJson(json)
        val expectedDevice = activeSessions[req.sessionId]
        if (expectedDevice != req.deviceId) {
            return HeartbeatResponse(
                success = false,
                message = "Invalid or expired session. Please re-register."
            )
        }

        val existing = store.getDevice(req.deviceId)
        if (existing != null) {
            val updated = existing.copy(
                lastSeenTimeUtc = System.currentTimeMillis(),
                isInspecting = req.isInspecting,
                fps = req.fps,
                inferenceLatencyMs = req.inferenceLatencyMs,
                queueDepth = req.queueDepth,
                clientIp = clientIp
            )
            store.saveDevice(updated)
            _devices.value = store.getAllDevices()
        }

        return HeartbeatResponse(
            success = true,
            activeBatch = _activeBatch.value
        )
    }

    @Synchronized
    fun acceptEventFromJson(json: JSONObject): EventSubmissionResponse {
        val event: InspectionEvent
        try {
            event = InspectionEvent.fromJson(json)
        } catch (t: Throwable) {
            return EventSubmissionResponse(
                accepted = false,
                eventId = json.optString("eventId", "unknown"),
                message = "Malformed event payload: ${t.message}"
            )
        }

        // Validate session
        val expectedDevice = activeSessions[event.sessionId]
        if (expectedDevice != event.deviceId) {
            return EventSubmissionResponse(
                accepted = false,
                eventId = event.eventId,
                message = "Unauthorized: Session '${event.sessionId}' does not match device '${event.deviceId}'"
            )
        }

        // Record in persistent store (guarantees idempotency and durability)
        val (accepted, isDuplicate, errorMsg) = store.recordEvent(event)

        if (accepted) {
            if (!isDuplicate) {
                // Update live StateFlows
                val currentList = _recentEvents.value.toMutableList()
                currentList.add(0, event)
                if (currentList.size > 50) currentList.removeAt(currentList.lastIndex)
                _recentEvents.value = currentList

                val summary = store.getSummaryForBatch(event.batchId)
                _currentSummary.value = summary

                broadcast(DistributedProtocol.WS_EVENT_ACCEPTED, event.toJson())
                broadcast(DistributedProtocol.WS_COUNTS_UPDATED, summary.toJson())
            }

            return EventSubmissionResponse(
                accepted = true,
                isDuplicate = isDuplicate,
                eventId = event.eventId,
                message = if (isDuplicate) "Event already accepted previously" else "Accepted"
            )
        } else {
            return EventSubmissionResponse(
                accepted = false,
                eventId = event.eventId,
                message = errorMsg ?: "Rejected by server"
            )
        }
    }

    private fun broadcast(type: String, data: JSONObject) {
        server?.broadcast(type, data)
    }
}
