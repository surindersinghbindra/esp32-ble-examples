package com.esp32ble.ota.presentation.ota

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.OtaTransferEvent
import com.esp32ble.ota.domain.usecase.ConnectToDeviceUseCase
import com.esp32ble.ota.domain.usecase.DisconnectDeviceUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromAssetsUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromUriUseCase
import com.esp32ble.ota.domain.usecase.PerformOtaUpdateUseCase
import com.esp32ble.ota.domain.usecase.RebootDeviceUseCase
import com.esp32ble.ota.domain.usecase.ScanForEspDeviceUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class OtaViewModel(
    private val scanForEspDevice: ScanForEspDeviceUseCase,
    private val connectToDevice: ConnectToDeviceUseCase,
    private val disconnectDevice: DisconnectDeviceUseCase,
    private val performOtaUpdate: PerformOtaUpdateUseCase,
    private val rebootDevice: RebootDeviceUseCase,
    private val loadFirmwareFromAssets: LoadFirmwareFromAssetsUseCase,
    private val loadFirmwareFromUri: LoadFirmwareFromUriUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OtaUiState>(OtaUiState.Idle)
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()

    private var currentDevice: BleDeviceInfo? = null
    private var pendingFirmware: ByteArray? = null

    fun startScan() {
        viewModelScope.launch {
            _uiState.value = OtaUiState.Scanning
            val found = withTimeoutOrNull(15_000) {
                scanForEspDevice().first()
            }
            _uiState.value = if (found != null) {
                OtaUiState.DeviceFound(found)
            } else {
                OtaUiState.Error("No device advertising as esp32-ble-ota was found nearby")
            }
        }
    }

    fun connect(device: BleDeviceInfo) {
        viewModelScope.launch {
            _uiState.value = OtaUiState.Connecting(device)
            connectToDevice(device)
                .onSuccess { mtu ->
                    currentDevice = device
                    _uiState.value = OtaUiState.Connected(device, mtu, firmware = null)
                }
                .onFailure { e ->
                    _uiState.value = OtaUiState.Error(e.message ?: "Failed to connect")
                }
        }
    }

    fun useBundledFirmware() {
        viewModelScope.launch {
            runCatching { loadFirmwareFromAssets() }
                .onSuccess { bytes -> onFirmwareLoaded("Bundled demo firmware", bytes) }
                .onFailure { e -> _uiState.value = OtaUiState.Error(e.message ?: "Failed to read bundled firmware") }
        }
    }

    fun useFirmwareFromUri(uriString: String, displayName: String) {
        viewModelScope.launch {
            runCatching { loadFirmwareFromUri(uriString) }
                .onSuccess { bytes -> onFirmwareLoaded(displayName, bytes) }
                .onFailure { e -> _uiState.value = OtaUiState.Error(e.message ?: "Failed to read selected file") }
        }
    }

    private fun onFirmwareLoaded(label: String, bytes: ByteArray) {
        pendingFirmware = bytes
        val connected = _uiState.value as? OtaUiState.Connected ?: return
        _uiState.value = connected.copy(firmware = FirmwareInfo(label, bytes.size))
    }

    fun startUpdate() {
        val device = currentDevice ?: return
        val firmware = pendingFirmware ?: return
        viewModelScope.launch {
            performOtaUpdate(firmware)
                .catch { e ->
                    _uiState.value = OtaUiState.UpdateFailed(device, e.message ?: "Unknown error")
                }
                .collect { event ->
                    _uiState.value = when (event) {
                        is OtaTransferEvent.Progress ->
                            OtaUiState.Updating(device, event.bytesSent, event.totalBytes, validating = false)
                        OtaTransferEvent.Validating ->
                            OtaUiState.Updating(device, firmware.size, firmware.size, validating = true)
                        OtaTransferEvent.Success ->
                            OtaUiState.UpdateSucceeded(device)
                        is OtaTransferEvent.Failure ->
                            OtaUiState.UpdateFailed(device, event.reason)
                    }
                }
        }
    }

    fun reboot() {
        val device = currentDevice ?: return
        viewModelScope.launch {
            _uiState.value = OtaUiState.Rebooting(device)
            rebootDevice() // The board disconnects on its own right after this; errors aren't actionable here.
            delay(1_500)
            disconnectDevice()
            resetToIdle()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            disconnectDevice()
            resetToIdle()
        }
    }

    /** Used from the error/failed states to start over without a fresh disconnect() being meaningful. */
    fun backToStart() {
        resetToIdle()
    }

    private fun resetToIdle() {
        currentDevice = null
        pendingFirmware = null
        _uiState.value = OtaUiState.Idle
    }
}
