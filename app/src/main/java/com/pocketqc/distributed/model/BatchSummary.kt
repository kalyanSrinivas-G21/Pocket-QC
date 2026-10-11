package com.pocketqc.distributed.model

import org.json.JSONObject

/**
 * Aggregated inspection metrics derived authoritatively from accepted [InspectionEvent] records.
 */
data class BatchSummary(
    val batchId: String,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val defectiveCount: Int = 0,
    val brokenCount: Int = 0,
    val fractureCount: Int = 0,
    val intactCount: Int = 0,
    val deviceCounts: Map<String, Int> = emptyMap(),
    val deviceDefectCounts: Map<String, Int> = emptyMap(),
    val defectRate: Float = if (totalInspected > 0) (defectiveCount.toFloat() / totalInspected.toFloat()) * 100f else 0f,
    val passRate: Float = if (totalInspected > 0) (passedCount.toFloat() / totalInspected.toFloat()) * 100f else 0f
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("batchId", batchId)
            put("totalInspected", totalInspected)
            put("passedCount", passedCount)
            put("defectiveCount", defectiveCount)
            put("brokenCount", brokenCount)
            put("fractureCount", fractureCount)
            put("intactCount", intactCount)
            put("defectRate", defectRate.toDouble())
            put("passRate", passRate.toDouble())

            val devObj = JSONObject()
            deviceCounts.forEach { (dev, count) -> devObj.put(dev, count) }
            put("deviceCounts", devObj)

            val devDefObj = JSONObject()
            deviceDefectCounts.forEach { (dev, count) -> devDefObj.put(dev, count) }
            put("deviceDefectCounts", devDefObj)
        }
    }

    companion object {
        fun fromEvents(batchId: String, events: List<InspectionEvent>): BatchSummary {
            var total = 0
            var passed = 0
            var defective = 0
            var broken = 0
            var fracture = 0
            var intact = 0
            val devCounts = mutableMapOf<String, Int>()
            val devDefects = mutableMapOf<String, Int>()

            for (e in events) {
                total++
                val devKey = if (e.stationName.isNotBlank()) e.stationName else e.deviceId
                devCounts[devKey] = (devCounts[devKey] ?: 0) + 1

                when {
                    e.className.equals("Broken", ignoreCase = true) -> {
                        broken++
                        defective++
                        devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                    }
                    e.className.equals("Fracture", ignoreCase = true) -> {
                        fracture++
                        defective++
                        devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                    }
                    e.className.equals("Intact", ignoreCase = true) -> {
                        intact++
                        passed++
                    }
                    else -> {
                        if (e.isDefect) {
                            broken++
                            defective++
                            devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                        } else {
                            intact++
                            passed++
                        }
                    }
                }
            }

            val dRate = if (total > 0) (defective.toFloat() / total.toFloat()) * 100f else 0f
            val pRate = if (total > 0) (passed.toFloat() / total.toFloat()) * 100f else 0f

            return BatchSummary(
                batchId = batchId,
                totalInspected = total,
                passedCount = passed,
                defectiveCount = defective,
                brokenCount = broken,
                fractureCount = fracture,
                intactCount = intact,
                deviceCounts = devCounts,
                deviceDefectCounts = devDefects,
                defectRate = dRate,
                passRate = pRate
            )
        }
    }
}
