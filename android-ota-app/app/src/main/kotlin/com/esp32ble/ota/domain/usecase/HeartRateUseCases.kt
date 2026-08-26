package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.HeartRateSample
import com.esp32ble.ota.domain.repository.BleOtaRepository
import kotlinx.coroutines.flow.Flow

class ObserveHeartRateUseCase(private val repository: BleOtaRepository) {
    operator fun invoke(): Flow<HeartRateSample> = repository.observeHeartRate()
}

class SetHeartRateFastModeUseCase(private val repository: BleOtaRepository) {
    suspend operator fun invoke(fast: Boolean): Result<Unit> = repository.setHeartRateFastMode(fast)
}
