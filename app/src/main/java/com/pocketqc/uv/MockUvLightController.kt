package com.pocketqc.uv

import kotlinx.coroutines.delay

/**
 * Mock controller used for development, unit testing, and when no physical
 * USB UV hardware is connected.
 *
 * Transparently indicates demo / disconnected mode without faking real hardware.
 */
class MockUvLightController(
    private val simulateHardwarePresent: Boolean = false
) : UvLightController {

    private var _isLightOn = false
    override val isLightOn: Boolean
        get() = _isLightOn

    private var _isConnected = simulateHardwarePresent
    override val isConnected: Boolean
        get() = _isConnected

    override val status: UvHardwareStatus
        get() = if (_isConnected) {
            UvHardwareStatus.ConnectedRealHardware
        } else {
            UvHardwareStatus.NotConnected("Demo / Mock Mode (USB UV hardware not attached)")
        }

    override suspend fun connect(): Result<Unit> {
        delay(100)
        _isConnected = true
        return Result.success(Unit)
    }

    override suspend fun turnOn(): Result<Unit> {
        delay(50)
        _isLightOn = true
        return Result.success(Unit)
    }

    override suspend fun turnOff(): Result<Unit> {
        delay(50)
        _isLightOn = false
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _isLightOn = false
        _isConnected = false
    }
}
