package com.esp32ble.ota.domain.usecase

import com.esp32ble.ota.domain.model.FirmwareVersionReader
import com.esp32ble.ota.domain.repository.FirmwareSource
import javax.inject.Inject

/** Path inside app/src/main/assets/ where the bundled demo firmware lives. */
const val BUNDLED_FIRMWARE_ASSET_PATH = "firmware/ble_ota.bin"

// See the KDoc on ScanForEspDeviceUseCase in BleUseCases.kt for what @Inject constructor and
// operator fun invoke() are doing here - every use case in this app follows that same shape.

class LoadFirmwareFromAssetsUseCase @Inject constructor(private val source: FirmwareSource) {
    suspend operator fun invoke(assetPath: String = BUNDLED_FIRMWARE_ASSET_PATH): ByteArray =
        source.loadFromAssets(assetPath)
}

class LoadFirmwareFromUriUseCase @Inject constructor(private val source: FirmwareSource) {
    suspend operator fun invoke(uriString: String): ByteArray = source.loadFromUri(uriString)
}

/**
 * Reads the version string embedded in a firmware image, for the "about to install X" check.
 * This one has no constructor parameters at all - it's a pure function wrapped in a class purely
 * for consistency with the other use cases. `@Inject constructor()` (explicitly empty) is still
 * needed even here: without it, Hilt has no idea it's allowed to construct this class for
 * whatever asks for it (`OtaViewModel`, in this case).
 */
class ExtractFirmwareVersionUseCase @Inject constructor() {
    operator fun invoke(bytes: ByteArray): String? = FirmwareVersionReader.fromImageBytes(bytes)
}
