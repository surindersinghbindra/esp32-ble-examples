package com.esp32ble.ota.domain.model

/** A BLE device discovered by scanning, before any GATT connection exists. */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
)

/** One event emitted while an OTA transfer is running. */
sealed interface OtaTransferEvent {
    data class Progress(val bytesSent: Int, val totalBytes: Int) : OtaTransferEvent
    data object Validating : OtaTransferEvent
    data object Success : OtaTransferEvent
    data class Failure(val reason: String) : OtaTransferEvent
}

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
