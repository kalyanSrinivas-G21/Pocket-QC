package com.pocketqc.distributed.model

import org.json.JSONObject

/**
 * Aggregated inspection metrics derived authoritatively from accepted [InspectionEvent] records.
 * Supports multi-station combined analysis (2D Defect Sort + 3D Volume Check + UV Screening).
 */
data class BatchSummary(
    val batchId: String,
    val totalInspected: Int = 0,
    val passedCount: Int = 0,
    val defectiveCount: Int = 0,
    // 2D Defect Sort stream
    val brokenCount: Int = 0,
    val fractureCount: Int = 0,
    val intactCount: Int = 0,
    val count2dTotal: Int = 0,
    val count2dPass: Int = 0,
    val count2dDefect: Int = 0,
    // 3D Volume Inspection stream
    val count3dTotal: Int = 0,
    val count3dPass: Int = 0,
    val count3dDefect: Int = 0,
    val count3dInconclusive: Int = 0,
    // UV Fluorescence stream
    val countUvTotal: Int = 0,
    val countUvPass: Int = 0,
    val countUvDefect: Int = 0,
    // Station telemetry & modes
    val deviceCounts: Map<String, Int> = emptyMap(),
    val deviceDefectCounts: Map<String, Int> = emptyMap(),
    val devicePassCounts: Map<String, Int> = emptyMap(),
    val deviceModes: Map<String, String> = emptyMap(),
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
            put("count2dTotal", count2dTotal)
            put("count2dPass", count2dPass)
            put("count2dDefect", count2dDefect)
            put("count3dTotal", count3dTotal)
            put("count3dPass", count3dPass)
            put("count3dDefect", count3dDefect)
            put("count3dInconclusive", count3dInconclusive)
            put("countUvTotal", countUvTotal)
            put("countUvPass", countUvPass)
            put("countUvDefect", countUvDefect)
            put("defectRate", defectRate.toDouble())
            put("passRate", passRate.toDouble())

            val devObj = JSONObject()
            deviceCounts.forEach { (dev, count) -> devObj.put(dev, count) }
            put("deviceCounts", devObj)

            val devDefObj = JSONObject()
            deviceDefectCounts.forEach { (dev, count) -> devDefObj.put(dev, count) }
            put("deviceDefectCounts", devDefObj)

            val devPassObj = JSONObject()
            devicePassCounts.forEach { (dev, count) -> devPassObj.put(dev, count) }
            put("devicePassCounts", devPassObj)

            val devModesObj = JSONObject()
            deviceModes.forEach { (dev, mode) -> devModesObj.put(dev, mode) }
            put("deviceModes", devModesObj)
        }
    }

    companion object {
        fun fromEvents(batchId: String, events: List<InspectionEvent>): BatchSummary {
            var total = 0
            var passed = 0
            var defective = 0

            // 2D breakdown
            var count2dTotal = 0
            var count2dPass = 0
            var count2dDefect = 0
            var broken = 0
            var fracture = 0
            var intact = 0

            // 3D breakdown
            var count3dTotal = 0
            var count3dPass = 0
            var count3dDefect = 0
            var count3dInconclusive = 0

            // UV breakdown
            var countUvTotal = 0
            var countUvPass = 0
            var countUvDefect = 0

            val devCounts = mutableMapOf<String, Int>()
            val devDefects = mutableMapOf<String, Int>()
            val devPasses = mutableMapOf<String, Int>()
            val devModes = mutableMapOf<String, String>()

            for (e in events) {
                total++
                val devKey = if (e.stationName.isNotBlank()) e.stationName else e.deviceId
                devCounts[devKey] = (devCounts[devKey] ?: 0) + 1
                if (e.inspectionMode.isNotBlank()) {
                    devModes[devKey] = e.inspectionMode
                }

                val is3d = e.inspectionMode.contains("VOLUME", ignoreCase = true) ||
                        e.inspectionMode.contains("3D", ignoreCase = true) ||
                        e.modelId.contains("fill", ignoreCase = true) ||
                        e.modelId.contains("geometric", ignoreCase = true) ||
                        e.itemId.contains("bottle", ignoreCase = true)

                val isUv = e.inspectionMode.contains("UV", ignoreCase = true) ||
                        e.modelId.contains("aflatoxin", ignoreCase = true)

                if (is3d) {
                    count3dTotal++
                    val isPass = e.className.equals("PASS", ignoreCase = true) ||
                            (!e.isDefect && !e.className.contains("TOLERANCE", ignoreCase = true))
                    val isInconclusive = e.className.contains("INCONCLUSIVE", ignoreCase = true)

                    if (isPass) {
                        count3dPass++
                        passed++
                        devPasses[devKey] = (devPasses[devKey] ?: 0) + 1
                    } else {
                        count3dDefect++
                        defective++
                        devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                        if (isInconclusive) {
                            count3dInconclusive++
                        }
                    }
                } else if (isUv) {
                    countUvTotal++
                    if (e.isDefect) {
                        countUvDefect++
                        defective++
                        devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                    } else {
                        countUvPass++
                        passed++
                        devPasses[devKey] = (devPasses[devKey] ?: 0) + 1
                    }
                } else {
                    // 2D Defect Sort
                    count2dTotal++
                    when {
                        e.className.equals("Broken", ignoreCase = true) -> {
                            broken++
                            count2dDefect++
                            defective++
                            devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                        }
                        e.className.equals("Fracture", ignoreCase = true) -> {
                            fracture++
                            count2dDefect++
                            defective++
                            devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                        }
                        e.className.equals("Intact", ignoreCase = true) -> {
                            intact++
                            count2dPass++
                            passed++
                            devPasses[devKey] = (devPasses[devKey] ?: 0) + 1
                        }
                        else -> {
                            if (e.isDefect) {
                                broken++
                                count2dDefect++
                                defective++
                                devDefects[devKey] = (devDefects[devKey] ?: 0) + 1
                            } else {
                                intact++
                                count2dPass++
                                passed++
                                devPasses[devKey] = (devPasses[devKey] ?: 0) + 1
                            }
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
                count2dTotal = count2dTotal,
                count2dPass = count2dPass,
                count2dDefect = count2dDefect,
                count3dTotal = count3dTotal,
                count3dPass = count3dPass,
                count3dDefect = count3dDefect,
                count3dInconclusive = count3dInconclusive,
                countUvTotal = countUvTotal,
                countUvPass = countUvPass,
                countUvDefect = countUvDefect,
                deviceCounts = devCounts,
                deviceDefectCounts = devDefects,
                devicePassCounts = devPasses,
                deviceModes = devModes,
                defectRate = dRate,
                passRate = pRate
            )
        }

        fun fromJson(json: JSONObject): BatchSummary {
            val devCounts = mutableMapOf<String, Int>()
            if (json.has("deviceCounts")) {
                val obj = json.getJSONObject("deviceCounts")
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    devCounts[k] = obj.getInt(k)
                }
            }

            val devDefects = mutableMapOf<String, Int>()
            if (json.has("deviceDefectCounts")) {
                val obj = json.getJSONObject("deviceDefectCounts")
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    devDefects[k] = obj.getInt(k)
                }
            }

            val devPasses = mutableMapOf<String, Int>()
            if (json.has("devicePassCounts")) {
                val obj = json.getJSONObject("devicePassCounts")
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    devPasses[k] = obj.getInt(k)
                }
            }

            val devModes = mutableMapOf<String, String>()
            if (json.has("deviceModes")) {
                val obj = json.getJSONObject("deviceModes")
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    devModes[k] = obj.getString(k)
                }
            }

            val total = json.optInt("totalInspected", 0)
            val defective = json.optInt("defectiveCount", 0)
            val passed = json.optInt("passedCount", 0)
            val dRate = json.optDouble("defectRate", if (total > 0) (defective.toDouble() / total) * 100.0 else 0.0).toFloat()
            val pRate = json.optDouble("passRate", if (total > 0) (passed.toDouble() / total) * 100.0 else 0.0).toFloat()

            return BatchSummary(
                batchId = json.getString("batchId"),
                totalInspected = total,
                passedCount = passed,
                defectiveCount = defective,
                brokenCount = json.optInt("brokenCount", 0),
                fractureCount = json.optInt("fractureCount", 0),
                intactCount = json.optInt("intactCount", 0),
                count2dTotal = json.optInt("count2dTotal", 0),
                count2dPass = json.optInt("count2dPass", 0),
                count2dDefect = json.optInt("count2dDefect", 0),
                count3dTotal = json.optInt("count3dTotal", 0),
                count3dPass = json.optInt("count3dPass", 0),
                count3dDefect = json.optInt("count3dDefect", 0),
                count3dInconclusive = json.optInt("count3dInconclusive", 0),
                countUvTotal = json.optInt("countUvTotal", 0),
                countUvPass = json.optInt("countUvPass", 0),
                countUvDefect = json.optInt("countUvDefect", 0),
                deviceCounts = devCounts,
                deviceDefectCounts = devDefects,
                devicePassCounts = devPasses,
                deviceModes = devModes,
                defectRate = dRate,
                passRate = pRate
            )
        }
    }
}
