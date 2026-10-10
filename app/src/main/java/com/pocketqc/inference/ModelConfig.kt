package com.pocketqc.inference

/**
 * Generic specification and metadata for a YOLO vision model in Pocket QC.
 *
 * Encapsulates:
 * - Model asset file location
 * - Input tensor specifications (name, width, height, channels)
 * - Output structure (anchor count, feature count)
 * - Class vocabulary and mapping
 * - Runtime thresholds (confidence, IoU)
 *
 * Establishes a clean model abstraction decoupling the inference engine,
 * preprocessor, and postprocessor from any single hardcoded model.
 */
data class ModelConfig(
    val id: String = "biscuits_final",
    val modelAssetPath: String = "biscuits_final.onnx",
    val inputName: String = "images",
    val inputWidth: Int = 640,
    val inputHeight: Int = 640,
    val inputChannels: Int = 3,
    val numAnchors: Int = 8400,
    val classNames: List<String> = listOf("Broken", "Fracture", "Intact"),
    val confidenceThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f
) {
    val numClasses: Int get() = classNames.size
    val numFeatures: Int get() = 4 + numClasses
    val totalOutputElements: Int get() = numFeatures * numAnchors

    fun getClassName(classId: Int): String {
        return classNames.getOrElse(classId) { "Unknown" }
    }

    companion object {
        /**
         * Upgraded production 2D Biscuit Defect Sort model (YOLOv8n).
         * Trained on new_detection_data_v2 dataset.
         */
        val BISCUITS_FINAL = ModelConfig(
            id = "biscuits_final",
            modelAssetPath = "biscuits_final.onnx",
            inputName = "images",
            inputWidth = 640,
            inputHeight = 640,
            inputChannels = 3,
            numAnchors = 8400,
            classNames = listOf("Broken", "Fracture", "Intact"),
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        /**
         * Production UV Aflatoxin B1 Spot Detection model (YOLOv8n).
         * Trained on 365nm UV fluorescence chilli powder dataset.
         */
        val AFLATOXIN = ModelConfig(
            id = "aflatoxin",
            modelAssetPath = "aflatoxin.onnx",
            inputName = "images",
            inputWidth = 640,
            inputHeight = 640,
            inputChannels = 3,
            numAnchors = 8400,
            classNames = listOf("Aflatoxin"),
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )
    }
}
