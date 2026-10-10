package com.pocketqc.batch

import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.InspectionResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for tracking, persisting, and calculating batch-level statistics during QC runs.
 */
interface BatchManager {
    val currentBatch: StateFlow<Batch?>

    fun startNewBatch(productName: String, batchId: String): Batch
    fun recordInspection(result: InspectionResult): Batch
    fun completeCurrentBatch(): Batch?
    fun getRecentBatches(): List<Batch>
    fun saveBatch(batch: Batch) { /* default fallback */ }
    fun getBatchById(batchId: String): Batch? = getRecentBatches().find { it.batchId == batchId }
}
