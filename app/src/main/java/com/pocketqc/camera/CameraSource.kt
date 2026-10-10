package com.pocketqc.camera

import kotlinx.coroutines.flow.StateFlow

/**
 * Architectural abstraction for the camera subsystem.
 * Allows decoupling Compose UI and QC business logic from Camera2 / CameraX implementation.
 */
interface CameraSource {
    val cameraState: StateFlow<CameraState>
    val frameFlow: StateFlow<CameraFrame?>

    suspend fun start()
    suspend fun stop()
}
