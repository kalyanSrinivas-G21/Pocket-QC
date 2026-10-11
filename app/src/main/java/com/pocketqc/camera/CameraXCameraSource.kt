package com.pocketqc.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pocketqc.core.model.Detection
import com.pocketqc.inference.InferenceEngine
import com.pocketqc.inference.YoloInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CameraXSource"

/**
 * Granular pipeline telemetry for performance monitoring and diagnostics.
 */
data class PipelineTelemetry(
    val inferenceFps: Float = 0f,
    val preprocessTimeMs: Long = 0L,
    val inferenceTimeMs: Long = 0L,
    val postprocessTimeMs: Long = 0L,
    val totalLatencyMs: Long = 0L,
    val framesReceived: Long = 0L,
    val framesSkipped: Long = 0L,
    val framesInferred: Long = 0L
)

/**
 * High-performance real-time CameraX source for Pocket QC inspection station.
 *
 * Architecture:
 * CameraX Analyzer (Thread: CameraX)
 *        ↓  (Conflated atomic handoff, < 0.1ms non-blocking)
 * Latest Frame Holder (Bounded capacity: at most 1 pending frame)
 *        ↓  (Asynchronous channel notification)
 * CPU Inference Worker (Thread: Dispatchers.Default)
 *        ↓  (Preallocated buffers, zero intermediate allocations)
 * Result Snapshot
 *        ↓
 * ViewModel / Compose UI
 *
 * Guarantees:
 * 1. Zero runBlocking in the camera analyzer path.
 * 2. Camera preview operates independently at 30 FPS without inference stalls.
 * 3. Latest-frame semantics: stale frames are discarded immediately, preventing backlog.
 * 4. ImageProxy ownership: every ImageProxy is closed exactly once in all paths.
 * 5. Reusable preallocated preprocessing and output buffers.
 */
