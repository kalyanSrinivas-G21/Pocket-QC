package com.pocketqc.distributed.store

import android.content.Context
import com.pocketqc.distributed.model.InspectionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class QueuedEvent(
    val event: InspectionEvent,
    val enqueuedTimeUtc: Long = System.currentTimeMillis(),
    var attemptCount: Int = 0,
    var lastAttemptTimeUtc: Long = 0L,
    var lastError: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event", event.toJson())
        put("enqueuedTimeUtc", enqueuedTimeUtc)
        put("attemptCount", attemptCount)
        put("lastAttemptTimeUtc", lastAttemptTimeUtc)
        if (lastError != null) put("lastError", lastError)
    }

    companion object {
        fun fromJson(json: JSONObject): QueuedEvent {
            return QueuedEvent(
                event = InspectionEvent.fromJson(json.getJSONObject("event")),
                enqueuedTimeUtc = json.getLong("enqueuedTimeUtc"),
                attemptCount = json.optInt("attemptCount", 0),
                lastAttemptTimeUtc = json.optLong("lastAttemptTimeUtc", 0L),
                lastError = if (json.has("lastError")) json.getString("lastError") else null
            )
        }
    }
}

/**
 * Persistent client-side outbox for inspection nodes (Devices 2 & 3).
 *
 * Ensures events captured during network interruptions or server downtime
 * are safely persisted and retransmitted once connectivity is restored.
 */
class ClientOutboxStore(
    private val baseDir: File,
    private val maxQueueSize: Int = 5000
) {
    constructor(context: Context) : this(context.filesDir)

    private val outboxFile = File(baseDir, "client_outbox.json")
    private val queue = mutableListOf<QueuedEvent>()

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        load()
    }

    @Synchronized
    private fun load() {
        if (outboxFile.exists()) {
            try {
                val content = outboxFile.readText(StandardCharsets.UTF_8)
                val array = JSONArray(content)
                queue.clear()
                for (i in 0 until array.length()) {
                    queue.add(QueuedEvent.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Throwable) {
                // If corrupt, start fresh
            }
        }
    }

    @Synchronized
    private fun persistAtomic() {
        val temp = File.createTempFile("outbox_", ".tmp", baseDir)
        val array = JSONArray()
        queue.forEach { array.put(it.toJson()) }

        FileOutputStream(temp).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.write(array.toString())
                writer.flush()
            }
            runCatching { fos.fd.sync() }
        }
        if (!temp.renameTo(outboxFile)) {
            if (outboxFile.exists()) outboxFile.delete()
            temp.renameTo(outboxFile)
        }
    }

    @Synchronized
    fun enqueue(event: InspectionEvent): Boolean {
        // Idempotency: don't double-queue same event ID
        if (queue.any { it.event.eventId == event.eventId }) {
            return false
        }
        if (queue.size >= maxQueueSize) {
            // Drop oldest or reject
            queue.removeAt(0)
        }
        queue.add(QueuedEvent(event))
        persistAtomic()
        return true
    }

    @Synchronized
    fun getPendingEvents(limit: Int = 50): List<QueuedEvent> {
        return queue.take(limit)
    }

    @Synchronized
    fun markAcknowledged(eventId: String) {
        val removed = queue.removeAll { it.event.eventId == eventId }
        if (removed) {
            persistAtomic()
        }
    }

    @Synchronized
    fun recordAttemptFailed(eventId: String, error: String) {
        val item = queue.firstOrNull { it.event.eventId == eventId }
        if (item != null) {
            item.attemptCount++
            item.lastAttemptTimeUtc = System.currentTimeMillis()
            item.lastError = error
            persistAtomic()
        }
    }

    @Synchronized
    fun getQueueDepth(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
        if (outboxFile.exists()) outboxFile.delete()
    }
}
