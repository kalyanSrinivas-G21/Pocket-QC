package com.pocketqc.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mock camera source for development, testing, and UI preview without requiring
 * physical camera hardware or Camera2 bindings.
 */
class MockCameraSource : CameraSource {

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _frameFlow = MutableStateFlow<CameraFrame?>(null)
    override val frameFlow: StateFlow<CameraFrame?> = _frameFlow.asStateFlow()

    private var frameCounter = 0L

    override suspend fun start() {
        _cameraState.value = CameraState.Initializing
        _cameraState.value = CameraState.Streaming
        emitMockFrame()
    }

    override suspend fun stop() {
        _cameraState.value = CameraState.Idle
        _frameFlow.value = null
    }

    fun emitMockFrame() {
        frameCounter++
        _frameFlow.value = CameraFrame(
            frameId = frameCounter,
            width = 1920,
            height = 1080,
            timestampNs = System.nanoTime(),
            rotationDegrees = 0,
            payload = null
        )
    }
}
