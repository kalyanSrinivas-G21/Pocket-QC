package com.pocketqc.uv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "UsbUvController"
private const val ACTION_USB_PERMISSION = "com.pocketqc.USB_PERMISSION"

/**
 * Production implementation of [UvLightController] interfacing with an external
 * microcontroller or USB-relay board via Android USB Host / OTG.
 *
 * SAFETY ARCHITECTURE:
 * Commands sent (e.g., "UV_ON\n", "UV_OFF\n") only trigger an external relay or MOSFET.
 * No power for the UV illumination source is drawn through the phone's USB port.
 */
class UsbSerialUvLightController(
    private val context: Context
) : UvLightController {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private var targetDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null

    private var _isLightOn = false
    override val isLightOn: Boolean
        get() = _isLightOn

    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    private var _status: UvHardwareStatus = UvHardwareStatus.NotConnected("Searching for USB controller...")
    override val status: UvHardwareStatus
        get() = _status

    init {
        detectDevice()
    }

    fun detectDevice(): UsbDevice? {
        val manager = usbManager ?: run {
            _status = UvHardwareStatus.NotConnected("USB Host mode not supported on device")
            return null
        }

        val deviceList = manager.deviceList
        if (deviceList.isEmpty()) {
            _status = UvHardwareStatus.NotConnected("No USB device attached via OTG")
            return null
        }

        // Check for attached USB devices (Microcontroller / Serial / Relay)
        for ((_, device) in deviceList) {
            // Check for communication / vendor classes
            val isSerialOrVendor = device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                    device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE ||
                    device.deviceClass == UsbConstants.USB_CLASS_VENDOR_SPEC

            if (isSerialOrVendor || device.interfaceCount > 0) {
                targetDevice = device
                _status = if (manager.hasPermission(device)) {
                    UvHardwareStatus.ConnectedRealHardware
                } else {
                    UvHardwareStatus.NotConnected("USB permission pending for ${device.deviceName}")
                }
                return device
            }
        }

        _status = UvHardwareStatus.NotConnected("No compatible UV relay controller found")
        return null
    }

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = usbManager ?: throw IllegalStateException("USB service unavailable")
            val device = targetDevice ?: detectDevice() ?: throw IllegalStateException("No USB device attached")

            if (!manager.hasPermission(device)) {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                manager.requestPermission(device, permissionIntent)
                throw IllegalStateException("USB permission required for ${device.deviceName}")
            }

            val conn = manager.openDevice(device) ?: throw IllegalStateException("Failed to open USB device")
            connection = conn

            // Find bulk transfer OUT endpoint
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(e)
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        conn.claimInterface(iface, true)
                        usbInterface = iface
                        outEndpoint = endpoint
                        break
                    }
                }
                if (outEndpoint != null) break
            }

            _isConnected = true
            _status = UvHardwareStatus.ConnectedRealHardware
            Log.i(TAG, "Successfully connected to USB UV controller: ${device.deviceName}")
            Unit
        }
    }

    override suspend fun turnOn(): Result<Unit> = withContext(Dispatchers.IO) {
        sendCommand("UV_ON\n").also {
            if (it.isSuccess) _isLightOn = true
        }
    }

    override suspend fun turnOff(): Result<Unit> = withContext(Dispatchers.IO) {
        sendCommand("UV_OFF\n").also {
            if (it.isSuccess) _isLightOn = false
        }
    }

    private fun sendCommand(cmd: String): Result<Unit> {
        val conn = connection ?: return Result.failure(IllegalStateException("Not connected"))
        val endpoint = outEndpoint ?: return Result.failure(IllegalStateException("No OUT endpoint"))

        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        val transferred = conn.bulkTransfer(endpoint, bytes, bytes.size, 1000)

        return if (transferred >= 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("USB transfer failed with code: $transferred"))
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                turnOff()
                usbInterface?.let { connection?.releaseInterface(it) }
                connection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting USB UV controller", e)
            } finally {
                connection = null
                usbInterface = null
                outEndpoint = null
                _isConnected = false
                _isLightOn = false
                _status = UvHardwareStatus.NotConnected("Disconnected")
            }
        }
    }
}
