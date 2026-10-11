package com.pocketqc.distributed

import com.pocketqc.distributed.model.BatchSummary
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.DistributedDevice
import com.pocketqc.distributed.model.DistributedProtocol
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.store.PersistentDistributedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DistributedModelAndStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testInspectionEventSerializationAndValidation() {
        val event = InspectionEvent(
            eventId = "ev-101",
            deviceId = "node-a",
            sessionId = "sess-123",
            batchId = "PQ-DIST-001",
            stationName = "Line A Inspector",
            itemId = "node-a-item-1",
            classId = 0,
            className = "Broken",
            confidence = 0.94f,
            clientTimestampUtc = 1700000000000L
        )

        assertTrue(event.isDefect)
        assertFalse(event.isPass)

        val json = event.toJson()
        assertEquals("ev-101", json.getString("eventId"))
        assertEquals("node-a", json.getString("deviceId"))
        assertEquals(0.94, json.getDouble("confidence"), 0.001)

        val restored = InspectionEvent.fromJson(json)
        assertEquals(event.eventId, restored.eventId)
        assertEquals(event.className, restored.className)
        assertEquals(event.confidence, restored.confidence, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInspectionEventRejectsInvalidConfidence() {
        InspectionEvent(
            eventId = "ev-bad",
            deviceId = "node-a",
            sessionId = "sess-123",
            batchId = "PQ-DIST-001",
            stationName = "Line A Inspector",
            itemId = "item-1",
            classId = 0,
            className = "Broken",
            confidence = 1.5f, // Invalid: must be <= 1.0
            clientTimestampUtc = 1700000000000L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInspectionEventRejectsBlankEventId() {
        InspectionEvent(
            eventId = "   ",
            deviceId = "node-a",
            sessionId = "sess-123",
            batchId = "PQ-DIST-001",
            stationName = "Line A Inspector",
            itemId = "item-1",
            classId = 0,
            className = "Broken",
            confidence = 0.9f,
            clientTimestampUtc = 1700000000000L
        )
    }

    @Test
    fun testDistributedBatchSerializationAndLifecycle() {
        val batch = DistributedBatch(
            batchId = "PQ-DIST-999",
            productName = "Biscuit Conveyor Lot",
            state = DistributedBatchState.CREATED,
            createdTimeUtc = 1000L
        )
        val json = batch.toJson()
        val restored = DistributedBatch.fromJson(json)
        assertEquals(DistributedBatchState.CREATED, restored.state)
        assertEquals("PQ-DIST-999", restored.batchId)
    }

    @Test
    fun testDistributedDeviceOnlineState() {
        val now = System.currentTimeMillis()
        val onlineDev = DistributedDevice(
            deviceId = "node-a",
            deviceName = "Line A",
            sessionId = "s1",
            lastSeenTimeUtc = now - 2000L
        )
        assertTrue(onlineDev.isOnline(timeoutMs = 5000L))

        val offlineDev = DistributedDevice(
            deviceId = "node-b",
            deviceName = "Line B",
            sessionId = "s2",
            lastSeenTimeUtc = now - 10000L
        )
        assertFalse(offlineDev.isOnline(timeoutMs = 5000L))
    }

    @Test
    fun testPersistentDistributedStoreIdempotencyAndDuplicates() {
        val store = PersistentDistributedStore(tempFolder.root)

        val batch = DistributedBatch(
            batchId = "BATCH-01",
            productName = "Test Lot",
            state = DistributedBatchState.RUNNING,
            createdTimeUtc = 1000L,
            startedTimeUtc = 1000L
        )
        store.saveBatch(batch)

        val event1 = InspectionEvent(
            eventId = "EV-001",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "BATCH-01",
            stationName = "Line A",
            itemId = "item-1",
            classId = 0,
            className = "Broken",
            confidence = 0.95f,
            clientTimestampUtc = 2000L
        )

        // First submission: accepted
        val (acc1, isDup1, err1) = store.recordEvent(event1)
        assertTrue(acc1)
        assertFalse(isDup1)
        assertNull(err1)

        // Resubmission of exact same event: accepted as duplicate without re-counting
        val (acc2, isDup2, err2) = store.recordEvent(event1)
        assertTrue(acc2)
        assertTrue(isDup2)
        assertNull(err2)

        val summary = store.getSummaryForBatch("BATCH-01")
        assertEquals("Total count must remain 1 after duplicate retry", 1, summary.totalInspected)
        assertEquals(1, summary.brokenCount)
        assertEquals(1, summary.defectiveCount)
    }

    @Test
    fun testPersistentDistributedStoreConflictingEventIdRejection() {
        val store = PersistentDistributedStore(tempFolder.root)

        val batch = DistributedBatch(
            batchId = "BATCH-01",
            state = DistributedBatchState.RUNNING,
            createdTimeUtc = 1000L
        )
        store.saveBatch(batch)

        val eventOriginal = InspectionEvent(
            eventId = "EV-CONFLICT",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "BATCH-01",
            stationName = "Line A",
            itemId = "item-1",
            classId = 0,
            className = "Broken",
            confidence = 0.95f,
            clientTimestampUtc = 2000L
        )
        store.recordEvent(eventOriginal)

        // Reuse same eventId with different class and item
        val eventConflict = InspectionEvent(
            eventId = "EV-CONFLICT",
            deviceId = "node-b", // Different device
            sessionId = "s2",
            batchId = "BATCH-01",
            stationName = "Line B",
            itemId = "item-99", // Different item
            classId = 2,
            className = "Intact",
            confidence = 0.98f,
            clientTimestampUtc = 2000L
        )

        val (acc, isDup, err) = store.recordEvent(eventConflict)
        assertFalse(acc)
        assertFalse(isDup)
        assertNotNull(err)
        assertTrue(err!!.contains("Conflict", ignoreCase = true))
    }

    @Test
    fun testPersistentDistributedStoreRejectsPostStopEvents() {
        val store = PersistentDistributedStore(tempFolder.root)

        val batch = DistributedBatch(
            batchId = "BATCH-STOPPED",
            state = DistributedBatchState.STOPPED,
            createdTimeUtc = 1000L,
            startedTimeUtc = 1000L,
            stoppedTimeUtc = 5000L
        )
        store.saveBatch(batch)

        // Event captured AFTER batch was stopped
        val postStopEvent = InspectionEvent(
            eventId = "EV-LATE",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "BATCH-STOPPED",
            stationName = "Line A",
            itemId = "item-2",
            classId = 0,
            className = "Broken",
            confidence = 0.9f,
            clientTimestampUtc = 6000L // After stop time 5000L
        )

        val (acc, _, err) = store.recordEvent(postStopEvent)
        assertFalse(acc)
        assertTrue(err!!.contains("stopped", ignoreCase = true))

        // But an event captured BEFORE stop (timestamp 3000L) submitted delayed during stop is accepted
        val delayedInFlightEvent = InspectionEvent(
            eventId = "EV-INFLIGHT",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "BATCH-STOPPED",
            stationName = "Line A",
            itemId = "item-3",
            classId = 2,
            className = "Intact",
            confidence = 0.91f,
            clientTimestampUtc = 3000L // Before stop time 5000L
        )
        val (acc2, _, _) = store.recordEvent(delayedInFlightEvent)
        assertTrue("Delayed in-flight event captured before stop must be accepted", acc2)
    }

    @Test
    fun testPersistentStoreSurvivesRestart() {
        val folder = tempFolder.root

        // 1. Session 1: Store records
        val store1 = PersistentDistributedStore(folder)
        val batch = DistributedBatch(batchId = "BATCH-PERSIST", state = DistributedBatchState.RUNNING)
        store1.saveBatch(batch)
        store1.saveDevice(DistributedDevice(deviceId = "node-a", deviceName = "Line A", sessionId = "s1"))
        store1.recordEvent(
            InspectionEvent(
                eventId = "EV-P1",
                deviceId = "node-a",
                sessionId = "s1",
                batchId = "BATCH-PERSIST",
                stationName = "Line A",
                itemId = "i1",
                classId = 0,
                className = "Broken",
                confidence = 0.9f,
                clientTimestampUtc = 1000L
            )
        )

        // 2. Session 2: Fresh store instance reading same folder
        val store2 = PersistentDistributedStore(folder)
        assertNotNull(store2.getBatch("BATCH-PERSIST"))
        assertEquals("node-a", store2.getDevice("node-a")?.deviceId)

        val summary = store2.getSummaryForBatch("BATCH-PERSIST")
        assertEquals(1, summary.totalInspected)
        assertEquals(1, summary.brokenCount)
        assertEquals(1, summary.defectiveCount)
        assertEquals(100f, summary.defectRate, 0.01f)
    }

    @Test
    fun testCombinedMultiStationBatchSummaryAggregation() {
        val events = listOf(
            // Station 1: 2D Defect Sort (1 Broken, 2 Intact)
            InspectionEvent(
                eventId = "EV-2D-1",
                deviceId = "node-2d",
                sessionId = "s1",
                batchId = "BATCH-COMBINED",
                stationName = "Station 1 - 2D",
                inspectionMode = "2D_DEFECT_SORT",
                modelId = "biscuits_final",
                itemId = "biscuit-1",
                classId = 0,
                className = "Broken",
                confidence = 0.92f,
                clientTimestampUtc = 1000L
            ),
            InspectionEvent(
                eventId = "EV-2D-2",
                deviceId = "node-2d",
                sessionId = "s1",
                batchId = "BATCH-COMBINED",
                stationName = "Station 1 - 2D",
                inspectionMode = "2D_DEFECT_SORT",
                modelId = "biscuits_final",
                itemId = "biscuit-2",
                classId = 2,
                className = "Intact",
                confidence = 0.95f,
                clientTimestampUtc = 1100L
            ),
            InspectionEvent(
                eventId = "EV-2D-3",
                deviceId = "node-2d",
                sessionId = "s1",
                batchId = "BATCH-COMBINED",
                stationName = "Station 1 - 2D",
                inspectionMode = "2D_DEFECT_SORT",
                modelId = "biscuits_final",
                itemId = "biscuit-3",
                classId = 2,
                className = "Intact",
                confidence = 0.97f,
                clientTimestampUtc = 1200L
            ),
            // Station 2: 3D Volume Inspection (2 PASS, 1 OUT_OF_TOLERANCE)
            InspectionEvent(
                eventId = "EV-3D-1",
                deviceId = "node-3d",
                sessionId = "s2",
                batchId = "BATCH-COMBINED",
                stationName = "Station 2 - 3D",
                inspectionMode = "VOLUME_CHECK_3D",
                modelId = "geometric_fill_analyzer",
                itemId = "bottle-1",
                classId = 1,
                className = "PASS",
                confidence = 0.90f,
                clientTimestampUtc = 1300L
            ),
            InspectionEvent(
                eventId = "EV-3D-2",
                deviceId = "node-3d",
                sessionId = "s2",
                batchId = "BATCH-COMBINED",
                stationName = "Station 2 - 3D",
                inspectionMode = "VOLUME_CHECK_3D",
                modelId = "geometric_fill_analyzer",
                itemId = "bottle-2",
                classId = 0,
                className = "OUT_OF_TOLERANCE",
                confidence = 0.88f,
                clientTimestampUtc = 1400L
            ),
            InspectionEvent(
                eventId = "EV-3D-3",
                deviceId = "node-3d",
                sessionId = "s2",
                batchId = "BATCH-COMBINED",
                stationName = "Station 2 - 3D",
                inspectionMode = "VOLUME_CHECK_3D",
                modelId = "geometric_fill_analyzer",
                itemId = "bottle-3",
                classId = 1,
                className = "PASS",
                confidence = 0.93f,
                clientTimestampUtc = 1500L
            )
        )

        val summary = BatchSummary.fromEvents("BATCH-COMBINED", events)

        // Combined network metrics
        assertEquals(6, summary.totalInspected)
        assertEquals(4, summary.passedCount)     // 2 Intact + 2 PASS
        assertEquals(2, summary.defectiveCount)  // 1 Broken + 1 OUT_OF_TOLERANCE
        assertEquals(33.33f, summary.defectRate, 0.05f)
        assertEquals(66.66f, summary.passRate, 0.05f)

        // 2D stream metrics
        assertEquals(3, summary.count2dTotal)
        assertEquals(2, summary.intactCount)
        assertEquals(1, summary.brokenCount)
        assertEquals(0, summary.fractureCount)

        // 3D volume stream metrics
        assertEquals(3, summary.count3dTotal)
        assertEquals(2, summary.count3dPass)
        assertEquals(1, summary.count3dDefect)

        // Per-station breakdown
        assertEquals(3, summary.deviceCounts["Station 1 - 2D"])
        assertEquals(3, summary.deviceCounts["Station 2 - 3D"])
        assertEquals(1, summary.deviceDefectCounts["Station 1 - 2D"])
        assertEquals(1, summary.deviceDefectCounts["Station 2 - 3D"])
        assertEquals("2D_DEFECT_SORT", summary.deviceModes["Station 1 - 2D"])
        assertEquals("VOLUME_CHECK_3D", summary.deviceModes["Station 2 - 3D"])

        // JSON round-trip serialization
        val json = summary.toJson()
        val restored = BatchSummary.fromJson(json)
        assertEquals(summary.totalInspected, restored.totalInspected)
        assertEquals(summary.passedCount, restored.passedCount)
        assertEquals(summary.defectiveCount, restored.defectiveCount)
        assertEquals(summary.count2dTotal, restored.count2dTotal)
        assertEquals(summary.count3dTotal, restored.count3dTotal)
        assertEquals(summary.count3dPass, restored.count3dPass)
        assertEquals(summary.count3dDefect, restored.count3dDefect)
        assertEquals(summary.deviceModes["Station 2 - 3D"], restored.deviceModes["Station 2 - 3D"])
    }
}
