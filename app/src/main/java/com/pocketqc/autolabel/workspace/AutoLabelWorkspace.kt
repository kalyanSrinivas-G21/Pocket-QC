package com.pocketqc.autolabel.workspace

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages the isolated file storage workspace for the Auto-Labeling PoC.
 *
 * Guarantees that all dataset images, candidate annotations, YOLO exports,
 * and quality reports are written strictly to an application-private directory
 * (`files/experimental_autolabel/`) and CANNOT touch the production model assets
 * or production inspection database (`batches.json`).
 */
class AutoLabelWorkspace(
    val baseFilesDir: File,
    private val assetStreamProvider: (String) -> InputStream
) {

    constructor(context: Context) : this(
        baseFilesDir = context.filesDir,
        assetStreamProvider = { context.assets.open(it) }
    )

    companion object {
        const val WORKSPACE_ROOT_DIR = "experimental_autolabel"
        const val PRODUCTION_MODEL_ASSET = "biscuits_final.onnx"
        const val PRODUCTION_BATCHES_FILE = "batches.json"
        const val KNOWN_PRODUCTION_SHA256 = "92D96E80AF5C8E897459B74C58862CE201DBE09CDAA41F0FD03EDA6A4A2EDF08"
    }

    val rootDir: File
        get() = File(baseFilesDir, WORKSPACE_ROOT_DIR).apply {
            if (!exists()) mkdirs()
        }

    val imagesDir: File
        get() = File(rootDir, "images").apply { if (!exists()) mkdirs() }

    val annotationsDir: File
        get() = File(rootDir, "annotations").apply { if (!exists()) mkdirs() }

    val yoloExportDir: File
        get() = File(rootDir, "yolo_export").apply { if (!exists()) mkdirs() }

    val evaluationsDir: File
        get() = File(rootDir, "evaluations").apply { if (!exists()) mkdirs() }

    init {
        initWorkspace()
    }

    fun initWorkspace() {
        imagesDir
        annotationsDir
        yoloExportDir
        evaluationsDir
    }

    /**
     * Resets the experimental workspace by deleting all auto-labeling data.
     * Guaranteed to touch ONLY the experimental_autolabel directory.
     * Never touches production assets or batches.json.
     */
    fun clearWorkspace() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        initWorkspace()
    }

    /**
     * Calculates the SHA-256 hash of the immutable production biscuit model asset.
     */
    fun computeProductionModelSha256(): String {
        return assetStreamProvider(PRODUCTION_MODEL_ASSET).use { inputStream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        }
    }

    /**
     * Verifies that the experimental workspace is strictly isolated from production files.
     */
    fun verifyIsolation(): Boolean {
        // 1. Root must be inside baseFilesDir
        val isInsideSandbox = rootDir.canonicalPath.startsWith(baseFilesDir.canonicalPath)
        // 2. Must not equal baseFilesDir itself (cannot overwrite root)
        val isSeparateFromRoot = rootDir.canonicalPath != baseFilesDir.canonicalPath
        // 3. Must not point to batches.json
        val batchesFile = File(baseFilesDir, PRODUCTION_BATCHES_FILE)
        val batchesUntouched = !rootDir.canonicalPath.startsWith(batchesFile.canonicalPath)
        // 4. Production model hash matches expected
        val currentHash = computeProductionModelSha256()
        val modelIntact = currentHash.equals(KNOWN_PRODUCTION_SHA256, ignoreCase = true)

        return isInsideSandbox && isSeparateFromRoot && batchesUntouched && modelIntact
    }
}
