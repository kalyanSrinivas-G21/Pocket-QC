package com.pocketqc.batch

import android.content.Context
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.ui.components.InspectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local persistent batch manager that stores completed and active QC batches
 * to internal app storage (JSON file).
 *
 * Ensures batches survive app exits, process recreation, and device reboots completely offline.
 */
class PersistentBatchManager(
    baseDir: File,
    fileName: String = "batches.json"
) : BatchManager {

    constructor(context: Context, fileName: String = "batches.json") : this(context.filesDir, fileName)

    private val storageFile = File(baseDir, fileName)
    private val batches = mutableListOf<Batch>()

    private val _currentBatch = MutableStateFlow<Batch?>(null)
    override val currentBatch: StateFlow<Batch?> = _currentBatch.asStateFlow()

    init {
        loadBatches()
        _currentBatch.value = batches.firstOrNull()
    }

    fun seedDefaultBatches() {
        val now = System.currentTimeMillis()
        batches.addAll(
            listOf(
                Batch(
                    batchId = "PQ-20261007-0024",
                    productName = "Biscuit",
                    inspectionCount = 24,
                    passCount = 21,
                    failCount = 3,
                    defectRate = 0.125f,
                    timestamp = now - 1800_000,
                    status = BatchStatus.APPROVED,
                    mode = InspectionMode.DEFECT_SORT_2D,
                    brokenCount = 2,
                    fractureCount = 1,
                    intactCount = 21,
                    qrPayload = """{"app":"PocketQC","id":"PQ-20261007-0024","type":"2D","total":24,"pass":21,"defect":3,"rate":12.50,"result":"PASS"}"""
                ),
                Batch(
                    batchId = "PQ-20261007-0023",
                    productName = "Red Chilli Sample Lot",
                    inspectionCount = 12,
                    passCount = 11,
                    failCount = 1,
                    defectRate = 0.083f,
                    timestamp = now - 3600_000 * 3,
                    status = BatchStatus.APPROVED,
                    mode = InspectionMode.UV_AFLATOXIN,
                    flaggedCount = 1,
                    qrPayload = """{"app":"PocketQC","id":"PQ-20261007-0023","type":"UV","total":12,"pass":11,"defect":1,"rate":8.33,"result":"PASS"}"""
                ),
                Batch(
                    batchId = "PQ-20261007-0022",
                    productName = "Bottled Beverage 500ml",
                    inspectionCount = 18,
                    passCount = 17,
                    failCount = 1,
                    defectRate = 0.055f,
                    timestamp = now - 3600_000 * 24,
                    status = BatchStatus.APPROVED,
                    mode = InspectionMode.VOLUME_CHECK_3D,
                    requiredFillPercent = 80f,
                    averageDetectedFillPercent = 80.8f,
                    qrPayload = """{"app":"PocketQC","id":"PQ-20261007-0022","type":"3D","total":18,"pass":17,"defect":1,"rate":5.56,"result":"PASS"}"""
                )
            )
        )
        persistBatches()
    }

    @Synchronized
    private fun loadBatches() {
        if (!storageFile.exists()) return
        runCatching {
            val jsonStr = storageFile.readText()
            val jsonArray = JSONArray(jsonStr)
            batches.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val modeStr = obj.optString("mode", InspectionMode.DEFECT_SORT_2D.name)
                val mode = runCatching { InspectionMode.valueOf(modeStr) }.getOrDefault(InspectionMode.DEFECT_SORT_2D)
                val statusStr = obj.optString("status", BatchStatus.IN_PROGRESS.name)
                val status = runCatching { BatchStatus.valueOf(statusStr) }.getOrDefault(BatchStatus.IN_PROGRESS)

                batches.add(
                    Batch(
                        batchId = obj.getString("batchId"),
                        productName = obj.getString("productName"),
                        inspectionCount = obj.getInt("inspectionCount"),
                        passCount = obj.getInt("passCount"),
                        failCount = obj.getInt("failCount"),
                        defectRate = obj.getDouble("defectRate").toFloat(),
                        timestamp = obj.getLong("timestamp"),
                        status = status,
                        mode = mode,
                        startTime = obj.optLong("startTime", obj.getLong("timestamp")),
                        endTime = obj.optLong("endTime", obj.getLong("timestamp")),
                        brokenCount = obj.optInt("brokenCount", 0),
                        fractureCount = obj.optInt("fractureCount", 0),
                        intactCount = obj.optInt("intactCount", 0),
                        flaggedCount = obj.optInt("flaggedCount", 0),
                        requiredFillPercent = obj.optDouble("requiredFillPercent", 0.0).toFloat(),
                        averageDetectedFillPercent = obj.optDouble("averageDetectedFillPercent", 0.0).toFloat(),
                        qrPayload = obj.optString("qrPayload", "")
                    )
                )
            }
        }
    }

    @Synchronized
    private fun persistBatches() {
        runCatching {
            val jsonArray = JSONArray()
            for (b in batches) {
                val obj = JSONObject().apply {
                    put("batchId", b.batchId)
                    put("productName", b.productName)
                    put("inspectionCount", b.inspectionCount)
                    put("passCount", b.passCount)
                    put("failCount", b.failCount)
                    put("defectRate", b.defectRate.toDouble())
                    put("timestamp", b.timestamp)
                    put("status", b.status.name)
                    put("mode", b.mode.name)
                    put("startTime", b.startTime)
                    put("endTime", b.endTime)
                    put("brokenCount", b.brokenCount)
                    put("fractureCount", b.fractureCount)
                    put("intactCount", b.intactCount)
                    put("flaggedCount", b.flaggedCount)
                    put("requiredFillPercent", b.requiredFillPercent.toDouble())
                    put("averageDetectedFillPercent", b.averageDetectedFillPercent.toDouble())
                    put("qrPayload", b.qrPayload)
                }
                jsonArray.put(obj)
            }
            storageFile.writeText(jsonArray.toString())
        }
    }

    @Synchronized
    override fun startNewBatch(productName: String, batchId: String): Batch {
        val newBatch = Batch(
            batchId = batchId,
            productName = productName,
            inspectionCount = 0,
            passCount = 0,
            failCount = 0,
            defectRate = 0f,
            timestamp = System.currentTimeMillis(),
            status = BatchStatus.IN_PROGRESS
        )
        batches.add(0, newBatch)
        _currentBatch.value = newBatch
        persistBatches()
        return newBatch
    }

    @Synchronized
    override fun recordInspection(result: InspectionResult): Batch {
        val current = _currentBatch.value ?: startNewBatch(result.productName, "PQ-${System.currentTimeMillis()}")
        val updatedCount = current.inspectionCount + result.totalInspected
        val updatedPass = current.passCount + result.passed
        val updatedFail = current.failCount + result.failed
        val updatedRate = if (updatedCount > 0) updatedFail.toFloat() / updatedCount.toFloat() else 0f

        val updated = current.copy(
            inspectionCount = updatedCount,
            passCount = updatedPass,
            failCount = updatedFail,
            defectRate = updatedRate
        )
        val index = batches.indexOfFirst { it.batchId == updated.batchId }
        if (index >= 0) {
            batches[index] = updated
        } else {
            batches.add(0, updated)
        }
        _currentBatch.value = updated
        persistBatches()
        return updated
    }

    @Synchronized
    override fun completeCurrentBatch(): Batch? {
        val current = _currentBatch.value ?: return null
        val completed = current.copy(
            status = if (current.defectRate <= 0.15f) BatchStatus.APPROVED else BatchStatus.REJECTED
        )
        saveBatch(completed)
        return completed
    }

    @Synchronized
    override fun getRecentBatches(): List<Batch> {
        return batches.toList()
    }

    @Synchronized
    override fun saveBatch(batch: Batch) {
        val index = batches.indexOfFirst { it.batchId == batch.batchId }
        if (index >= 0) {
            batches[index] = batch
        } else {
            batches.add(0, batch)
        }
        _currentBatch.value = batch
        persistBatches()
    }

    @Synchronized
    override fun getBatchById(batchId: String): Batch? {
        return batches.find { it.batchId == batchId }
    }

    @Synchronized
    fun clearAllBatches() {
        batches.clear()
        _currentBatch.value = null
        persistBatches()
    }
}
