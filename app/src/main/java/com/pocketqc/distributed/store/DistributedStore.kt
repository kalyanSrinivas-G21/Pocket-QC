package com.pocketqc.distributed.store

import com.pocketqc.distributed.model.BatchSummary
import com.pocketqc.distributed.model.DistributedBatch
import com.pocketqc.distributed.model.DistributedDevice
import com.pocketqc.distributed.model.InspectionEvent

interface DistributedStore {
    fun saveDevice(device: DistributedDevice)
    fun getDevice(deviceId: String): DistributedDevice?
    fun getAllDevices(): List<DistributedDevice>
    fun removeDevice(deviceId: String)

    fun saveBatch(batch: DistributedBatch)
    fun getBatch(batchId: String): DistributedBatch?
    fun getAllBatches(): List<DistributedBatch>
    fun getActiveBatch(): DistributedBatch?

    /**
     * Atomically stores an inspection event if not already present.
     * Returns Triple(accepted: Boolean, isDuplicate: Boolean, errorMessage: String?).
     */
    fun recordEvent(event: InspectionEvent): Triple<Boolean, Boolean, String?>

    fun getEventsForBatch(batchId: String): List<InspectionEvent>
    fun getSummaryForBatch(batchId: String): BatchSummary
    fun getAllEvents(): List<InspectionEvent>
    fun clearAll()
}
