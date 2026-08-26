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
