package com.esp32ble.ota.domain.model

enum class LedMode(val wireValue: Int) {
    OFF(0), SOLID(1), BLINK(2);

    companion object {
        fun fromWireValue(value: Int): LedMode = entries.firstOrNull { it.wireValue == value } ?: SOLID
    }
}

/**
 * Mirrors the 7-byte wire format of the LED Config characteristic in
 * ble-ota/components/led_service/led_service.c:
 *   [mode, red, green, blue, brightness, blink_interval_ms (uint16 LE)]
 */
data class LedConfig(
    val mode: LedMode,
    val red: Int,
    val green: Int,
    val blue: Int,
    val brightness: Int,
    val blinkIntervalMs: Int,
) {
    fun toWireBytes(): ByteArray {
        val lo = blinkIntervalMs and 0xFF
        val hi = (blinkIntervalMs shr 8) and 0xFF
        return byteArrayOf(
            mode.wireValue.toByte(),
            red.toByte(),
            green.toByte(),
            blue.toByte(),
            brightness.toByte(),
            lo.toByte(),
            hi.toByte(),
        )
    }

    companion object {
        const val WIRE_LEN = 7

        fun fromWireBytes(bytes: ByteArray): LedConfig? {
            if (bytes.size != WIRE_LEN) return null
            val lo = bytes[5].toInt() and 0xFF
            val hi = bytes[6].toInt() and 0xFF
            return LedConfig(
                mode = LedMode.fromWireValue(bytes[0].toInt() and 0xFF),
                red = bytes[1].toInt() and 0xFF,
                green = bytes[2].toInt() and 0xFF,
                blue = bytes[3].toInt() and 0xFF,
                brightness = bytes[4].toInt() and 0xFF,
                blinkIntervalMs = lo or (hi shl 8),
            )
        }
    }
}
