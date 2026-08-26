package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.FirmwareVersionReader
import com.esp32ble.ota.domain.repository.FirmwareSource

/** Path inside app/src/main/assets/ where the bundled demo firmware lives. */
const val BUNDLED_FIRMWARE_ASSET_PATH = "firmware/ble_ota.bin"

class LoadFirmwareFromAssetsUseCase(private val source: FirmwareSource) {
    suspend operator fun invoke(assetPath: String = BUNDLED_FIRMWARE_ASSET_PATH): ByteArray =
        source.loadFromAssets(assetPath)
}

class LoadFirmwareFromUriUseCase(private val source: FirmwareSource) {
    suspend operator fun invoke(uriString: String): ByteArray = source.loadFromUri(uriString)
}

/** Reads the version string embedded in a firmware image, for the "about to install X" check. */
class ExtractFirmwareVersionUseCase {
    operator fun invoke(bytes: ByteArray): String? = FirmwareVersionReader.fromImageBytes(bytes)
}
