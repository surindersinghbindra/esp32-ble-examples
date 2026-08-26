package com.esp32ble.ota.data.ble

import android.content.Context
import android.net.Uri
import com.esp32ble.ota.domain.repository.FirmwareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FirmwareSourceImpl(private val context: Context) : FirmwareSource {

    override suspend fun loadFromAssets(assetPath: String): ByteArray = withContext(Dispatchers.IO) {
        context.assets.open(assetPath).use { it.readBytes() }
    }

    override suspend fun loadFromUri(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Could not open $uriString")
    }
}
