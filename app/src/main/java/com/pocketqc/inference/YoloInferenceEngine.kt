package com.pocketqc.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.pocketqc.camera.CameraFrame
import com.pocketqc.core.model.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class YoloBenchmarkResult(
    val detections: List<Detection>,
    val preprocessTimeMs: Long,
    val inferenceTimeMs: Long,
    val postprocessTimeMs: Long,
    val totalTimeMs: Long,
    val backend: HardwareBackend = HardwareBackend.CPU
)

/**
 * Generic, high-performance YOLO ONNX mobile inference engine for Pocket QC.
 * Runs completely offline on Android CPU using ONNX Runtime Mobile.
 *
 * Configurable via [ModelConfig] with full support for dynamic model metadata,
 * classes, dimensions, and runtime threshold adjustments.
 *
 * Preserves all Phase 3 CPU pipeline optimizations:
 * - Preallocated persistent buffers (zero large allocations in steady state)
 * - Reused native DirectByteBuffer for input tensor
 * - Reused preallocated output FloatArray
 * - Direct letterbox rendering onto preallocated Canvas
 * - Preserved OrtSession and OrtEnvironment reuse across inferences
 */
class YoloInferenceEngine private constructor(
    private val modelBytesProvider: () -> ByteArray,
    val modelConfig: ModelConfig = ModelConfig.BISCUITS_FINAL,
    var confidenceThreshold: Float = modelConfig.confidenceThreshold,
    var iouThreshold: Float = modelConfig.iouThreshold,
    override val targetBackend: HardwareBackend = HardwareBackend.CPU,
    private val nativeLibDir: String? = null
) : InferenceEngine {

    constructor(
        context: Context,
        modelConfig: ModelConfig = ModelConfig.BISCUITS_FINAL,
        confidenceThreshold: Float = modelConfig.confidenceThreshold,
        iouThreshold: Float = modelConfig.iouThreshold,
        targetBackend: HardwareBackend = HardwareBackend.CPU
    ) : this(
        modelBytesProvider = {
            context.assets.open(modelConfig.modelAssetPath).use { it.readBytes() }
        },
        modelConfig = modelConfig,
        confidenceThreshold = confidenceThreshold,
        iouThreshold = iouThreshold,
        targetBackend = targetBackend,
        nativeLibDir = context.applicationInfo.nativeLibraryDir
    )

    constructor(
        context: Context,
        modelAssetPath: String,
        confidenceThreshold: Float = YoloConfig.DEFAULT_CONFIDENCE_THRESHOLD,
        iouThreshold: Float = YoloConfig.DEFAULT_IOU_THRESHOLD,
        targetBackend: HardwareBackend = HardwareBackend.CPU
    ) : this(
        context = context,
        modelConfig = ModelConfig.BISCUITS_FINAL.copy(
            modelAssetPath = modelAssetPath,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold
        ),
        confidenceThreshold = confidenceThreshold,
        iouThreshold = iouThreshold,
        targetBackend = targetBackend
    )

    constructor(
        modelBytes: ByteArray,
        modelConfig: ModelConfig = ModelConfig.BISCUITS_FINAL,
        confidenceThreshold: Float = modelConfig.confidenceThreshold,
        iouThreshold: Float = modelConfig.iouThreshold,
        targetBackend: HardwareBackend = HardwareBackend.CPU
    ) : this(
        modelBytesProvider = { modelBytes },
        modelConfig = modelConfig,
        confidenceThreshold = confidenceThreshold,
        iouThreshold = iouThreshold,
        targetBackend = targetBackend
    )

    constructor(
        modelBytes: ByteArray,
        confidenceThreshold: Float = YoloConfig.DEFAULT_CONFIDENCE_THRESHOLD,
        iouThreshold: Float = YoloConfig.DEFAULT_IOU_THRESHOLD,
        targetBackend: HardwareBackend = HardwareBackend.CPU
    ) : this(
        modelBytes = modelBytes,
        modelConfig = ModelConfig.BISCUITS_FINAL.copy(
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold
        ),
        confidenceThreshold = confidenceThreshold,
        iouThreshold = iouThreshold,
        targetBackend = targetBackend
    )

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = modelConfig.inputName
    private var isEngineInitialized = false

    // Preallocated buffers sized according to modelConfig
    private var buffers: YoloPreallocatedBuffers? = null
    private val inputShape = longArrayOf(
        1,
        modelConfig.inputChannels.toLong(),
        modelConfig.inputHeight.toLong(),
        modelConfig.inputWidth.toLong()
    )

    private var resolvedBackend: HardwareBackend = HardwareBackend.CPU
    val actualBackend: HardwareBackend get() = resolvedBackend

    override val isInitialized: Boolean
        get() = isEngineInitialized && ortSession != null

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (isEngineInitialized && ortSession != null) {
                return@runCatching
            }

            val env = OrtEnvironment.getEnvironment()
            ortEnvironment = env

            val modelBytes = modelBytesProvider()

            if (!nativeLibDir.isNullOrBlank()) {
                InferenceBackendManager.configureEnvironmentForAdsp(nativeLibDir)
            }

            when (targetBackend) {
                HardwareBackend.CPU -> {
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    ortSession = env.createSession(modelBytes, sessionOptions)
                    resolvedBackend = HardwareBackend.CPU
                }
                HardwareBackend.QNN_NPU -> {
                    val sessionOptions = OrtSession.SessionOptions()
                    val configured = configureQnnSessionOptions(sessionOptions)
                    if (!configured) {
                        throw IllegalStateException(
                            "Qualcomm QNN HTP Execution Provider is not supported by the active ONNX Runtime package (missing addQnn API). Strict QNN_NPU execution cannot proceed."
                        )
                    }
                    try {
                        ortSession = env.createSession(modelBytes, sessionOptions)
                        resolvedBackend = HardwareBackend.QNN_NPU
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to initialize QNN HTP inference session for ${modelConfig.id}: ${e.message}", e)
                    }
                }
                HardwareBackend.AUTO -> {
                    var sessionCreated = false
                    val sessionOptions = OrtSession.SessionOptions()
                    val configured = configureQnnSessionOptions(sessionOptions)
                    if (configured) {
                        try {
                            ortSession = env.createSession(modelBytes, sessionOptions)
                            resolvedBackend = HardwareBackend.QNN_NPU
                            sessionCreated = true
                        } catch (e: Exception) {
                            Log.w("YoloInferenceEngine", "QNN HTP session creation failed (${e.message}). Falling back to CPU backend according to AUTO policy.")
                        }
                    }
                    if (!sessionCreated) {
                        val cpuOptions = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(4)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        }
                        ortSession = env.createSession(modelBytes, cpuOptions)
                        resolvedBackend = HardwareBackend.CPU
                    }
                }
            }

            val session = checkNotNull(ortSession)
            val inputNames = session.inputNames
            if (inputNames.isNotEmpty()) {
                inputName = inputNames.first()
            }

            // Allocate persistent reusable buffers dynamically based on modelConfig
            buffers = YoloPreallocatedBuffers(modelConfig)

            isEngineInitialized = true
        }
    }

    private fun configureQnnSessionOptions(options: OrtSession.SessionOptions): Boolean {
        return try {
            options.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
            options.setSessionLogVerbosityLevel(3)
            val qnnOptions = InferenceBackendManager.createQnnProviderOptions(nativeLibraryDir = nativeLibDir)
            val addQnnMethod = options.javaClass.getMethod("addQnn", Map::class.java)
            addQnnMethod.invoke(options, qnnOptions)
            Log.i("YoloInferenceEngine", "Configured QNN Execution Provider options: $qnnOptions")
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (e: Exception) {
            Log.w("YoloInferenceEngine", "Error configuring QNN SessionOptions: ${e.message}")
            false
        }
    }

    /**
     * Executes real inference on a static [Bitmap] and returns detected objects.
     */
    suspend fun detectBitmap(bitmap: Bitmap): List<Detection> {
        return detectBitmapWithBenchmark(bitmap).detections
    }

    /**
     * Executes inference on a [Bitmap] and records runtime latency metrics.
     * Uses preallocated buffers to eliminate steady-state allocations.
     */
    suspend fun detectBitmapWithBenchmark(bitmap: Bitmap): YoloBenchmarkResult = withContext(Dispatchers.Default) {
        check(isInitialized) { "YoloInferenceEngine is not initialized. Call initialize() first." }
        val session = checkNotNull(ortSession)
        val env = checkNotNull(ortEnvironment)
        val activeBuffers = checkNotNull(buffers)

        // 1. Preprocessing (letterbox + RGB CHW FloatBuffer) using preallocated buffers
        val t0 = System.currentTimeMillis()
        val preprocessed = YoloImagePreprocessor.preprocess(
            bitmap = bitmap,
            targetWidth = modelConfig.inputWidth,
            targetHeight = modelConfig.inputHeight,
            buffers = activeBuffers
        )
        val tPreprocess = System.currentTimeMillis() - t0

        // 2. ONNX Runtime inference
        val t1 = System.currentTimeMillis()
        val inputTensor = OnnxTensor.createTensor(env, preprocessed.floatBuffer, inputShape)

        val outputArray = inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val resultTensor = results[0] as OnnxTensor
                val buffer = resultTensor.floatBuffer
                buffer.get(activeBuffers.outputFloatArray, 0, buffer.remaining())
                activeBuffers.outputFloatArray
            }
        }
        val tInference = System.currentTimeMillis() - t1

        // 3. Postprocessing (parsing + NMS + letterbox inversion) using active modelConfig
        val t2 = System.currentTimeMillis()
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = outputArray,
            letterboxInfo = preprocessed.letterboxInfo,
            modelConfig = modelConfig,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold
        )
        val tPostprocess = System.currentTimeMillis() - t2

        YoloBenchmarkResult(
            detections = detections,
            preprocessTimeMs = tPreprocess,
            inferenceTimeMs = tInference,
            postprocessTimeMs = tPostprocess,
            totalTimeMs = tPreprocess + tInference + tPostprocess,
            backend = resolvedBackend
        )
    }

    /**
     * High-speed live camera inference path:
     * Combines sensor rotation and letterbox scaling directly into the preallocated canvas,
     * eliminating intermediate rotated and scaled Bitmaps completely.
     */
    suspend fun detectRawFrameWithBenchmark(
        rawBitmap: Bitmap,
        rotationDegrees: Int
    ): YoloBenchmarkResult = withContext(Dispatchers.Default) {
        check(isInitialized) { "YoloInferenceEngine is not initialized. Call initialize() first." }
        val session = checkNotNull(ortSession)
        val env = checkNotNull(ortEnvironment)
        val activeBuffers = checkNotNull(buffers)

        // 1. Direct rotation + letterbox scaling onto preallocated canvas
        val t0 = System.currentTimeMillis()
        val preprocessed = YoloImagePreprocessor.preprocessWithTransform(
            rawBitmap = rawBitmap,
            rotationDegrees = rotationDegrees,
            targetWidth = modelConfig.inputWidth,
            targetHeight = modelConfig.inputHeight,
            buffers = activeBuffers
        )
        val tPreprocess = System.currentTimeMillis() - t0

        // 2. ONNX Runtime inference
        val t1 = System.currentTimeMillis()
        val inputTensor = OnnxTensor.createTensor(env, preprocessed.floatBuffer, inputShape)

        val outputArray = inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val resultTensor = results[0] as OnnxTensor
                val buffer = resultTensor.floatBuffer
                buffer.get(activeBuffers.outputFloatArray, 0, buffer.remaining())
                activeBuffers.outputFloatArray
            }
        }
        val tInference = System.currentTimeMillis() - t1

        // 3. Postprocessing using active modelConfig
        val t2 = System.currentTimeMillis()
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = outputArray,
            letterboxInfo = preprocessed.letterboxInfo,
            modelConfig = modelConfig,
            confidenceThreshold = confidenceThreshold,
            iouThreshold = iouThreshold
        )
        val tPostprocess = System.currentTimeMillis() - t2

        YoloBenchmarkResult(
            detections = detections,
            preprocessTimeMs = tPreprocess,
            inferenceTimeMs = tInference,
            postprocessTimeMs = tPostprocess,
            totalTimeMs = tPreprocess + tInference + tPostprocess,
            backend = resolvedBackend
        )
    }

    override suspend fun detect(frame: CameraFrame): List<Detection> {
        val bitmap = frame.bitmap ?: frame.payload?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }
        return if (bitmap != null) {
            detectBitmap(bitmap)
        } else {
            emptyList()
        }
    }

    override fun release() {
        buffers?.release()
        buffers = null
        ortSession?.close()
        ortSession = null
        ortEnvironment = null
        isEngineInitialized = false
    }
}
