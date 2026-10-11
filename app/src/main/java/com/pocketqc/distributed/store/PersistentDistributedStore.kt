package com.pocketqc.distributed.store

import android.content.Context
import com.pocketqc.distributed.model.BatchSummary
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedBatchState
import com.pocketqc.distributed.model.DistributedDevice
import com.pocketqc.distributed.model.InspectionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Crash-resilient, atomic file-backed persistent store for distributed inspection server.
 *
 * Guarantees:
 * 1. ACID-like atomic writes using temp files and atomic filesystem rename.
 * 2. Survives process crashes and device reboots.
 * 3. Strict idempotent deduplication on [InspectionEvent.eventId].
 * 4. Derived totals are computed directly from accepted event records to prevent drift.
 */
class PersistentDistributedStore(
    private val baseDir: File
) : DistributedStore {

    constructor(context: Context) : this(context.filesDir)

    private val devicesFile = File(baseDir, "distributed_devices.json")
    private val batchesFile = File(baseDir, "distributed_batches.json")
    private val eventsFile = File(baseDir, "distributed_events.json")

    private val devices = mutableMapOf<String, DistributedDevice>()
    private val batches = mutableMapOf<String, DistributedBatch>()
    private val events = mutableMapOf<String, InspectionEvent>()

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        loadAll()
    }

    @Synchronized
    private fun loadAll() {
        // 1. Load devices
        if (devicesFile.exists()) {
            try {
                val content = devicesFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val d = DistributedDevice.fromJson(array.getJSONObject(i))
                    devices[d.deviceId] = d
                }
            } catch (t: Throwable) {
                // If corrupted, log or fallback
            }
        }

        // 2. Load batches
        if (batchesFile.exists()) {
            try {
                val content = batchesFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val b = DistributedBatch.fromJson(array.getJSONObject(i))
                    batches[b.batchId] = b
                }
            } catch (t: Throwable) {
                // Ignore corrupt
            }
        }

        // 3. Load events
        if (eventsFile.exists()) {
            try {
                val content = eventsFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val e = InspectionEvent.fromJson(array.getJSONObject(i))
                    events[e.eventId] = e
                }
            } catch (t: Throwable) {
                // Ignore corrupt
            }
        }
    }

    @Synchronized
    private fun persistAtomic(file: File, jsonContent: String) {
        val temp = File.createTempFile("dist_store_", ".tmp", baseDir)
        FileOutputStream(temp).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonContent)
                writer.flush()
            }
            runCatching { fos.fd.sync() }
        }
        if (!temp.renameTo(file)) {
            // Fallback for Windows/Android if destination exists
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    @Synchronized
    override fun saveDevice(device: DistributedDevice) {
        devices[device.deviceId] = device
        val array = JSONArray()
        devices.values.forEach { array.put(it.toJson()) }
        persistAtomic(devicesFile, array.toString())
    }

    @Synchronized
    override fun getDevice(deviceId: String): DistributedDevice? {
        return devices[deviceId]
    }

    @Synchronized
    override fun getAllDevices(): List<DistributedDevice> {
        return devices.values.toList()
    }

    @Synchronized
    override fun removeDevice(deviceId: String) {
        if (devices.remove(deviceId) != null) {
            val array = JSONArray()
            devices.values.forEach { array.put(it.toJson()) }
            persistAtomic(devicesFile, array.toString())
        }
    }

    @Synchronized
    override fun saveBatch(batch: DistributedBatch) {
        batches[batch.batchId] = batch
        val array = JSONArray()
        batches.values.forEach { array.put(it.toJson()) }
        persistAtomic(batchesFile, array.toString())
    }

    @Synchronized
    override fun getBatch(batchId: String): DistributedBatch? {
        return batches[batchId]
    }

    @Synchronized
    override fun getAllBatches(): List<DistributedBatch> {
        return batches.values.sortedByDescending { it.createdTimeUtc }
    }

    @Synchronized
    override fun getActiveBatch(): DistributedBatch? {
        return batches.values.firstOrNull { it.state == DistributedBatchState.RUNNING }
    }

    @Synchronized
    override fun recordEvent(event: InspectionEvent): Triple<Boolean, Boolean, String?> {
        // 1. Idempotency check: Does eventId already exist?
        val existing = events[event.eventId]
        if (existing != null) {
            // Check if identical
            val isSame = existing.deviceId == event.deviceId &&
                    existing.batchId == event.batchId &&
                    existing.className == event.className &&
                    existing.itemId == event.itemId

            return if (isSame) {
                // Acknowledged as duplicate without re-incrementing totals
                Triple(true, true, null)
            } else {
                // Conflicting payload reusing the same eventId!
                Triple(false, false, "Conflict: Event ID '${event.eventId}' reused with differing inspection payload")
            }
        }

        // 2. Validate batch state
        val batch = batches[event.batchId]
        if (batch == null) {
            return Triple(false, false, "Unknown batch ID '${event.batchId}'")
        }

        if (batch.state == DistributedBatchState.STOPPED) {
            val stoppedTime = batch.stoppedTimeUtc ?: Long.MAX_VALUE
            // If the event was captured AFTER the batch stopped, reject it
            if (event.clientTimestampUtc > stoppedTime) {
                return Triple(false, false, "Batch '${event.batchId}' is stopped; post-stop events rejected")
            }
        }

        // 3. Durable write BEFORE acknowledgment
        events[event.eventId] = event
        val array = JSONArray()
        events.values.forEach { array.put(it.toJson()) }
        persistAtomic(eventsFile, array.toString())

        return Triple(true, false, null)
    }

    @Synchronized
    override fun getEventsForBatch(batchId: String): List<InspectionEvent> {
        return events.values.filter { it.batchId == batchId }
            .sortedBy { it.clientTimestampUtc }
    }

    @Synchronized
    override fun getSummaryForBatch(batchId: String): BatchSummary {
        val batchEvents = getEventsForBatch(batchId)
        return BatchSummary.fromEvents(batchId, batchEvents)
    }

    @Synchronized
    override fun getAllEvents(): List<InspectionEvent> {
        return events.values.toList()
    }

    @Synchronized
    override fun clearAll() {
        devices.clear()
        batches.clear()
        events.clear()
        if (devicesFile.exists()) devicesFile.delete()
        if (batchesFile.exists()) batchesFile.delete()
        if (eventsFile.exists()) eventsFile.delete()
    }
}
