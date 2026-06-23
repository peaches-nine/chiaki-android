package com.metallic.chiaki.lib

object DualSenseReportBuilder {
    // valid_flag0 bits:
    // 0x01|0x02 = main motors (rumble)
    // 0x04 = right trigger motor enable
    // 0x08 = left trigger motor enable
    // 0x10 = audio volume
    // 0x20 = speaker toggle
    // 0x40 = mic volume
    // 0x80 = mic/speaker toggle

    // valid_flag1 bits:
    // 0x01 = mic LED
    // 0x02 = audio/mic mute
    // 0x04 = touchpad LED strips
    // 0x10 = player indicator LEDs
    // 0x40 = motor/effect power level

    private const val REPORT_ID = 0x02
    private const val REPORT_SIZE = 48

    private val template: ByteArray = byteArrayOf(
        0x02,               // [ 0] Report ID
        (0x01 or 0x02).toByte(), // [ 1] valid_flag0 (motors enabled by default)
        0x00,               // [ 2] valid_flag1
        0x00,               // [ 3] right motor
        0x00,               // [ 4] left motor
        0x00, 0x00, 0x00, 0x00, // [ 5-8] padding
        0x00,               // [ 9] mute button LED
        0x10,               // [10] power save control (0x10 = mute LED off)
        0x00,               // [11] R2 trigger effect type
        0x00, 0x00, 0x00,   // [12-14] R2 effect params 1-3
        0x00, 0x00, 0x00, 0x00, // [15-18] R2 effect params 4-7
        0x00, 0x00, 0x00,   // [19-21] padding
        0x00,               // [22] L2 trigger effect type
        0x00, 0x00, 0x00,   // [23-25] L2 effect params 1-3
        0x00, 0x00, 0x00, 0x00, // [26-29] L2 effect params 4-7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // [30-39] padding
        0x02, 0x00,         // [40-41] audio related
        0x02, 0x00,         // [42-43] audio related
        0x00,               // [44] player LEDs
        0x78.toByte(),      // [45] LED R
        0x78.toByte(),      // [46] LED G
        0xEF.toByte()       // [47] LED B
    )

    fun buildRumbleReport(left: UByte, right: UByte): ByteArray {
        val report = template.copyOf()
        report[1] = (0x01 or 0x02).toByte()
        report[3] = right.toByte()   // high freq motor
        report[4] = left.toByte()    // low freq motor
        return report
    }

    fun buildTriggerEffectReport(
        leftType: UByte,
        leftData: ByteArray,
        rightType: UByte,
        rightData: ByteArray
    ): ByteArray {
        val report = template.copyOf()
        report[1] = (0x04 or 0x08).toByte()  // enable both triggers

        // Right trigger (bytes 11-20)
        report[11] = rightType.toByte()
        for (i in 0 until minOf(rightData.size, 10)) {
            report[12 + i] = rightData[i]
        }

        // Left trigger (bytes 22-31)
        report[22] = leftType.toByte()
        for (i in 0 until minOf(leftData.size, 10)) {
            report[23 + i] = leftData[i]
        }

        return report
    }

    fun buildCombinedReport(
        leftMotor: UByte,
        rightMotor: UByte,
        leftType: UByte,
        leftData: ByteArray,
        rightType: UByte,
        rightData: ByteArray
    ): ByteArray {
        val report = template.copyOf()
        report[1] = (0x01 or 0x02 or 0x04 or 0x08).toByte()  // motors + both triggers
        report[3] = rightMotor.toByte()
        report[4] = leftMotor.toByte()

        // Right trigger
        report[11] = rightType.toByte()
        for (i in 0 until minOf(rightData.size, 10)) {
            report[12 + i] = rightData[i]
        }

        // Left trigger
        report[22] = leftType.toByte()
        for (i in 0 until minOf(leftData.size, 10)) {
            report[23 + i] = leftData[i]
        }

        return report
    }

    fun buildClearReport(): ByteArray {
        return template.copyOf()
    }

    private fun minOf(a: Int, b: Int) = if (a < b) a else b
}
