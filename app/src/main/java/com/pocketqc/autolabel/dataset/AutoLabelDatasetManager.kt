package com.pocketqc.autolabel.dataset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.ProposalSource
import com.pocketqc.autolabel.model.ProposedBoxAnnotation
import com.pocketqc.autolabel.workspace.AutoLabelWorkspace
import com.pocketqc.core.model.BoundingBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class DuplicateImageException(message: String) : Exception(message)

/**
 * Manages image ingestion, SHA-256 deduplication, metadata persistence,
 * and class assignment in the isolated auto-labeling workspace.
 */
class AutoLabelDatasetManager(
    private val workspace: AutoLabelWorkspace,
    private val assetStreamProvider: (String) -> java.io.InputStream
) {

    constructor(context: Context, workspace: AutoLabelWorkspace) : this(
        workspace = workspace,
        assetStreamProvider = { context.assets.open(it) }
    )

    constructor(workspace: AutoLabelWorkspace) : this(
        workspace = workspace,
        assetStreamProvider = { fileName ->
            val f = File("src/main/assets", fileName)
            if (f.exists()) f.inputStream() else File(workspace.imagesDir, fileName).inputStream()
        }
    )

    private val annotationsFile: File
        get() = File(workspace.annotationsDir, "annotations.json")

    /**
     * Loads all existing image items from disk.
     */
    @Synchronized
    fun loadItems(): List<AutoLabelImageItem> {
        if (!annotationsFile.exists()) return emptyList()
        return try {
            val jsonStr = annotationsFile.readText()
            val array = JSONArray(jsonStr)
            val items = mutableListOf<AutoLabelImageItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = parseItemJson(obj)
                if (File(item.filePath).exists()) {
                    items.add(item)
                }
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Persists all image items to disk.
     */
    @Synchronized
    fun saveItems(items: List<AutoLabelImageItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(itemToJson(item))
        }
        annotationsFile.writeText(array.toString(2))
    }

    /**
     * Ingests image raw bytes, checks for duplicate SHA-256 content,
     * saves to private storage, and records metadata.
     */
    @Synchronized
    fun ingestImageBytes(
        bytes: ByteArray,
        width: Int = 640,
        height: Int = 640,
        source: String = "IMPORT",
        operatorClass: AutoLabelClass? = null
    ): Result<AutoLabelImageItem> {
        return runCatching {
            val hash = computeSha256(bytes)
            val currentItems = loadItems()
            val existing = currentItems.firstOrNull { it.fileHash.equals(hash, ignoreCase = true) }
            if (existing != null) {
                throw DuplicateImageException("Duplicate image detected. Identical content already exists as ${existing.fileName} (ID: ${existing.id})")
            }

            val timestamp = System.currentTimeMillis()
            val shortHash = hash.take(8)
            val fileName = "biscuit_${timestamp}_$shortHash.jpg"
            val targetFile = File(workspace.imagesDir, fileName)

            FileOutputStream(targetFile).use { fos ->
                fos.write(bytes)
            }

            val newItem = AutoLabelImageItem(
                id = UUID.randomUUID().toString(),
                fileHash = hash,
                fileName = fileName,
                filePath = targetFile.absolutePath,
                source = source,
                imageWidth = width,
                imageHeight = height,
                operatorAssignedClass = operatorClass,
                reviewStatus = AnnotationStatus.PENDING,
                timestamp = timestamp,
                annotations = emptyList()
            )

            val updated = currentItems + newItem
            saveItems(updated)
            newItem
        }
    }

    /**
     * Ingests a new image Bitmap, checks for duplicate SHA-256 content,
     * normalizes orientation, saves to private storage, and records metadata.
     */
    @Synchronized
    fun ingestBitmap(
        bitmap: Bitmap,
        source: String = "CAMERA",
        operatorClass: AutoLabelClass? = null,
        rotationDegrees: Int = 0
    ): Result<AutoLabelImageItem> {
        return runCatching {
            // 1. Normalize orientation if needed
            val normalizedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            // 2. Compress to byte array
            val byteStream = ByteArrayOutputStream()
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, byteStream)
            val bytes = byteStream.toByteArray()

            ingestImageBytes(
                bytes = bytes,
                width = normalizedBitmap.width,
                height = normalizedBitmap.height,
                source = source,
                operatorClass = operatorClass
            ).getOrThrow()
        }
    }

    /**
     * Updates an existing item with modified annotations or review status.
     */
    @Synchronized
    fun updateItem(updatedItem: AutoLabelImageItem) {
        val current = loadItems().toMutableList()
        val index = current.indexOfFirst { it.id == updatedItem.id }
        if (index != -1) {
            current[index] = updatedItem
            saveItems(current)
        }
    }

    /**
     * Deletes an item and its associated image file from disk.
     */
    @Synchronized
    fun deleteItem(itemId: String): Boolean {
        val current = loadItems().toMutableList()
        val item = current.firstOrNull { it.id == itemId } ?: return false
        val file = File(item.filePath)
        if (file.exists()) {
            file.delete()
        }
        current.removeAll { it.id == itemId }
        saveItems(current)
        return true
    }

    /**
     * Computes distribution counts by operator-assigned or box-level class.
     */
    fun getCountsByClass(): Map<AutoLabelClass, Int> {
        val items = loadItems()
        val counts = mutableMapOf(
            AutoLabelClass.INTACT to 0,
            AutoLabelClass.FRACTURE to 0,
            AutoLabelClass.BROKEN to 0
        )
        for (item in items) {
            val cls = item.operatorAssignedClass
                ?: item.annotations.firstOrNull()?.assignedClass
            if (cls != null) {
                counts[cls] = (counts[cls] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * Computes counts by review status.
     */
    fun getCountsByStatus(): Map<AnnotationStatus, Int> {
        val items = loadItems()
        return AnnotationStatus.entries.associateWith { status ->
            items.count { it.reviewStatus == status }
        }
    }

    /**
     * Imports standard demo biscuit images from assets into isolated workspace.
     */
    @Synchronized
    fun importStandardDemoBiscuits(): List<AutoLabelImageItem> {
        val demoSpecs = listOf(
            Triple("test_images/biscuit_intact.jpg", AutoLabelClass.INTACT, BoundingBox(0.20f, 0.30f, 0.72f, 0.78f)),
            Triple("test_images/biscuit_fracture.jpg", AutoLabelClass.FRACTURE, BoundingBox(0.15f, 0.10f, 0.70f, 0.68f)),
            Triple("test_images/biscuit_broken.jpg", AutoLabelClass.BROKEN, BoundingBox(0.18f, 0.20f, 0.85f, 0.85f))
        )

        val createdItems = mutableListOf<AutoLabelImageItem>()
        for ((assetPath, expectedClass, groundTruthBox) in demoSpecs) {
            val bmp = try {
                assetStreamProvider(assetPath).use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            } ?: continue

            val result = ingestBitmap(
                bitmap = bmp,
                source = "STANDARD_SAMPLE",
                operatorClass = expectedClass
            )

            result.onSuccess { item ->
                // Add initial proposed box
                val proposed = ProposedBoxAnnotation(
                    boxId = UUID.randomUUID().toString(),
                    boundingBox = groundTruthBox,
                    predictedClass = expectedClass,
                    confidence = 0.90f,
                    assignedClass = expectedClass,
                    isHumanVerified = false,
                    source = ProposalSource.MODEL_DETECTOR
                )
                val itemWithBox = item.copy(
                    annotations = listOf(proposed),
                    reviewStatus = AnnotationStatus.PENDING
                )
                updateItem(itemWithBox)
                createdItems.add(itemWithBox)
            }
            bmp.recycle()
        }
        return createdItems
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02X".format(it) }
    }

    private fun itemToJson(item: AutoLabelImageItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("fileHash", item.fileHash)
            put("fileName", item.fileName)
            put("filePath", item.filePath)
            put("source", item.source)
            put("imageWidth", item.imageWidth)
            put("imageHeight", item.imageHeight)
            put("operatorAssignedClass", item.operatorAssignedClass?.displayName)
            put("reviewStatus", item.reviewStatus.name)
            put("timestamp", item.timestamp)
            put("processingTimeMs", item.processingTimeMs)
            put("rejectionReason", item.rejectionReason)
            put("hasHumanCorrections", item.hasHumanCorrections)

            val boxArray = JSONArray()
            for (box in item.annotations) {
                boxArray.put(JSONObject().apply {
                    put("boxId", box.boxId)
                    put("left", box.boundingBox.left.toDouble())
                    put("top", box.boundingBox.top.toDouble())
                    put("right", box.boundingBox.right.toDouble())
                    put("bottom", box.boundingBox.bottom.toDouble())
                    put("predictedClass", box.predictedClass.displayName)
                    put("confidence", box.confidence.toDouble())
                    put("assignedClass", box.assignedClass.displayName)
                    put("isHumanVerified", box.isHumanVerified)
                    put("source", box.source.name)
                    put("reviewNotes", box.reviewNotes)
                })
            }
            put("annotations", boxArray)
        }
    }

    private fun parseItemJson(obj: JSONObject): AutoLabelImageItem {
        val operatorClassStr = if (obj.has("operatorAssignedClass") && !obj.isNull("operatorAssignedClass")) obj.getString("operatorAssignedClass") else null
        val operatorClass = if (!operatorClassStr.isNullOrBlank()) AutoLabelClass.fromName(operatorClassStr) else null

        val annotations = mutableListOf<ProposedBoxAnnotation>()
        val boxArray = obj.optJSONArray("annotations") ?: JSONArray()
        for (i in 0 until boxArray.length()) {
            val bObj = boxArray.getJSONObject(i)
            val predClass = AutoLabelClass.fromName(bObj.getString("predictedClass")) ?: AutoLabelClass.BROKEN
            val assClass = AutoLabelClass.fromName(bObj.optString("assignedClass", predClass.displayName)) ?: predClass

            annotations.add(
                ProposedBoxAnnotation(
                    boxId = bObj.getString("boxId"),
                    boundingBox = BoundingBox(
                        left = bObj.getDouble("left").toFloat(),
                        top = bObj.getDouble("top").toFloat(),
                        right = bObj.getDouble("right").toFloat(),
                        bottom = bObj.getDouble("bottom").toFloat()
                    ),
                    predictedClass = predClass,
                    confidence = bObj.getDouble("confidence").toFloat(),
                    assignedClass = assClass,
                    isHumanVerified = bObj.optBoolean("isHumanVerified", false),
                    source = runCatching { ProposalSource.valueOf(bObj.getString("source")) }.getOrDefault(ProposalSource.MODEL_DETECTOR),
                    reviewNotes = if (bObj.has("reviewNotes") && !bObj.isNull("reviewNotes")) bObj.getString("reviewNotes") else null
                )
            )
        }

        return AutoLabelImageItem(
            id = obj.getString("id"),
            fileHash = obj.getString("fileHash"),
            fileName = obj.getString("fileName"),
            filePath = obj.getString("filePath"),
            source = obj.optString("source", "IMPORT"),
            imageWidth = obj.getInt("imageWidth"),
            imageHeight = obj.getInt("imageHeight"),
            operatorAssignedClass = operatorClass,
            reviewStatus = runCatching { AnnotationStatus.valueOf(obj.getString("reviewStatus")) }.getOrDefault(AnnotationStatus.PENDING),
            timestamp = obj.getLong("timestamp"),
            annotations = annotations,
            processingTimeMs = obj.optLong("processingTimeMs", 0L),
            rejectionReason = if (obj.has("rejectionReason") && !obj.isNull("rejectionReason")) obj.getString("rejectionReason") else null,
            hasHumanCorrections = obj.optBoolean("hasHumanCorrections", false)
        )
    }
}
