package com.esp32ble.ota.domain.repository

import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.HeartRateSample
import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.model.OtaTransferEvent
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app needs from the BLE transport, expressed independently of
 * the Android BLE APIs so the domain/presentation layers never touch
 * android.bluetooth.* directly.
 */
interface BleOtaRepository {

    /** Emits each time a device matching [targetName] is (re)discovered. */
    fun scanForDevice(targetName: String): Flow<BleDeviceInfo>

    /** Connects, discovers services, and negotiates the ATT MTU. Returns the negotiated MTU. */
    suspend fun connect(device: BleDeviceInfo): Result<Int>

    /** Reads the version string the connected device currently reports (see the Version characteristic). */
    suspend fun readDeviceVersion(): Result<String>

    suspend fun disconnect()

    /**
     * Streams [firmware] into whichever OTA partition the board isn't
     * currently running, emitting progress as it goes. The flow completes
     * after a terminal [OtaTransferEvent.Success] or [OtaTransferEvent.Failure].
     */
    fun performOtaUpdate(firmware: ByteArray): Flow<OtaTransferEvent>

    /** Tells the board to reboot into whichever partition is set as its boot target. */
    suspend fun reboot(): Result<Unit>

    /** Reads the LED's current color/mode/brightness/blink settings. */
    suspend fun readLedConfig(): Result<LedConfig>

    /** Applies [config] to the LED immediately; the board also persists it to NVS. */
    suspend fun writeLedConfig(config: LedConfig): Result<Unit>

    /**
     * Subscribes to heart rate notifications. Emits a sample every time one arrives; if the
     * board is notifying faster than the collector consumes them, older samples are dropped
     * rather than buffered without bound (backpressure) - see the implementation for the exact
     * strategy. Unsubscribes automatically when collection stops.
     */
    fun observeHeartRate(): Flow<HeartRateSample>

    /** Switches the board's heart rate notify rate between ~1/s (false) and ~20/s (true). */
    suspend fun setHeartRateFastMode(fast: Boolean): Result<Unit>
}
