package com.pocketqc.lab.registry

import com.pocketqc.lab.model.ExperimentalModelMetadata
import com.pocketqc.lab.training.ExperimentalModel
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import java.io.File

/**
 * Isolated registry managing experimental model artifacts in application-private storage.
 *
 * Implements strict architectural barriers preventing any experimental model
 * from being promoted, substituted, or activated for production 2D conveyor inspection.
 */
class ExperimentalModelRegistry(
    private val workspace: ExperimentalWorkspace
) {

    /**
     * Lists all experimental models currently saved in the isolated workspace.
     */
    fun listExperimentalModels(): List<ExperimentalModelMetadata> {
        val files = workspace.modelsDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        val list = mutableListOf<ExperimentalModelMetadata>()
        for (f in files) {
            try {
                val model = ExperimentalModel.loadFromFile(f)
                list.add(model.metadata)
            } catch (_: Exception) {
                // Ignore corrupt or incomplete files
            }
        }
        return list.sortedByDescending { it.creationTimestamp }
    }

    /**
     * Loads an experimental model artifact by its unique modelId.
     */
    fun loadExperimentalModel(modelId: String): ExperimentalModel? {
        val files = workspace.modelsDir.listFiles { _, name -> name.endsWith(".json") } ?: return null
        for (f in files) {
            try {
                val model = ExperimentalModel.loadFromFile(f)
                if (model.metadata.modelId == modelId) {
                    return model
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
        return null
    }

    /**
     * Deletes a specific experimental model artifact from private storage.
     */
    fun deleteExperimentalModel(modelId: String): Boolean {
        val files = workspace.modelsDir.listFiles { _, name -> name.endsWith(".json") } ?: return false
        for (f in files) {
            try {
                val model = ExperimentalModel.loadFromFile(f)
                if (model.metadata.modelId == modelId) {
                    return f.delete()
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
        return false
    }

    /**
     * Architectural barrier: Throws an UnsupportedOperationException if any attempt is made
     * to promote an experimental model to the production inspection pipeline.
     */
    fun promoteToProduction(@Suppress("UNUSED_PARAMETER") modelId: String): Nothing {
        throw UnsupportedOperationException(
            "ARCHITECTURAL SECURITY VIOLATION: Experimental models cannot be promoted to production. " +
            "The production 2D inspection model at app/src/main/assets/biscuits_final.onnx is strictly immutable."
        )
    }
}
