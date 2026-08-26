package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.OtaTransferEvent
import com.esp32ble.ota.domain.model.OtaTransport
import com.esp32ble.ota.domain.repository.BleOtaRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The advertised device name this whole app is built to talk to (see ble-ota firmware). */
const val TARGET_DEVICE_NAME = "esp32-ble-ota"

/**
 * A **use case** is Clean Architecture's name for "one single thing the app can do", wrapped in
 * its own tiny class instead of being a method buried inside a bigger repository or ViewModel.
 * The benefit shows up in the ViewModel: instead of a fat `BleOtaRepository` object with dozens
 * of methods, `OtaViewModel`'s constructor lists exactly the handful of specific actions it
 * actually performs, each independently readable, testable, and reusable.
 *
 * Every use case in this file follows the same two Kotlin patterns, explained here once:
 *
 * 1. **`@Inject constructor(private val repository: BleOtaRepository)`** - the `@Inject` tells
 *    Hilt "you're allowed to construct this class yourself, and here's what it needs". Hilt
 *    already knows how to produce a `BleOtaRepository` (see `di/RepositoryModule.kt`), so it can
 *    now also produce any of these use cases automatically, and in turn `OtaViewModel`, which
 *    depends on all of them. Nobody writes `ScanForEspDeviceUseCase(repository)` by hand anywhere.
 * 2. **`operator fun invoke(...)`** - marking a function `operator` and naming it exactly
 *    `invoke` lets Kotlin call an *instance* of the class as if it were a function. Given a
 *    `val scan: ScanForEspDeviceUseCase`, both `scan.invoke()` and just `scan()` compile to the
 *    same thing - `OtaViewModel` calls `scanForEspDevice()`, which reads like calling a plain
 *    function even though it's really invoking a method on an injected object. This is purely a
 *    readability convention, not required for the DI or Clean Architecture parts to work.
 */
class ScanForEspDeviceUseCase @Inject constructor(private val repository: BleOtaRepository) {
    operator fun invoke(): Flow<BleDeviceInfo> = repository.scanForDevice(TARGET_DEVICE_NAME)
}

class ConnectToDeviceUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(device: BleDeviceInfo): Result<Int> = repository.connect(device)
}

class ReadDeviceVersionUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<String> = repository.readDeviceVersion()
}

class DisconnectDeviceUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke() = repository.disconnect()
}

class PerformOtaUpdateUseCase @Inject constructor(private val repository: BleOtaRepository) {
    operator fun invoke(firmware: ByteArray, transport: OtaTransport = OtaTransport.GATT): Flow<OtaTransferEvent> =
        repository.performOtaUpdate(firmware, transport)
}

class RebootDeviceUseCase @Inject constructor(private val repository: BleOtaRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.reboot()
}
