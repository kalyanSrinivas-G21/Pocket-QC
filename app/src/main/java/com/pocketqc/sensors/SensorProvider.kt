package com.pocketqc.sensors

import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware abstraction for onboard smartphone environmental and motion sensors
 * (accelerometer, gyroscope, ambient light sensor).
 */
interface SensorProvider {
    val stabilityState: StateFlow<DeviceStability>
    val lightingState: StateFlow<LightingCondition>

    fun start()
    fun stop()
}
