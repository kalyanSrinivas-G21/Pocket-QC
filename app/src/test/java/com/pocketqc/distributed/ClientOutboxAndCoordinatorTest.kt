package com.pocketqc.distributed

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.distributed.client.InspectionNodeCoordinator
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.InspectionEvent
import com.pocketqc.distributed.store.ClientOutboxStore
import com.pocketqc.tracking.TrackedDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClientOutboxAndCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testOutboxQueueAndDurableAcknowledgment() {
        val outbox = ClientOutboxStore(tempFolder.root)
        assertEquals(0, outbox.getQueueDepth())

        val event1 = InspectionEvent(
            eventId = "EV-1",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "B1",
            stationName = "Line A",
            itemId = "i1",
            classId = 0,
            className = "Broken",
            confidence = 0.9f,
            clientTimestampUtc = 1000L
        )

        val event2 = InspectionEvent(
            eventId = "EV-2",
            deviceId = "node-a",
            sessionId = "s1",
            batchId = "B1",
            stationName = "Line A",
            itemId = "i2",
            classId = 2,
            className = "Intact",
            confidence = 0.95f,
            clientTimestampUtc = 1100L
        )

        assertTrue(outbox.enqueue(event1))
        assertTrue(outbox.enqueue(event2))
        // Duplicate enqueue of same eventId must return false
        assertFalse(outbox.enqueue(event1))
        assertEquals(2, outbox.getQueueDepth())

        // Peek pending
        val pending = outbox.getPendingEvents(10)
        assertEquals(2, pending.size)
        assertEquals("EV-1", pending[0].event.eventId)

        // Mark event1 acknowledged (server received and stored it)
        outbox.markAcknowledged("EV-1")
        assertEquals(1, outbox.getQueueDepth())

        // Fresh outbox store instance reading same folder must see persistent state
        val outboxRestored = ClientOutboxStore(tempFolder.root)
        assertEquals(1, outboxRestored.getQueueDepth())
        assertEquals("EV-2", outboxRestored.getPendingEvents(1)[0].event.eventId)
    }

    @Test
    fun testCoordinatorTrackingIntegrationAndOutboxEnqueue() {
        val coordinator = InspectionNodeCoordinator(tempFolder.root)

        // Setup mock connected state with active running batch
        val batch = DistributedBatch(
            batchId = "BATCH-RUN",
            state = DistributedBatchState.RUNNING
        )

        // Simulate newly tracked physical biscuits from CentroidDetectionTracker
        val trackedItem1 = TrackedDetection(
            trackId = 1,
            className = "Broken",
            boundingBox = BoundingBox(0.1f, 0.1f, 0.3f, 0.3f),
            confidence = 0.92f
        )
        val trackedItem2 = TrackedDetection(
            trackId = 2,
            className = "Intact",
            boundingBox = BoundingBox(0.4f, 0.4f, 0.6f, 0.6f),
            confidence = 0.98f
        )

        // Initially not inspecting: items must not be counted
        coordinator.onNewlyCountedItems(listOf(trackedItem1))
        assertEquals(0, coordinator.uiState.value.localItemCount)
    }
}
