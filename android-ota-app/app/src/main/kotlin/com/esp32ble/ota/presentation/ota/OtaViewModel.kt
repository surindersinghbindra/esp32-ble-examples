package com.esp32ble.ota.presentation.ota

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.model.LedMode
import com.esp32ble.ota.domain.model.OtaTransferEvent
import com.esp32ble.ota.domain.model.OtaTransport
import com.esp32ble.ota.domain.usecase.ConnectToDeviceUseCase
import com.esp32ble.ota.domain.usecase.DisconnectDeviceUseCase
import com.esp32ble.ota.domain.usecase.ExtractFirmwareVersionUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromAssetsUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromUriUseCase
import com.esp32ble.ota.domain.usecase.ObserveHeartRateUseCase
import com.esp32ble.ota.domain.usecase.PerformOtaUpdateUseCase
import com.esp32ble.ota.domain.usecase.ReadDeviceVersionUseCase
import com.esp32ble.ota.domain.usecase.ReadLedConfigUseCase
import com.esp32ble.ota.domain.usecase.RebootDeviceUseCase
import com.esp32ble.ota.domain.usecase.ScanForEspDeviceUseCase
import com.esp32ble.ota.domain.usecase.SetHeartRateFastModeUseCase
import com.esp32ble.ota.domain.usecase.WriteLedConfigUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private val readDeviceVersion: ReadDeviceVersionUseCase,
    private val extractFirmwareVersion: ExtractFirmwareVersionUseCase,
    private val readLedConfig: ReadLedConfigUseCase,
    private val writeLedConfig: WriteLedConfigUseCase,
    private val observeHeartRate: ObserveHeartRateUseCase,
    private val setHeartRateFastModeUseCase: SetHeartRateFastModeUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OtaUiState>(OtaUiState.Idle)
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()

    private val _ledConfig = MutableStateFlow<LedConfig?>(null)
    val ledConfig: StateFlow<LedConfig?> = _ledConfig.asStateFlow()

    private val _heartRateSubscribed = MutableStateFlow(false)
    val heartRateSubscribed: StateFlow<Boolean> = _heartRateSubscribed.asStateFlow()

    private val _heartRateFastMode = MutableStateFlow(false)
    val heartRateFastMode: StateFlow<Boolean> = _heartRateFastMode.asStateFlow()

    private val _currentBpm = MutableStateFlow<Int?>(null)
    val currentBpm: StateFlow<Int?> = _currentBpm.asStateFlow()

    /** Sampled at a fixed rate (see startHeartRate) independent of how fast BLE notifies. */
    private val _heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
    val heartRateHistory: StateFlow<List<Int>> = _heartRateHistory.asStateFlow()

    /** Which transport the *next* Start Update tap will use - see [OtaTransport] for what this means. */
    private val _otaTransport = MutableStateFlow(OtaTransport.GATT)
    val otaTransport: StateFlow<OtaTransport> = _otaTransport.asStateFlow()

    fun setOtaTransport(transport: OtaTransport) {
        _otaTransport.value = transport
    }

    private var currentDevice: BleDeviceInfo? = null
    private var pendingFirmware: ByteArray? = null
    private var heartRateCollectJob: Job? = null
    private var heartRateSampleJob: Job? = null

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
                    _uiState.value = OtaUiState.Connected(device, mtu, firmware = null, deviceVersion = null)
                    // Best-effort: if either of these fails, that section of the UI just stays blank.
                    readDeviceVersion().onSuccess { version ->
                        val connected = _uiState.value as? OtaUiState.Connected ?: return@onSuccess
                        _uiState.value = connected.copy(deviceVersion = version)
                    }
                    readLedConfig().onSuccess { config -> _ledConfig.value = config }
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
        val version = extractFirmwareVersion(bytes)
        _uiState.value = connected.copy(firmware = FirmwareInfo(label, bytes.size, version))
    }

    fun startUpdate() {
        val device = currentDevice ?: return
        val firmware = pendingFirmware ?: return
        viewModelScope.launch {
            performOtaUpdate(firmware, _otaTransport.value)
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
            stopHeartRate()
            _uiState.value = OtaUiState.Rebooting(device)
            rebootDevice() // The board disconnects on its own right after this; errors aren't actionable here.
            delay(1_500)
            disconnectDevice()
            resetToIdle()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopHeartRate()
            disconnectDevice()
            resetToIdle()
        }
    }

    /** Used from the error/failed states to start over without a fresh disconnect() being meaningful. */
    fun backToStart() {
        resetToIdle()
    }

    // --- LED control ---
    // Each setter does an optimistic local update (so sliders/buttons feel instant) and pushes
    // the full new config over BLE; the firmware applies it live and persists it to NVS.

    fun setLedMode(mode: LedMode) = updateLedConfig { it.copy(mode = mode) }

    fun setLedColor(red: Int, green: Int, blue: Int) =
        updateLedConfig { it.copy(mode = LedMode.SOLID, red = red, green = green, blue = blue) }

    fun setLedBrightness(brightness: Int) = updateLedConfig { it.copy(brightness = brightness) }

    fun setLedBlinkIntervalMs(intervalMs: Int) = updateLedConfig { it.copy(blinkIntervalMs = intervalMs) }

    private fun updateLedConfig(transform: (LedConfig) -> LedConfig) {
        val updated = transform(_ledConfig.value ?: return)
        _ledConfig.value = updated
        viewModelScope.launch { writeLedConfig(updated) }
    }

    // --- Heart rate ---

    fun toggleHeartRateSubscription() {
        if (_heartRateSubscribed.value) stopHeartRate() else startHeartRate()
    }

    private fun startHeartRate() {
        _heartRateSubscribed.value = true
        _heartRateHistory.value = emptyList()

        heartRateCollectJob = viewModelScope.launch {
            observeHeartRate()
                .catch { _heartRateSubscribed.value = false }
                .collect { sample -> _currentBpm.value = sample.bpm }
        }

        // The graph redraws on its own fixed cadence, decoupled from however fast BLE is
        // actually notifying - this is the client-side half of the backpressure story (the
        // other half, dropping old samples under load, lives in BleOtaRepositoryImpl).
        heartRateSampleJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                _currentBpm.value?.let { bpm ->
                    _heartRateHistory.update { (it + bpm).takeLast(50) }
                }
            }
        }
    }

    private fun stopHeartRate() {
        _heartRateSubscribed.value = false
        _heartRateFastMode.value = false
        heartRateCollectJob?.cancel()
        heartRateCollectJob = null
        heartRateSampleJob?.cancel()
        heartRateSampleJob = null
    }

    fun setHeartRateFastMode(fast: Boolean) {
        _heartRateFastMode.value = fast
        viewModelScope.launch { setHeartRateFastModeUseCase(fast) }
    }

    private fun resetToIdle() {
        currentDevice = null
        pendingFirmware = null
        _ledConfig.value = null
        _uiState.value = OtaUiState.Idle
    }
}
