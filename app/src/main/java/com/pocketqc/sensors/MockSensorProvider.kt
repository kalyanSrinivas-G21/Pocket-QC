package com.pocketqc.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory simulated sensor provider for testing and development.
 */
class MockSensorProvider : SensorProvider {

    private val _stabilityState = MutableStateFlow(
        DeviceStability(isStable = true, pitchAngle = 0.5f, rollAngle = -0.2f, jitterMagnitude = 0.02f)
    )
    override val stabilityState: StateFlow<DeviceStability> = _stabilityState.asStateFlow()

    private val _lightingState = MutableStateFlow(
        LightingCondition(illuminanceLux = 520f, isAdequate = true, isUvActive = false)
    )
    override val lightingState: StateFlow<LightingCondition> = _lightingState.asStateFlow()

    override fun start() {
        // Mock is active immediately with nominal values
    }

    override fun stop() {
        // No-op for mock
    }

    fun updateStability(isStable: Boolean, jitter: Float = 0.05f) {
        _stabilityState.value = _stabilityState.value.copy(
            isStable = isStable,
            jitterMagnitude = jitter
        )
    }

    fun updateLighting(lux: Float, isAdequate: Boolean, isUvActive: Boolean = false) {
        _lightingState.value = LightingCondition(
            illuminanceLux = lux,
            isAdequate = isAdequate,
            isUvActive = isUvActive
        )
    }
}
