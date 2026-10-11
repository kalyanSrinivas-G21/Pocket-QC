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
}
