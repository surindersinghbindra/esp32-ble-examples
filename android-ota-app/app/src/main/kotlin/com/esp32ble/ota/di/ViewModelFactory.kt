package com.esp32ble.ota.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.esp32ble.ota.presentation.ota.OtaViewModel

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OtaViewModel::class.java)) {
            return OtaViewModel(
                scanForEspDevice = container.scanForEspDevice,
                connectToDevice = container.connectToDevice,
                disconnectDevice = container.disconnectDevice,
                performOtaUpdate = container.performOtaUpdate,
                rebootDevice = container.rebootDevice,
                loadFirmwareFromAssets = container.loadFirmwareFromAssets,
                loadFirmwareFromUri = container.loadFirmwareFromUri,
                readDeviceVersion = container.readDeviceVersion,
                extractFirmwareVersion = container.extractFirmwareVersion,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
