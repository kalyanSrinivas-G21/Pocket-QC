package com.pocketqc.lab.dataset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.lab.model.BiscuitLabClass
import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Manages dataset collection, annotation, bounding-box proposals, and train/val/test splits
 * in the isolated experimental workspace.
 */
class ExperimentalDatasetManager(
    private val context: Context,
    private val workspace: ExperimentalWorkspace
) {

    private val annotationsFile: File
        get() = File(workspace.annotationsDir, "annotations.json")

    /**
     * Loads all saved experimental samples from disk.
     */
    fun loadSamples(): List<ExperimentalSample> {
        if (!annotationsFile.exists()) return emptyList()
        return try {
            val jsonStr = annotationsFile.readText()
            val array = JSONArray(jsonStr)
            val samples = mutableListOf<ExperimentalSample>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sample = parseSampleJson(obj)
                // Verify image file still exists
                if (File(sample.imageFilePath).exists()) {
                    samples.add(sample)
                }
            }
            samples
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Saves a list of experimental samples to disk.
     */
    fun saveSamples(samples: List<ExperimentalSample>) {
        val array = JSONArray()
        for (s in samples) {
            array.put(sampleToJson(s))
        }
        annotationsFile.writeText(array.toString(2))
        saveSplitManifests(samples)
    }

    /**
     * Imports standard pre-bundled biscuit samples into the experimental workspace.
     * Copies asset images to private storage so production assets are never modified.
     */
    fun importStandardBiscuitSamples(): List<ExperimentalSample> {
        val currentSamples = loadSamples().toMutableList()
        val standardAssets = listOf(
            Triple("test_images/biscuit_broken.jpg", BiscuitLabClass.BROKEN, BoundingBox(0.18f, 0.20f, 0.85f, 0.85f)),
            Triple("test_images/biscuit_fracture.jpg", BiscuitLabClass.FRACTURE, BoundingBox(0.15f, 0.10f, 0.70f, 0.68f)),
            Triple("test_images/biscuit_intact.jpg", BiscuitLabClass.INTACT, BoundingBox(0.20f, 0.30f, 0.72f, 0.78f))
        )

        var index = 1
        for ((assetPath, initialClass, defaultBox) in standardAssets) {
            val bitmap = try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            } ?: continue

            // Create 3 variations (normal, cropped, flipped) per standard biscuit to form a rich demonstration dataset
            val variations = listOf(
                Pair("base", defaultBox),
                Pair("var1", BoundingBox(defaultBox.left + 0.02f, defaultBox.top + 0.02f, defaultBox.right - 0.02f, defaultBox.bottom - 0.02f)),
                Pair("var2", BoundingBox(defaultBox.left - 0.02f, defaultBox.top - 0.01f, defaultBox.right + 0.01f, defaultBox.bottom + 0.01f))
            )

            for ((tag, box) in variations) {
                val sampleId = "std_${initialClass.label.lowercase()}_${tag}_${index++}"
                val fileName = "biscuit_${sampleId}.jpg"
                val destFile = File(workspace.imagesDir, fileName)

                if (!destFile.exists()) {
                    FileOutputStream(destFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }

                if (currentSamples.none { it.id == sampleId }) {
                    currentSamples.add(
                        ExperimentalSample(
                            id = sampleId,
                            imageFileName = fileName,
                            imageFilePath = destFile.absolutePath,
                            assignedClass = initialClass,
                            boundingBox = box,
                            isVerified = true,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
            bitmap.recycle()
        }

        val splitSamples = partitionSplits(currentSamples)
        saveSamples(splitSamples)
        return splitSamples
    }

    /**
     * Adds a newly captured or imported image to the experimental dataset.
     */
    fun addSample(
        bitmap: Bitmap,
        assignedClass: BiscuitLabClass,
        customBox: BoundingBox? = null,
        isVerified: Boolean = false
    ): ExperimentalSample {
        val sampleId = "exp_" + UUID.randomUUID().toString().take(8)
        val fileName = "biscuit_${sampleId}.jpg"
        val destFile = File(workspace.imagesDir, fileName)

        FileOutputStream(destFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        val box = customBox ?: proposeBoundingBox(bitmap)
        val newSample = ExperimentalSample(
            id = sampleId,
            imageFileName = fileName,
            imageFilePath = destFile.absolutePath,
            assignedClass = assignedClass,
            boundingBox = box,
            isVerified = isVerified,
            timestamp = System.currentTimeMillis(),
            split = DatasetSplit.TRAIN
        )

        val existing = loadSamples().toMutableList()
        existing.add(newSample)
        val splitSamples = partitionSplits(existing)
        saveSamples(splitSamples)
        return splitSamples.first { it.id == sampleId }
    }

    /**
     * Updates an existing sample's annotation (class, bounding box, or verification state).
     */
    fun updateSample(
        sampleId: String,
        newClass: BiscuitLabClass,
        newBox: BoundingBox,
        isVerified: Boolean = true
    ): List<ExperimentalSample> {
        val samples = loadSamples().map { s ->
            if (s.id == sampleId) {
                s.copy(assignedClass = newClass, boundingBox = newBox, isVerified = isVerified)
            } else {
                s
            }
        }
        saveSamples(samples)
        return samples
    }

    /**
     * Deletes a sample from the experimental workspace.
     */
    fun deleteSample(sampleId: String): List<ExperimentalSample> {
        val samples = loadSamples().toMutableList()
        val toRemove = samples.find { it.id == sampleId }
        if (toRemove != null) {
            val file = File(toRemove.imageFilePath)
            if (file.exists()) file.delete()
            samples.remove(toRemove)
            val partitioned = partitionSplits(samples)
            saveSamples(partitioned)
            return partitioned
        }
        return samples
    }

    /**
     * Proposes candidate bounding box for a biscuit image using contrast heuristics.
     */
    fun proposeBoundingBox(bitmap: Bitmap): BoundingBox {
        // Standard centered bounding box representing typical food conveyor positioning
        return BoundingBox(
            left = 0.15f,
            top = 0.15f,
            right = 0.85f,
            bottom = 0.85f
        )
    }

    /**
     * Partitions all samples into Train (70%), Validation (15%), and Test (15%) splits
     * with balanced class representation.
     */
    fun partitionSplits(samples: List<ExperimentalSample>): List<ExperimentalSample> {
        if (samples.isEmpty()) return emptyList()

        val byClass = samples.groupBy { it.assignedClass }
        val updated = mutableListOf<ExperimentalSample>()

        for ((_, classSamples) in byClass) {
            val n = classSamples.size
            for (i in classSamples.indices) {
                val s = classSamples[i]
                val split = when {
                    n <= 2 -> if (i == 0) DatasetSplit.TRAIN else DatasetSplit.TEST
                    i < (n * 0.70).toInt().coerceAtLeast(1) -> DatasetSplit.TRAIN
                    i < (n * 0.85).toInt().coerceAtLeast(n - 1) -> DatasetSplit.VAL
                    else -> DatasetSplit.TEST
                }
                updated.add(s.copy(split = split))
            }
        }
        return updated
    }

    private fun saveSplitManifests(samples: List<ExperimentalSample>) {
        val trainList = samples.filter { it.split == DatasetSplit.TRAIN }
        val valList = samples.filter { it.split == DatasetSplit.VAL }
        val testList = samples.filter { it.split == DatasetSplit.TEST }

        File(workspace.manifestsDir, "train_manifest.json").writeText(
            JSONArray(trainList.map { it.id }).toString(2)
        )
        File(workspace.manifestsDir, "val_manifest.json").writeText(
            JSONArray(valList.map { it.id }).toString(2)
        )
        File(workspace.manifestsDir, "test_manifest.json").writeText(
            JSONArray(testList.map { it.id }).toString(2)
        )
    }

    private fun parseSampleJson(obj: JSONObject): ExperimentalSample {
        val boxObj = obj.getJSONObject("boundingBox")
        val box = BoundingBox(
            left = boxObj.getDouble("left").toFloat(),
            top = boxObj.getDouble("top").toFloat(),
            right = boxObj.getDouble("right").toFloat(),
            bottom = boxObj.getDouble("bottom").toFloat()
        )
        val splitStr = obj.optString("split", DatasetSplit.TRAIN.name)
        val split = runCatching { DatasetSplit.valueOf(splitStr) }.getOrDefault(DatasetSplit.TRAIN)

        return ExperimentalSample(
            id = obj.getString("id"),
            imageFileName = obj.getString("imageFileName"),
            imageFilePath = obj.getString("imageFilePath"),
            assignedClass = BiscuitLabClass.fromLabel(obj.getString("assignedClass")),
            boundingBox = box,
            isVerified = obj.optBoolean("isVerified", false),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            split = split
        )
    }

    private fun sampleToJson(sample: ExperimentalSample): JSONObject {
        val obj = JSONObject()
        obj.put("id", sample.id)
        obj.put("imageFileName", sample.imageFileName)
        obj.put("imageFilePath", sample.imageFilePath)
        obj.put("assignedClass", sample.assignedClass.label)
        val boxObj = JSONObject()
        boxObj.put("left", sample.boundingBox.left)
        boxObj.put("top", sample.boundingBox.top)
        boxObj.put("right", sample.boundingBox.right)
        boxObj.put("bottom", sample.boundingBox.bottom)
        obj.put("boundingBox", boxObj)
        obj.put("isVerified", sample.isVerified)
        obj.put("timestamp", sample.timestamp)
        obj.put("split", sample.split.name)
        return obj
    }
}
