package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.OtaTransferEvent
import com.esp32ble.ota.domain.repository.BleOtaRepository
import kotlinx.coroutines.flow.Flow

/** The advertised device name this whole app is built to talk to (see ble-ota firmware). */
const val TARGET_DEVICE_NAME = "esp32-ble-ota"

class ScanForEspDeviceUseCase(private val repository: BleOtaRepository) {
    operator fun invoke(): Flow<BleDeviceInfo> = repository.scanForDevice(TARGET_DEVICE_NAME)
}

class ConnectToDeviceUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(device: BleDeviceInfo): Result<Int> = repository.connect(device)
}

class ReadDeviceVersionUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<String> = repository.readDeviceVersion()
}

class DisconnectDeviceUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke() = repository.disconnect()
}

class PerformOtaUpdateUseCase(private val repository: BleOtaRepository) {
    operator fun invoke(firmware: ByteArray): Flow<OtaTransferEvent> =
        repository.performOtaUpdate(firmware)
}

class RebootDeviceUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.reboot()
}
