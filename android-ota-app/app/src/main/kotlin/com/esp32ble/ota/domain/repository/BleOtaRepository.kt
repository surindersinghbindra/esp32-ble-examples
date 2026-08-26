package com.esp32ble.ota.domain.repository

import com.esp32ble.ota.domain.model.BleDeviceInfo
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

    suspend fun disconnect()

    /**
     * Streams [firmware] into whichever OTA partition the board isn't
     * currently running, emitting progress as it goes. The flow completes
     * after a terminal [OtaTransferEvent.Success] or [OtaTransferEvent.Failure].
     */
    fun performOtaUpdate(firmware: ByteArray): Flow<OtaTransferEvent>

    /** Tells the board to reboot into whichever partition is set as its boot target. */
    suspend fun reboot(): Result<Unit>
}
