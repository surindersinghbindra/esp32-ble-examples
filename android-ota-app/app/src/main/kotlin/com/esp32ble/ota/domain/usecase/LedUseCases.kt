package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.repository.BleOtaRepository

class ReadLedConfigUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<LedConfig> = repository.readLedConfig()
}

class WriteLedConfigUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(config: LedConfig): Result<Unit> = repository.writeLedConfig(config)
}
