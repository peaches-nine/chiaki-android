package com.metallic.chiaki.lib

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DualSenseDriver(
    private val context: Context,
    private val onInputChanged: (ControllerState) -> Unit,
    private val onDeviceStatusChanged: (Boolean) -> Unit  // true = connected & active
) {
    companion object {
        private const val TAG = "DualSenseDriver"
        private val SUPPORTED_VIDS = intArrayOf(0x054C)   // Sony
        private val SUPPORTED_PIDS = intArrayOf(0x0CE6, 0x0DF2)  // DualSense, DualSense Edge
        private const val REPORT_SIZE = 64
    }

    @Volatile var active: Boolean = false
        private set

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var inEndpt: UsbEndpoint? = null
    private var outEndpt: UsbEndpoint? = null
    private var inputThread: Thread? = null
    @Volatile private var stopped: Boolean = false
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    // Current controller state
    private val state = ControllerState()

    fun findDevice(): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (dev in usbManager.deviceList.values) {
            for (vid in SUPPORTED_VIDS) {
                for (pid in SUPPORTED_PIDS) {
                    if (dev.vendorId == vid && dev.productId == pid) {
                        return dev
                    }
                }
            }
        }
        return null
    }

    fun start(dev: UsbDevice): Boolean {
        if (active) return true

        device = dev
        stopped = false

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        connection = usbManager.openDevice(dev) ?: run {
            Log.e(TAG, "Failed to open USB device")
            return false
        }

        // Only claim HID interfaces, leave Audio interfaces for Android system
        claimedInterfaces.clear()
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                if (!connection!!.claimInterface(iface, true)) {
                    Log.e(TAG, "Failed to claim HID interface $i")
                    stop()
                    return false
                }
                claimedInterfaces.add(iface)
                Log.d(TAG, "Claimed HID interface $i")
            } else {
                Log.d(TAG, "Skipping interface $i (class=${iface.interfaceClass}) for Android system")
            }
        }

        // Find IN/OUT endpoints on the HID interface
        inEndpt = null
        outEndpt = null
        for (iface in claimedInterfaces) {
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    inEndpt = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    outEndpt = ep
                }
            }
        }

        if (inEndpt == null || outEndpt == null) {
            Log.e(TAG, "Missing required endpoints")
            stop()
            return false
        }

        // Start input polling thread
        inputThread = Thread(this::inputLoop, "DualSenseDriver-Input").apply { start() }
        active = true
        onDeviceStatusChanged(true)
        Log.i(TAG, "DualSenseDriver started successfully")
        return true
    }

    fun stop() {
        stopped = true
        inputThread?.interrupt()
        inputThread = null

        try {
            claimedInterfaces.forEach { iface ->
                connection?.releaseInterface(iface)
            }
        } catch (_: Exception) {}
        claimedInterfaces.clear()

        try { connection?.close() } catch (_: Exception) {}
        connection = null
        device = null
        inEndpt = null
        outEndpt = null

        if (active) {
            active = false
            onDeviceStatusChanged(false)
        }
        Log.i(TAG, "DualSenseDriver stopped")
    }

    fun sendOutputReport(report: ByteArray) {
        connection?.let { conn ->
            outEndpt?.let { ep ->
                val res = conn.bulkTransfer(ep, report, report.size, 1000)
                if (res != report.size) {
                    Log.w(TAG, "bulkTransfer(OUT) failed: $res")
                }
            }
        }
    }

    fun sendRumble(left: UByte, right: UByte) {
        if (!active) return
        val report = DualSenseReportBuilder.buildRumbleReport(left, right)
        sendOutputReport(report)
    }

    fun sendTriggerEffects(
        leftType: UByte, leftData: ByteArray,
        rightType: UByte, rightData: ByteArray
    ) {
        if (!active) return
        val report = DualSenseReportBuilder.buildTriggerEffectReport(
            leftType, leftData, rightType, rightData)
        sendOutputReport(report)
    }

    // ─── Input parsing ───────────────────────────────────────────────

    private val parseBuf = ByteBuffer.allocateDirect(REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun inputLoop() {
        try {
            Thread.sleep(1000)  // Let kernel driver detach settle
        } catch (_: InterruptedException) { return }

        val buffer = ByteArray(REPORT_SIZE)  // Reused across reads
        while (!Thread.interrupted() && !stopped) {
            val conn = connection ?: break
            val ep = inEndpt ?: break

            var res: Int
            do {
                val lastMs = SystemClock.uptimeMillis()
                res = conn.bulkTransfer(ep, buffer, REPORT_SIZE, 3000)
                if (res == 0) res = -1
                // Detect I/O error (fast failure)
                if (res == -1 && SystemClock.uptimeMillis() - lastMs < 1000) {
                    Log.w(TAG, "Device I/O error, stopping driver")
                    stop()
                    return
                }
            } while (res == -1 && !stopped)

            if (res <= 0 || stopped) break

            parseBuf.clear()
            parseBuf.put(buffer, 0, res)
            parseBuf.flip()
            parseReport(parseBuf)
            onInputChanged(state)
        }
    }

    private fun parseReport(buf: ByteBuffer) {
        if (buf.remaining() != 64) return

        buf.get()  // Skip report ID byte

        // ── Analog sticks (bytes 1-4, raw 0-255) ──
        val lsX = buf.get(1).toInt() and 0xFF
        val lsY = buf.get(2).toInt() and 0xFF
        val rsX = buf.get(3).toInt() and 0xFF
        val rsY = buf.get(4).toInt() and 0xFF

        // Convert 0-255 → -1..1 → chiaki Short range
        state.leftX = normalizeStick(lsX)
        state.leftY = normalizeStick(lsY)
        state.rightX = normalizeStick(rsX)
        state.rightY = normalizeStick(rsY)

        // ── Triggers (bytes 5-6, raw 0-255) ──
        val l2 = buf.get(5).toInt() and 0xFF
        val r2 = buf.get(6).toInt() and 0xFF
        state.l2State = l2.toUByte()
        state.r2State = r2.toUByte()

        // ── Buttons ──
        val byte8 = buf.get(8).toInt() and 0xFF
        val byte9 = buf.get(9).toInt() and 0xFF
        val byte10 = buf.get(10).toInt() and 0xFF

        var buttons = 0U

        // D-pad (byte8 bits 0-3)
        val dpad = byte8 and 0x0F
        when (dpad) {
            0, 1, 7 -> buttons = buttons or ControllerState.BUTTON_DPAD_UP
        }
        when (dpad) {
            3, 4, 5 -> buttons = buttons or ControllerState.BUTTON_DPAD_DOWN
        }
        when (dpad) {
            5, 6, 7 -> buttons = buttons or ControllerState.BUTTON_DPAD_LEFT
        }
        when (dpad) {
            1, 2, 3 -> buttons = buttons or ControllerState.BUTTON_DPAD_RIGHT
        }

        // Face buttons (byte8 bits 4-7)
        if ((byte8 and 0x20) != 0) buttons = buttons or ControllerState.BUTTON_CROSS
        if ((byte8 and 0x40) != 0) buttons = buttons or ControllerState.BUTTON_MOON
        if ((byte8 and 0x10) != 0) buttons = buttons or ControllerState.BUTTON_BOX
        if ((byte8 and 0x80) != 0) buttons = buttons or ControllerState.BUTTON_PYRAMID

        // Shoulder buttons (byte9 bits 0-1)
        if ((byte9 and 0x01) != 0) buttons = buttons or ControllerState.BUTTON_L1
        if ((byte9 and 0x02) != 0) buttons = buttons or ControllerState.BUTTON_R1

        // Options/Share (byte9 bits 4-5)
        if ((byte9 and 0x10) != 0) buttons = buttons or ControllerState.BUTTON_SHARE
        if ((byte9 and 0x20) != 0) buttons = buttons or ControllerState.BUTTON_OPTIONS

        // Stick clicks (byte9 bits 6-7)
        if ((byte9 and 0x40) != 0) buttons = buttons or ControllerState.BUTTON_L3
        if ((byte9 and 0x80) != 0) buttons = buttons or ControllerState.BUTTON_R3

        // PS / Touchpad / Mic (byte10)
        if ((byte10 and 0x01) != 0) buttons = buttons or ControllerState.BUTTON_PS
        if ((byte10 and 0x02) != 0) buttons = buttons or ControllerState.BUTTON_TOUCHPAD

        state.buttons = buttons

        // ── IMU (gyro + accel) ──
        val GYRO_SCALE = 2000.0f / 32768.0f
        val ACCEL_SCALE = 4.0f / 32768.0f
        val G_TO_MS2 = 9.81f

        state.gyroX = buf.getShort(16).toFloat() * GYRO_SCALE
        state.gyroY = buf.getShort(18).toFloat() * GYRO_SCALE
        state.gyroZ = buf.getShort(20).toFloat() * GYRO_SCALE
        state.accelX = buf.getShort(22).toFloat() * ACCEL_SCALE * G_TO_MS2
        state.accelY = buf.getShort(24).toFloat() * ACCEL_SCALE * G_TO_MS2
        state.accelZ = buf.getShort(26).toFloat() * ACCEL_SCALE * G_TO_MS2

        // ── Touchpad (dual finger) ──
        parseTouchpadFinger(buf, 33, 0)
        parseTouchpadFinger(buf, 37, 1)
    }

    private fun parseTouchpadFinger(buf: ByteBuffer, offset: Int, fingerIdx: Int) {
        val b0 = buf.get(offset).toInt() and 0xFF
        val active = (b0 and 0x80) == 0
        val id = b0 and 0x7F

        if (fingerIdx >= state.touches.size) return

        if (active) {
            val x = ((buf.get(offset + 2).toInt() and 0x0F) shl 8) or (buf.get(offset + 1).toInt() and 0xFF)
            val y = ((buf.get(offset + 3).toInt() and 0xFF) shl 4) or ((buf.get(offset + 2).toInt() and 0xF0) shr 4)
            state.touches[fingerIdx].id = id.toByte()
            state.touches[fingerIdx].x = x.toUShort()
            state.touches[fingerIdx].y = y.toUShort()
        } else {
            state.touches[fingerIdx].id = -1
        }
    }

    private fun normalizeStick(raw: Int): Short {
        // Convert 0-255 → -32767..32767
        val centered = raw - 128
        val scaled = centered * 258f  // 258 ≈ 32767/127
        return scaled.toInt().coerceIn(-32767, 32767).toShort()
    }
}
