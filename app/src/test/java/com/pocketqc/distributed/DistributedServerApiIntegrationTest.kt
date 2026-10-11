package com.pocketqc.distributed

import com.pocketqc.distributed.client.InspectionNodeClient
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.DistributedProtocol
import com.pocketqc.distributed.model.HeartbeatRequest
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.model.RegisterRequest
import com.pocketqc.distributed.server.InspectionServerManager
import com.pocketqc.distributed.store.PersistentDistributedStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

class DistributedServerApiIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: PersistentDistributedStore
    private lateinit var serverManager: InspectionServerManager
    private lateinit var client: InspectionNodeClient
    private var testPort: Int = 0

    @Before
    fun setUp() {
        // Find a free port
        val s = ServerSocket(0)
        testPort = s.localPort
        s.close()

        store = PersistentDistributedStore(tempFolder.root)
        serverManager = InspectionServerManager(store)
        val startResult = serverManager.startServer(testPort)
        assertTrue("Server must start cleanly", startResult.isSuccess)

        client = InspectionNodeClient("127.0.0.1", testPort)
    }

    @After
    fun tearDown() {
        serverManager.stopServer()
    }

    @Test
    fun testHealthEndpoint() {
        val healthRes = client.checkHealth()
        assertTrue(healthRes.isSuccess)
        val health = healthRes.getOrThrow()
        assertEquals("UP", health.status)
        assertEquals(DistributedProtocol.PROTOCOL_VERSION, health.protocolVersion)
    }

    @Test
    fun testDeviceRegistrationAndHeartbeat() {
        val regReq = RegisterRequest(
            deviceId = "node-alpha",
            deviceName = "Line A Inspector",
            supportedInspectionModes = listOf("2D_DEFECT_SORT"),
            protocolVersion = DistributedProtocol.PROTOCOL_VERSION
        )

        val regRes = client.register(regReq)
        assertTrue(regRes.isSuccess)
        val regResp = regRes.getOrThrow()
        assertTrue(regResp.success)
        assertNotNull(regResp.sessionId)

        // Send Heartbeat with valid session
        val hbReq = HeartbeatRequest(
            deviceId = "node-alpha",
            sessionId = regResp.sessionId!!,
            isInspecting = true,
            fps = 29.5f,
            inferenceLatencyMs = 34L
        )
        val hbRes = client.sendHeartbeat(hbReq)
        assertTrue(hbRes.isSuccess)
        assertTrue(hbRes.getOrThrow().success)

        val dev = serverManager.store.getDevice("node-alpha")
        assertNotNull(dev)
        assertEquals(29.5f, dev!!.fps, 0.01f)
        assertTrue(dev.isInspecting)
    }

    @Test
    fun testRegistrationRejectsIncompatibleProtocol() {
        val regReq = RegisterRequest(
            deviceId = "node-legacy",
            deviceName = "Old Inspector",
            protocolVersion = 999 // Incompatible
        )
        val regRes = client.register(regReq)
        assertFalse(regRes.isSuccess)
        assertTrue(regRes.exceptionOrNull()?.message?.contains("Incompatible protocol") == true)
    }

    @Test
    fun testFullBatchInspectionLifecycleAndEventSubmission() {
        // 1. Create and start a batch on server
        val batch = serverManager.createBatch("Biscuit Conveyor Lot A")
        assertEquals(DistributedBatchState.CREATED, batch.state)

        val started = serverManager.startBatch(batch.batchId)
        assertTrue(started)
        assertEquals(DistributedBatchState.RUNNING, serverManager.getActiveBatch()?.state)

        // 2. Register Node A and Node B
        val regA = client.register(
            RegisterRequest(
                deviceId = "node-a",
                deviceName = "Line A Inspector",
                protocolVersion = DistributedProtocol.PROTOCOL_VERSION
            )
        ).getOrThrow()

        val regB = client.register(
            RegisterRequest(
                deviceId = "node-b",
                deviceName = "Line B Inspector",
                protocolVersion = DistributedProtocol.PROTOCOL_VERSION
            )
        ).getOrThrow()

        // 3. Submit real events from Node A (1 Broken, 1 Intact)
        val evA1 = InspectionEvent(
            eventId = "EV-A-01",
            deviceId = "node-a",
            sessionId = regA.sessionId!!,
            batchId = batch.batchId,
            stationName = "Line A Inspector",
            itemId = "node-a-item-1",
            classId = 0,
            className = "Broken",
            confidence = 0.91f,
            clientTimestampUtc = System.currentTimeMillis()
        )
        val subA1 = client.submitEvent(evA1).getOrThrow()
        assertTrue(subA1.accepted)
        assertFalse(subA1.isDuplicate)

        val evA2 = InspectionEvent(
            eventId = "EV-A-02",
            deviceId = "node-a",
            sessionId = regA.sessionId!!,
            batchId = batch.batchId,
            stationName = "Line A Inspector",
            itemId = "node-a-item-2",
            classId = 2,
            className = "Intact",
            confidence = 0.96f,
            clientTimestampUtc = System.currentTimeMillis()
        )
        val subA2 = client.submitEvent(evA2).getOrThrow()
        assertTrue(subA2.accepted)

        // 4. Submit event from Node B (1 Fracture)
        val evB1 = InspectionEvent(
            eventId = "EV-B-01",
            deviceId = "node-b",
            sessionId = regB.sessionId!!,
            batchId = batch.batchId,
            stationName = "Line B Inspector",
            itemId = "node-b-item-1",
            classId = 1,
            className = "Fracture",
            confidence = 0.88f,
            clientTimestampUtc = System.currentTimeMillis()
        )
        val subB1 = client.submitEvent(evB1).getOrThrow()
        assertTrue(subB1.accepted)

        // 5. Verify aggregated summary on server
        val summary = serverManager.getBatchSummary(batch.batchId)
        assertEquals(3, summary.totalInspected)
        assertEquals(1, summary.passedCount)     // 1 Intact
        assertEquals(2, summary.defectiveCount)  // 1 Broken + 1 Fracture
        assertEquals(1, summary.brokenCount)
        assertEquals(1, summary.fractureCount)
        assertEquals(1, summary.intactCount)
        assertEquals(66.66f, summary.defectRate, 0.1f)

        // Verify per-station breakdown
        assertEquals(2, summary.deviceCounts["Line A Inspector"])
        assertEquals(1, summary.deviceCounts["Line B Inspector"])
        assertEquals(1, summary.deviceDefectCounts["Line A Inspector"])
        assertEquals(1, summary.deviceDefectCounts["Line B Inspector"])

        // 6. Stop the batch
        val stopped = serverManager.stopBatch(batch.batchId)
        assertNotNull(stopped)
        assertEquals(DistributedBatchState.STOPPED, stopped!!.state)
    }
}
