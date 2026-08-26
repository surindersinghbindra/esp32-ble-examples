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
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * `@HiltViewModel` + `@Inject constructor` is the ViewModel equivalent of the `@Inject
 * constructor` pattern used throughout the `domain/usecase/` classes: it tells Hilt this class
 * can be built automatically, this time specifically wired into Android's `ViewModelProvider`
 * machinery. That's what lets `MainActivity` obtain this instance with the plain
 * `by viewModels()` delegate (no factory class to write or pass in by hand) as long as the
 * Activity itself is annotated `@AndroidEntryPoint` - see `MainActivity.kt`.
 *
 * Every constructor parameter here is a use case from `domain/usecase/` - this ViewModel never
 * imports `android.bluetooth.*` or talks to `BleOtaRepositoryImpl` directly, only to these
 * narrow, single-purpose classes. That's the actual point of the use-case layer: this
 * constructor signature alone tells you everything this screen can *do*, without reading a
 * single line of its body.
 */
@HiltViewModel
class OtaViewModel @Inject constructor(
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

    // `viewModelScope` is a CoroutineScope tied to this ViewModel's lifetime - Android cancels it
    // automatically when the ViewModel is cleared (e.g. the screen is finished for good), so a
    // coroutine launched here never needs manual cleanup the way a raw Thread would.
    fun startScan() {
        viewModelScope.launch {
            _uiState.value = OtaUiState.Scanning
            // `scanForEspDevice()` returns a cold Flow of every matching device seen; `.first()`
            // suspends until exactly one value arrives, then cancels the underlying scan for us.
            // `withTimeoutOrNull` wraps that suspension with a deadline: past 15s it cancels the
            // coroutine and yields `null` instead of throwing, which is why `found` is nullable
            // even though `.first()` alone would either return a value or throw.
            val found = withTimeoutOrNull(15_000.milliseconds) {
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
            // `Result<T>` is Kotlin's standard type for "either a success value or a caught
            // exception", returned rather than thrown - `.onSuccess {}` / `.onFailure {}` run their
            // lambda only in the matching case and otherwise pass `this` through unchanged, so they
            // chain the way `.let`/`.also` do.
            connectToDevice(device)
                .onSuccess { mtu ->
                    currentDevice = device
                    _uiState.value = OtaUiState.Connected(device, mtu, firmware = null, deviceVersion = null)
                    // Best-effort: if either of these fails, that section of the UI just stays blank.
                    readDeviceVersion().onSuccess { version ->
                        // `as?` is a *safe cast*: it yields the value typed as `Connected` if the
                        // state truly is that subtype, or null otherwise (rather than throwing a
                        // ClassCastException) - `?: return@onSuccess` then bails out of just this
                        // lambda if, by the time this suspend call resolved, the user had already
                        // disconnected and the state moved on to something else.
                        val connected = _uiState.value as? OtaUiState.Connected ?: return@onSuccess
                        // `.copy(...)` (free on every `data class`) returns a new instance with only
                        // the named field changed - `OtaUiState.Connected` itself is otherwise
                        // immutable (all `val`s), which is what makes it safe to hand out to Compose.
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
            delay(1_500.milliseconds)
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

        // Two independent coroutines, each doing one job, rather than one coroutine trying to do
        // both: this one just drains whatever BLE notifications arrive, as fast as they arrive,
        // into `_currentBpm`. `.catch {}` on a Flow only intercepts *upstream* exceptions (from
        // `observeHeartRate()` itself) - it can't catch anything thrown later in `.collect {}`.
        heartRateCollectJob = viewModelScope.launch {
            observeHeartRate()
                .catch { _heartRateSubscribed.value = false }
                .collect { sample -> _currentBpm.value = sample.bpm }
        }

        // The graph redraws on its own fixed cadence, decoupled from however fast BLE is
        // actually notifying - this is the client-side half of the backpressure story (the
        // other half, dropping old samples under load, lives in BleOtaRepositoryImpl).
        heartRateSampleJob = viewModelScope.launch {
            // `isActive` is true until this coroutine's Job is cancelled (by `stopHeartRate()`
            // below) - checking it is what makes this loop a well-behaved *cooperative* cancellation
            // point instead of a `while (true)` that would keep sampling forever after unsubscribe.
            while (isActive) {
                delay(200.milliseconds)
                // `_currentBpm.value?.let { bpm -> ... }` only runs the block when the value isn't
                // null (before the first BLE sample has ever arrived, it's still the initial null).
                _currentBpm.value?.let { bpm ->
                    // `StateFlow.update {}` atomically replaces the value with the result of the
                    // lambda applied to the current one - the safe way to do a "read old, compute
                    // new" update when multiple coroutines could otherwise race on a plain
                    // `.value = ...` assignment. `(it + bpm).takeLast(50)` appends one sample and
                    // caps the list at 50 entries, so the graph's data (and memory use) can't grow
                    // without bound over a long-running subscription.
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