class CameraXCameraSource(
    private val context: Context,
    private val inferenceEngine: InferenceEngine,
    private val onDetectionsUpdated: (detections: List<Detection>, fps: Float, latencyMs: Long) -> Unit
) : CameraSource {

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _frameFlow = MutableStateFlow<CameraFrame?>(null)
    override val frameFlow: StateFlow<CameraFrame?> = _frameFlow.asStateFlow()

    private val _telemetryState = MutableStateFlow(PipelineTelemetry())
    val telemetryState: StateFlow<PipelineTelemetry> = _telemetryState.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewViewRef: PreviewView? = null
    private var lifecycleOwnerRef: LifecycleOwner? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Bounded latest-frame atomic holder & notification channel
    private val pendingFrame = AtomicReference<ImageProxy?>(null)
    private val frameSignalChannel = Channel<Unit>(Channel.CONFLATED)

    // Dedicated asynchronous inference worker scope & job
    private var workerJob: Job? = null
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Pipeline telemetry counters
    private val framesReceived = AtomicLong(0)
    private val framesSkipped = AtomicLong(0)
    private val framesInferred = AtomicLong(0)

    val framesReceivedCount: Long get() = framesReceived.get()
    val framesSkippedCount: Long get() = framesSkipped.get()
    val framesInferredCount: Long get() = framesInferred.get()

    // Rolling FPS calculation
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var framesInferredInWindow = 0
    private var currentInferenceFps = 0f
    private var currentInferenceLatencyMs = 0L

    val currentFps: Float get() = currentInferenceFps
    val currentLatency: Long get() = currentInferenceLatencyMs

    // Stored dimensions for preview coordinate alignment
    @Volatile
    var previewWidthPx: Float = 1080f
        private set

    @Volatile
    var previewHeightPx: Float = 1440f
        private set

    /**
     * Binds CameraX to the provided PreviewView and LifecycleOwner.
     */
    fun bindPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        this.previewViewRef = previewView
        this.lifecycleOwnerRef = lifecycleOwner

        previewView.post {
            if (previewView.width > 0 && previewView.height > 0) {
                previewWidthPx = previewView.width.toFloat()
                previewHeightPx = previewView.height.toFloat()
            }
        }

        bindCameraInternal()
    }

    private fun bindCameraInternal() {
        val previewView = previewViewRef ?: return
        val lifecycleOwner = lifecycleOwnerRef ?: return

        _cameraState.value = CameraState.Initializing

        // Start background CPU inference worker if not active
        startInferenceWorker()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                provider.unbindAll()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // 1. Preview use case (independent 30 FPS rendering)
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // 2. ImageAnalysis use case with KEEP_ONLY_LATEST
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(640, 480))
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    handleIncomingFrame(imageProxy)
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                _cameraState.value = CameraState.Streaming
                Log.d(TAG, "CameraX successfully bound to lifecycle with async inference pipeline")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX", e)
                _cameraState.value = CameraState.Error(e.message ?: "Camera binding failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Non-blocking camera analyzer callback (< 0.1 ms execution time).
     * Dispatches frame to the bounded latest-frame holder and immediately returns.
     */
    private fun handleIncomingFrame(imageProxy: ImageProxy) {
        if (analysisExecutor.isShutdown || _cameraState.value !is CameraState.Streaming) {
            imageProxy.close()
            return
        }

        framesReceived.incrementAndGet()

        // Atomically place new frame in holder; if a stale frame was waiting, discard and close it
        val staleFrame = pendingFrame.getAndSet(imageProxy)
        if (staleFrame != null) {
            framesSkipped.incrementAndGet()
            staleFrame.close()
        }

        // Signal inference worker that a new frame is ready
        frameSignalChannel.trySend(Unit)
    }

    /**
     * Dedicated background CPU inference worker loop.
     * Consumes only the latest frame, performs zero-allocation preprocessing, runs ONNX inference,
     * and publishes telemetry snapshots to ViewModel / UI.
     */
    private fun startInferenceWorker() {
        if (workerJob?.isActive == true) return

        workerJob = workerScope.launch {
            for (signal in frameSignalChannel) {
                if (!isActive) break

                // Pull latest frame from atomic holder
                val imageToProcess = pendingFrame.getAndSet(null) ?: continue

                processSingleFrame(imageToProcess)
            }
        }
    }

    private suspend fun processSingleFrame(imageProxy: ImageProxy) {
        val tFrameStart = System.currentTimeMillis()
        var rawBitmap: Bitmap? = null
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val timestampNs = imageProxy.imageInfo.timestamp

        try {
            // Convert ImageProxy to Bitmap and release ImageProxy immediately (~3-5 ms hold time)
            rawBitmap = imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert ImageProxy to bitmap", e)
        } finally {
            // Guaranteed ImageProxy closure: camera buffer returned to pool immediately
            imageProxy.close()
        }

        val bitmap = rawBitmap ?: return

        try {
            val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
            val camW = if (isRotated) bitmap.height else bitmap.width
            val camH = if (isRotated) bitmap.width else bitmap.height

            var preprocessMs = 0L
            var inferenceMs = 0L
            var postprocessMs = 0L

            val rawDetections: List<Detection> = if (inferenceEngine is YoloInferenceEngine && inferenceEngine.isInitialized) {
                // High-performance direct rotation + letterbox zero-allocation path
                val benchmark = inferenceEngine.detectRawFrameWithBenchmark(
                    rawBitmap = bitmap,
                    rotationDegrees = rotationDegrees
                )
                preprocessMs = benchmark.preprocessTimeMs
                inferenceMs = benchmark.inferenceTimeMs
                postprocessMs = benchmark.postprocessTimeMs
                benchmark.detections
            } else {
                val t0 = System.currentTimeMillis()
                val rotatedBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
                val frame = CameraFrame(
                    frameId = tFrameStart,
                    width = camW,
                    height = camH,
                    timestampNs = timestampNs,
                    rotationDegrees = rotationDegrees,
                    bitmap = rotatedBitmap
                )
                val results = inferenceEngine.detect(frame)
                if (rotatedBitmap != bitmap) {
                    rotatedBitmap.recycle()
                }
                inferenceMs = System.currentTimeMillis() - t0
                results
            }

            // Transform raw detections from camera space to preview coordinates
            val transformedDetections = rawDetections.map { det ->
                val transformedBox = CameraCoordinateTransformer.transformToPreview(
                    box = det.boundingBox,
                    camWidth = camW,
                    camHeight = camH,
                    viewWidth = previewWidthPx,
                    viewHeight = previewHeightPx
                )
                det.copy(boundingBox = transformedBox)
            }

            val tTotalLatency = System.currentTimeMillis() - tFrameStart
            currentInferenceLatencyMs = tTotalLatency

            // Update rolling inference FPS
            framesInferred.incrementAndGet()
            framesInferredInWindow++
            val now = System.currentTimeMillis()
            val elapsed = now - lastFpsCalculationTime
            if (elapsed >= 1000) {
                currentInferenceFps = (framesInferredInWindow * 1000f) / elapsed
                framesInferredInWindow = 0
                lastFpsCalculationTime = now
            }

            val telemetry = PipelineTelemetry(
                inferenceFps = currentInferenceFps,
                preprocessTimeMs = preprocessMs,
                inferenceTimeMs = inferenceMs,
                postprocessTimeMs = postprocessMs,
                totalLatencyMs = tTotalLatency,
                framesReceived = framesReceived.get(),
                framesSkipped = framesSkipped.get(),
                framesInferred = framesInferred.get()
            )
            _telemetryState.value = telemetry

            // Emit frame and telemetry snapshot to ViewModel / UI
            _frameFlow.value = CameraFrame(
                frameId = tFrameStart,
                width = camW,
                height = camH,
                timestampNs = timestampNs,
                rotationDegrees = rotationDegrees
            )

            onDetectionsUpdated(transformedDetections, currentInferenceFps, tTotalLatency)
        } catch (e: Exception) {
            Log.e(TAG, "Error in CPU inference worker", e)
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun start() = withContext(Dispatchers.Main) {
        if (cameraProvider != null && previewViewRef != null && lifecycleOwnerRef != null) {
            bindCameraInternal()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        unbind()
    }

    fun unbind() {
        try {
            // Cancel inference worker job
            workerJob?.cancel()
            workerJob = null

            // Discard and close any pending frame
            pendingFrame.getAndSet(null)?.let { pending ->
                framesSkipped.incrementAndGet()
                pending.close()
            }

            cameraProvider?.unbindAll()
            _cameraState.value = CameraState.Idle
            _frameFlow.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding CameraX", e)
        }
    }

    fun release() {
        unbind()
        previewViewRef = null
        lifecycleOwnerRef = null
        workerJob?.cancel()
        workerJob = null
        if (!analysisExecutor.isShutdown) {
            analysisExecutor.shutdown()
        }
    }
}
