package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.HeartRateSample
import com.esp32ble.ota.domain.repository.BleOtaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// See the KDoc on ScanForEspDeviceUseCase in BleUseCases.kt for what @Inject constructor and
// operator fun invoke() are doing here - every use case in this app follows that same shape.

class ObserveHeartRateUseCase @Inject constructor(private val repository: BleOtaRepository) {
    operator fun invoke(): Flow<HeartRateSample> = repository.observeHeartRate()
}

class SetHeartRateFastModeUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(fast: Boolean): Result<Unit> = repository.setHeartRateFastMode(fast)
}
