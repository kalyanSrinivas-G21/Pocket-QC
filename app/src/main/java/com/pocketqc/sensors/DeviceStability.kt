package com.pocketqc.sensors

/**
 * Encapsulates the physical orientation and mechanical vibration stability of the smartphone fixture.
 * Used for inspection gating to ensure frames are sharp and motion-free before triggering inference.
 */
data class DeviceStability(
    val isStable: Boolean,
    val pitchAngle: Float = 0f,
    val rollAngle: Float = 0f,
    val jitterMagnitude: Float = 0f
)
