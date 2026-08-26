package com.esp32ble.ota.presentation.ota

import com.esp32ble.ota.domain.model.BleDeviceInfo

data class FirmwareInfo(val label: String, val sizeBytes: Int, val version: String?)

sealed interface OtaUiState {
    data object Idle : OtaUiState
    data object Scanning : OtaUiState
    data class DeviceFound(val device: BleDeviceInfo) : OtaUiState
    data class Connecting(val device: BleDeviceInfo) : OtaUiState
    data class Connected(
        val device: BleDeviceInfo,
        val mtu: Int,
        val firmware: FirmwareInfo?,
        val deviceVersion: String?,
    ) : OtaUiState
    data class Updating(
        val device: BleDeviceInfo,
        val bytesSent: Int,
        val totalBytes: Int,
        val validating: Boolean,
    ) : OtaUiState
    data class UpdateSucceeded(val device: BleDeviceInfo) : OtaUiState
    data class UpdateFailed(val device: BleDeviceInfo, val reason: String) : OtaUiState
    data class Rebooting(val device: BleDeviceInfo) : OtaUiState
    data class Error(val message: String) : OtaUiState
}
