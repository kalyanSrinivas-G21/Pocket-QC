package com.pocketqc.uv

sealed class UvHardwareStatus {
    data object ConnectedRealHardware : UvHardwareStatus()
    data class NotConnected(val reason: String = "No USB UV controller detected") : UvHardwareStatus()
    data class Error(val message: String) : UvHardwareStatus()
}

/**
 * Abstraction for controlling the external UV fluorescence screening light.
 *
 * SAFETY PRINCIPLE:
 * The Android smartphone must never directly power a high-intensity UV lamp from its USB port.
 * The controller only sends low-voltage digital control signals to an MCU / Relay / MOSFET driver
 * that switches an independently powered UV light source.
 */
interface UvLightController {
    val isConnected: Boolean
    val isLightOn: Boolean
    val status: UvHardwareStatus

    suspend fun connect(): Result<Unit>
    suspend fun turnOn(): Result<Unit>
    suspend fun turnOff(): Result<Unit>
    suspend fun disconnect()
}
