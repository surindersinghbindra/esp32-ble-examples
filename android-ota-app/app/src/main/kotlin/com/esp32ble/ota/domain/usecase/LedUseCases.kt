package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.repository.BleOtaRepository
import javax.inject.Inject

// See the KDoc on ScanForEspDeviceUseCase in BleUseCases.kt for what @Inject constructor and
// operator fun invoke() are doing here - every use case in this app follows that same shape.

class ReadLedConfigUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<LedConfig> = repository.readLedConfig()
}

class WriteLedConfigUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(config: LedConfig): Result<Unit> = repository.writeLedConfig(config)
}
