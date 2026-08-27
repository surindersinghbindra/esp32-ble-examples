package com.esp32ble.ota.domain.model

/**
 * A Kotlin `enum class` can carry a constructor property per case - here, the exact integer the
 * firmware expects on the wire, so nothing else in the app needs to know "SOLID means 1".
 */
enum class LedMode(val wireValue: Int) {
    OFF(0), SOLID(1), BLINK(2);

    companion object {
        // `entries` (replacing the older `.values()`) is the compiler-generated list of every
        // case, in declaration order - handy for exactly this "reverse lookup by field" pattern.
        // `?: SOLID` is the Elvis operator: if `firstOrNull` finds nothing (null), fall back to
        // SOLID instead of crashing - a deliberately lenient default for a value read off the wire.
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
        // Kotlin's `Byte` is signed (-128..127), same as Java's, but BLE payloads are unsigned
        // bytes - splitting the 16-bit interval into low/high bytes for "little-endian" wire order
        // needs the bitwise ops below rather than plain arithmetic, since a value like 300 doesn't
        // fit in one byte at all.
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

        /**
         * Must match `CONFIG_BLE_OTA_PAIRING_PASSKEY` in `ble-ota/main/Kconfig.projbuild`
         * (default shown in `sdkconfig.defaults`). This app can't auto-submit a pairing PIN
         * itself - `BluetoothDevice.setPin()`/`setPasskey()` need the system-only
         * `BLUETOOTH_PRIVILEGED` permission, unavailable to a normal app - so this exists purely
         * as a UI hint telling you what to type into the system pairing prompt, not something
         * wired into the pairing flow. If you change the firmware's Kconfig value, update this
         * to match or the hint shown in the app will be wrong.
         */
        const val PAIRING_PASSKEY_HINT = "123456"

        fun fromWireBytes(bytes: ByteArray): LedConfig? {
            if (bytes.size != WIRE_LEN) return null
            // `.toInt() and 0xFF` re-widens a signed Kotlin Byte back to its unsigned 0-255
            // meaning - without the mask, a byte like 0xFF (-1 as a signed Byte) would become
            // Int -1 instead of 255 when widened naively.
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
