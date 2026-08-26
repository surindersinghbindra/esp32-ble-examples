package com.esp32ble.ota.presentation.ota

import com.esp32ble.ota.domain.model.BleDeviceInfo

data class FirmwareInfo(val label: String, val sizeBytes: Int, val version: String?)

/**
 * The screen's entire state as one sealed hierarchy - at any moment the UI is in *exactly one* of
 * these cases, never some inconsistent mix (e.g. "updating" while also "not yet connected"). The
 * ViewModel holds a `StateFlow<OtaUiState>` and Compose (`OtaScreen`) does a `when` over it to
 * decide what to draw; because it's `sealed`, that `when` is exhaustive and adding a new case here
 * forces every place that reacts to state to consciously handle it - the compiler won't let a spot
 * silently forget.
 */
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
