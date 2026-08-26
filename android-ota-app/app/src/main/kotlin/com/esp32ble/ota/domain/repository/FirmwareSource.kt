package com.esp32ble.ota.domain.repository

/** Reads firmware image bytes from wherever they live on the device. */
interface FirmwareSource {
    suspend fun loadFromAssets(assetPath: String): ByteArray
    suspend fun loadFromUri(uriString: String): ByteArray
}
