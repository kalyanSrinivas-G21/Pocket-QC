package com.pocketqc.distributed.model

import org.json.JSONObject

enum class DistributedBatchState {
    CREATED,
    RUNNING,
    STOPPED
}

/**
 * Shared distributed inspection batch coordinated by Device 1 (Server).
 */
data class DistributedBatch(
    val batchId: String,
    val productName: String = "Biscuit Conveyor Lot",
    val state: DistributedBatchState = DistributedBatchState.CREATED,
    val createdTimeUtc: Long = System.currentTimeMillis(),
    val startedTimeUtc: Long? = null,
    val stoppedTimeUtc: Long? = null,
    val inspectionMode: String = "2D_DEFECT_SORT"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("batchId", batchId)
            put("productName", productName)
            put("state", state.name)
            put("createdTimeUtc", createdTimeUtc)
            if (startedTimeUtc != null) put("startedTimeUtc", startedTimeUtc)
            if (stoppedTimeUtc != null) put("stoppedTimeUtc", stoppedTimeUtc)
            put("inspectionMode", inspectionMode)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DistributedBatch {
            return DistributedBatch(
                batchId = json.getString("batchId"),
                productName = json.optString("productName", "Biscuit Conveyor Lot"),
                state = DistributedBatchState.valueOf(json.optString("state", DistributedBatchState.CREATED.name)),
                createdTimeUtc = json.getLong("createdTimeUtc"),
                startedTimeUtc = if (json.has("startedTimeUtc")) json.getLong("startedTimeUtc") else null,
                stoppedTimeUtc = if (json.has("stoppedTimeUtc")) json.getLong("stoppedTimeUtc") else null,
                inspectionMode = json.optString("inspectionMode", "2D_DEFECT_SORT")
            )
        }
    }
}
