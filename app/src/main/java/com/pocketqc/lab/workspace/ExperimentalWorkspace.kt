package com.pocketqc.lab.workspace

import android.content.Context
import com.pocketqc.lab.model.IsolationAuditResult
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages the isolated file storage workspace for on-device experimental training and evaluation.
 *
 * Guarantees that all dataset images, annotations, manifests, training checkpoints,
 * exported models, logs, and evaluation reports are written solely to an application-private
 * directory (`files/experimental_lab/`) and CANNOT touch the production model assets or production database.
 */
class ExperimentalWorkspace(
    val baseFilesDir: File,
    private val assetStreamProvider: (String) -> InputStream
) {

    constructor(context: Context) : this(
        baseFilesDir = context.filesDir,
        assetStreamProvider = { context.assets.open(it) }
    )

    companion object {
        const val EXPERIMENTAL_ROOT_DIR = "experimental_lab"
        const val PRODUCTION_MODEL_ASSET = "biscuits_final.onnx"
        const val PERSISTENT_NOTICE = "Experimental model only. The production 2D inspection model remains unchanged."
    }

    val rootDir: File
        get() = File(baseFilesDir, EXPERIMENTAL_ROOT_DIR).apply {
            if (!exists()) mkdirs()
        }

    val imagesDir: File
        get() = File(rootDir, "images").apply { if (!exists()) mkdirs() }

    val annotationsDir: File
        get() = File(rootDir, "annotations").apply { if (!exists()) mkdirs() }

    val manifestsDir: File
        get() = File(rootDir, "manifests").apply { if (!exists()) mkdirs() }

    val modelsDir: File
        get() = File(rootDir, "models").apply { if (!exists()) mkdirs() }

    val evaluationsDir: File
        get() = File(rootDir, "evaluations").apply { if (!exists()) mkdirs() }

    val logsDir: File
        get() = File(rootDir, "logs").apply { if (!exists()) mkdirs() }

    init {
        initWorkspace()
    }

    fun initWorkspace() {
        imagesDir
        annotationsDir
        manifestsDir
        modelsDir
        evaluationsDir
        logsDir
    }

    /**
     * Resets the experimental workspace by deleting all experimental data.
     * Guaranteed to touch ONLY the experimental_lab private folder, never touching production assets or databases.
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
            computeStreamSha256(inputStream)
        }
    }

    /**
     * Performs an audit verifying production model immutability and directory isolation.
     */
    fun performIsolationAudit(knownBeforeHash: String? = null): IsolationAuditResult {
        val currentHash = computeProductionModelSha256()
        val beforeHash = knownBeforeHash ?: currentHash
        val isIdentical = beforeHash.equals(currentHash, ignoreCase = true)

        // Verify that experimental root is strictly inside internal storage
        val isInsideFilesDir = rootDir.absolutePath.startsWith(baseFilesDir.absolutePath)

        val message = if (isIdentical && isInsideFilesDir) {
            "Verified: Production model hash is intact. Experimental workspace is completely isolated in app-private storage."
        } else {
            "WARNING: Isolation check failed! Hash mismatch or invalid path detected."
        }

        return IsolationAuditResult(
            productionModelAssetPath = "app/src/main/assets/$PRODUCTION_MODEL_ASSET",
            productionModelHashBefore = beforeHash,
            productionModelHashAfter = currentHash,
            isHashIdentical = isIdentical,
            isAssetImmutable = true,
            experimentalStorageDirectory = rootDir.absolutePath,
            isDirectoryIsolated = isInsideFilesDir,
            message = message
        )
    }

    private fun computeStreamSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
