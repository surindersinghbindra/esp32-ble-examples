package com.esp32ble.ota.di

import android.content.Context
import com.esp32ble.ota.data.ble.BleOtaRepositoryImpl
import com.esp32ble.ota.data.ble.FirmwareSourceImpl
import com.esp32ble.ota.domain.repository.BleOtaRepository
import com.esp32ble.ota.domain.repository.FirmwareSource
import com.esp32ble.ota.domain.usecase.ConnectToDeviceUseCase
import com.esp32ble.ota.domain.usecase.DisconnectDeviceUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromAssetsUseCase
import com.esp32ble.ota.domain.usecase.LoadFirmwareFromUriUseCase
import com.esp32ble.ota.domain.usecase.PerformOtaUpdateUseCase
import com.esp32ble.ota.domain.usecase.RebootDeviceUseCase
import com.esp32ble.ota.domain.usecase.ScanForEspDeviceUseCase

/**
 * Minimal hand-rolled DI container - no Hilt/Koin, to keep the build simple. Everything is wired
 * through interfaces (BleOtaRepository, FirmwareSource), so swapping in a DI framework later would
 * only touch this file, not the domain or presentation layers.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val bleOtaRepository: BleOtaRepository by lazy { BleOtaRepositoryImpl(appContext) }
    private val firmwareSource: FirmwareSource by lazy { FirmwareSourceImpl(appContext) }

    val scanForEspDevice by lazy { ScanForEspDeviceUseCase(bleOtaRepository) }
    val connectToDevice by lazy { ConnectToDeviceUseCase(bleOtaRepository) }
    val disconnectDevice by lazy { DisconnectDeviceUseCase(bleOtaRepository) }
    val performOtaUpdate by lazy { PerformOtaUpdateUseCase(bleOtaRepository) }
    val rebootDevice by lazy { RebootDeviceUseCase(bleOtaRepository) }
    val loadFirmwareFromAssets by lazy { LoadFirmwareFromAssetsUseCase(firmwareSource) }
    val loadFirmwareFromUri by lazy { LoadFirmwareFromUriUseCase(firmwareSource) }
}
