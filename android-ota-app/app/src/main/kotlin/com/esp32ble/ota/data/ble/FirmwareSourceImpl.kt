package com.esp32ble.ota.data.ble

import android.content.Context
import android.net.Uri
import com.esp32ble.ota.domain.repository.FirmwareSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `@Inject constructor` marks this as a class Hilt is allowed to build for us. Hilt sees the
 * `Context` parameter and, because it's annotated `@ApplicationContext` (a "qualifier" - Hilt's
 * way of picking between multiple things that are all just a `Context` under the hood: this app's
 * long-lived Application context vs. a shorter-lived Activity context), knows exactly which one
 * to hand over without us writing any wiring code. Nothing anywhere calls
 * `FirmwareSourceImpl(context)` directly any more - see [com.esp32ble.ota.di.RepositoryModule] for
 * how this gets connected to the [FirmwareSource] interface that the rest of the app depends on.
 */
class FirmwareSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FirmwareSource {

    override suspend fun loadFromAssets(assetPath: String): ByteArray = withContext(Dispatchers.IO) {
        context.assets.open(assetPath).use { it.readBytes() }
    }

    override suspend fun loadFromUri(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Could not open $uriString")
    }
}
