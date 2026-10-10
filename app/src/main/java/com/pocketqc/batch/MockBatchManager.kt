package com.pocketqc.batch

import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.InspectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory batch manager pre-seeded with sample agri-food MSME inspection batches.
 */
class MockBatchManager : BatchManager {

    private val recentBatches = mutableListOf(
        Batch(
            batchId = "B-2026-024",
            productName = "Biscuit",
            inspectionCount = 24,
            passCount = 21,
            failCount = 3,
            defectRate = 0.125f,
            timestamp = System.currentTimeMillis() - 1800_000,
            status = BatchStatus.IN_PROGRESS
        ),
        Batch(
            batchId = "B-2026-023",
            productName = "Cashew Nut W320 Grade",
            inspectionCount = 120,
            passCount = 114,
            failCount = 6,
            defectRate = 0.05f,
            timestamp = System.currentTimeMillis() - 3600_000 * 3,
            status = BatchStatus.APPROVED
        ),
        Batch(
            batchId = "B-2026-022",
            productName = "Arabica Coffee Beans Grade A",
            inspectionCount = 200,
            passCount = 188,
            failCount = 12,
            defectRate = 0.06f,
            timestamp = System.currentTimeMillis() - 3600_000 * 24,
            status = BatchStatus.APPROVED
        ),
        Batch(
            batchId = "B-2026-021",
            productName = "Golden Raisins Extra Select",
            inspectionCount = 85,
            passCount = 71,
            failCount = 14,
            defectRate = 0.165f,
            timestamp = System.currentTimeMillis() - 3600_000 * 48,
            status = BatchStatus.REJECTED
        )
    )

    private val _currentBatch = MutableStateFlow<Batch?>(recentBatches[0])
    override val currentBatch: StateFlow<Batch?> = _currentBatch.asStateFlow()

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
        _currentBatch.value = newBatch
        return newBatch
    }

    override fun recordInspection(result: InspectionResult): Batch {
        val current = _currentBatch.value ?: startNewBatch(result.productName, "B-TEMP")
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
        _currentBatch.value = updated
        return updated
    }

    override fun completeCurrentBatch(): Batch? {
        val current = _currentBatch.value ?: return null
        val completed = current.copy(
            status = if (current.defectRate <= 0.15f) BatchStatus.APPROVED else BatchStatus.REJECTED
        )
        val index = recentBatches.indexOfFirst { it.batchId == completed.batchId }
        if (index >= 0) {
            recentBatches[index] = completed
        } else {
            recentBatches.add(0, completed)
        }
        _currentBatch.value = completed
        return completed
    }

    override fun getRecentBatches(): List<Batch> {
        return recentBatches.toList()
    }

    override fun saveBatch(batch: Batch) {
        val index = recentBatches.indexOfFirst { it.batchId == batch.batchId }
        if (index >= 0) {
            recentBatches[index] = batch
        } else {
            recentBatches.add(0, batch)
        }
        _currentBatch.value = batch
    }
}
