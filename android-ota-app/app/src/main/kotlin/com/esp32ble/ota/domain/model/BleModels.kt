package com.esp32ble.ota.domain.model

/**
 * A Kotlin `data class` auto-generates `equals()`/`hashCode()`/`toString()`/`copy()` from its
 * constructor properties - the compiler writes the boilerplate a Java POJO would need by hand.
 * `name` is nullable (`String?`) because not every advertising BLE device includes one.
 */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
)

/**
 * A `sealed interface` restricts every implementation to this same file (or, since Kotlin 1.5,
 * anywhere in the same compilation module) - the compiler then *knows* it has seen every possible
 * subtype, so a `when (event) { ... }` over an `OtaTransferEvent` (see `OtaViewModel.startUpdate`)
 * needs no `else` branch to be exhaustive. This is Kotlin's answer to "model a fixed set of
 * cases", the same job an enum does, but here each case can carry different data.
 * `data object` (new in Kotlin 1.9) is for a singleton case with no payload - like `data class`,
 * it gets a sensible auto-generated `toString()`, which a plain `object` alone would not.
 */
sealed interface OtaTransferEvent {
    data class Progress(val bytesSent: Int, val totalBytes: Int) : OtaTransferEvent
    data object Validating : OtaTransferEvent
    data object Success : OtaTransferEvent
    data class Failure(val reason: String) : OtaTransferEvent
}

/** A plain custom exception type - `: Exception(message)` just forwards to the parent constructor. */
class BleOtaException(message: String) : Exception(message)

/**
 * Reads the `esp_app_desc_t.version` field embedded in a built ESP-IDF app
 * image, so the app can show "you're about to install version X" before
 * pushing an update. Must stay in sync with the same offsets used by
 * ble-ota/tools/ota_client.py's read_bin_version().
 */
object FirmwareVersionReader {
    private const val APP_DESC_OFFSET = 24 + 8 // esp_image_header_t + esp_image_segment_header_t
    private const val VERSION_FIELD_OFFSET = APP_DESC_OFFSET + 16 // magic_word + secure_version + reserv1[2]
    private const val VERSION_FIELD_LEN = 32

    fun fromImageBytes(bytes: ByteArray): String? {
        if (bytes.size < VERSION_FIELD_OFFSET + VERSION_FIELD_LEN) {
            return null
        }
        val raw = bytes.copyOfRange(VERSION_FIELD_OFFSET, VERSION_FIELD_OFFSET + VERSION_FIELD_LEN)
        val nullIndex = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, nullIndex, Charsets.UTF_8)
    }
}
