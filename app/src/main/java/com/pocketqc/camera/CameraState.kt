package com.pocketqc.camera

/**
 * Lifecycle state of the camera hardware source.
 */
sealed interface CameraState {
    data object Idle : CameraState
    data object Initializing : CameraState
    data object Streaming : CameraState
    data class Error(val message: String) : CameraState
}
