package com.pocketqc.inference

/**
 * Technical configuration for the YOLOv8n Biscuit Quality Control model.
 * Delegates to the active [ModelConfig.BISCUITS_FINAL] to ensure single-source-of-truth.
 */
object YoloConfig {
    val activeConfig: ModelConfig = ModelConfig.BISCUITS_FINAL

    val MODEL_ASSET_PATH: String get() = activeConfig.modelAssetPath

    val INPUT_WIDTH: Int get() = activeConfig.inputWidth
    val INPUT_HEIGHT: Int get() = activeConfig.inputHeight
    val INPUT_CHANNELS: Int get() = activeConfig.inputChannels

    val NUM_ANCHORS: Int get() = activeConfig.numAnchors
    val NUM_CLASSES: Int get() = activeConfig.numClasses
    val NUM_FEATURES: Int get() = activeConfig.numFeatures

    val DEFAULT_CONFIDENCE_THRESHOLD: Float get() = activeConfig.confidenceThreshold
    val DEFAULT_IOU_THRESHOLD: Float get() = activeConfig.iouThreshold

    val CLASS_NAMES: List<String> get() = activeConfig.classNames

    fun getClassName(classId: Int): String {
        return activeConfig.getClassName(classId)
    }
}
