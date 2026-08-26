package com.esp32ble.ota

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.esp32ble.ota.presentation.ota.OtaScreen
import com.esp32ble.ota.presentation.ota.OtaViewModel
import com.esp32ble.ota.presentation.theme.BleOtaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * `@AndroidEntryPoint` is Hilt's marker for "this Android framework class (Activity, Fragment,
 * Service, ...) participates in dependency injection". Android itself constructs Activities (we
 * never call `MainActivity()` ourselves), so Hilt can't just use a normal `@Inject constructor`
 * here the way it does for `OtaViewModel` - instead, this annotation makes Hilt hook into
 * `onCreate()` behind the scenes to wire everything up just before our code runs. The payoff:
 * `by viewModels()` below can find `OtaViewModel`'s dependencies automatically, with no factory
 * class of our own to write (compare this to how the same line looked before this app used
 * Hilt, in git history - it used to read
 * `by viewModels { ViewModelFactory(AppContainer(applicationContext)) }`).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // A plain Kotlin property, computed once when the Activity is created. `if/else` is an
    // *expression* in Kotlin (it evaluates to a value), not just a statement like in Java/C - so
    // this whole block can be assigned directly to a `val` instead of needing a separate
    // "declare then assign in a branch" dance.
    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    // `by viewModels()` is a Kotlin *property delegate* - the `by` keyword hands off "how do I
    // get/store this value" to another object (here, one Android provides) instead of us writing
    // that logic ourselves. What it actually does: look up (or, first time, create via Hilt) the
    // single `OtaViewModel` instance tied to this Activity, and survive configuration changes
    // (e.g. a screen rotation) without losing state - something a plain `private val viewModel =
    // OtaViewModel(...)` could never do on its own.
    private val viewModel: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // setContent { ... } is where a Compose screen replaces the old "inflate an XML layout"
        // step from classic Android views - everything inside this lambda is a description of
        // UI, re-run (a "recomposition") whenever something it reads changes.
        setContent {
            // `remember { mutableStateOf(...) }` is Compose's equivalent of a `var` that survives
            // recomposition and triggers a UI refresh whenever it's reassigned. Plain
            // `var hasPermissions = hasAllPermissions()` would reset to the same value on every
            // recomposition and never cause the UI to react to changes - `remember` is what makes
            // it "sticky" across those re-runs, and `mutableStateOf` is what makes writes to it
            // observable by Compose at all.
            var hasPermissions by remember { mutableStateOf(hasAllPermissions()) }

            // rememberLauncherForActivityResult wires up one of Android's ActivityResultContracts
            // (a typed, one-shot "launch this system UI, get a result back" pattern) as a Compose-
            // friendly callback. RequestMultiplePermissions launches the system permission dialog;
            // `grants` is a Map<String, Boolean> of permission name -> was it granted.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                hasPermissions = grants.values.all { it }
            }

            // OpenDocument() launches the system file picker; its result is a content:// Uri (or
            // null if the user backed out) rather than a real filesystem path - Android sandboxes
            // access to files outside an app's own storage this way, which is why
            // FirmwareSourceImpl reads it via ContentResolver rather than java.io.File.
            val filePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "picked file"
                    viewModel.useFirmwareFromUri(uri.toString(), name)
                }
            }

            BleOtaTheme {
                if (!hasPermissions) {
                    PermissionGateScreen { permissionLauncher.launch(requiredPermissions) }
                } else {
                    // collectAsStateWithLifecycle() bridges a ViewModel's StateFlow into Compose:
                    // it collects the flow only while this screen is actually visible/active
                    // (pausing when backgrounded, resuming when foregrounded again - avoiding
                    // pointless work and battery/CPU drain the rest of the time), and exposes the
                    // latest value as Compose `State`. The `by` here works the same way as
                    // `by viewModels()` above: it lets `uiState` etc. be read as a plain value
                    // below instead of needing `.value` on every use.
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val ledConfig by viewModel.ledConfig.collectAsStateWithLifecycle()
                    val heartRateSubscribed by viewModel.heartRateSubscribed.collectAsStateWithLifecycle()
                    val heartRateFastMode by viewModel.heartRateFastMode.collectAsStateWithLifecycle()
                    val currentBpm by viewModel.currentBpm.collectAsStateWithLifecycle()
                    val heartRateHistory by viewModel.heartRateHistory.collectAsStateWithLifecycle()
                    val otaTransport by viewModel.otaTransport.collectAsStateWithLifecycle()
                    OtaScreen(
                        state = uiState,
                        // `viewModel::startScan` is a *function reference* - shorthand for
                        // `{ viewModel.startScan() }`. Both mean "here's a function value that,
                        // when called, calls this method on this specific object"; the `::` form
                        // just avoids writing the wrapping lambda by hand.
                        onScan = viewModel::startScan,
                        onConnect = viewModel::connect,
                        onUseBundledFirmware = viewModel::useBundledFirmware,
                        onPickFirmware = { filePicker.launch(arrayOf("*/*")) },
                        onStartUpdate = viewModel::startUpdate,
                        onReboot = viewModel::reboot,
                        onDisconnect = viewModel::disconnect,
                        onBackToStart = viewModel::backToStart,
                        ledConfig = ledConfig,
                        onSetLedMode = viewModel::setLedMode,
                        onSetLedColor = viewModel::setLedColor,
                        onSetLedBrightness = viewModel::setLedBrightness,
                        onSetLedBlinkIntervalMs = viewModel::setLedBlinkIntervalMs,
                        heartRateSubscribed = heartRateSubscribed,
                        heartRateFastMode = heartRateFastMode,
                        currentBpm = currentBpm,
                        heartRateHistory = heartRateHistory,
                        onToggleHeartRate = viewModel::toggleHeartRateSubscription,
                        onSetHeartRateFastMode = viewModel::setHeartRateFastMode,
                        otaTransport = otaTransport,
                        onSetOtaTransport = viewModel::setOtaTransport,
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Content resolvers, not File paths, are how you read a user-picked document's real name. */
    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
}

@Composable
private fun PermissionGateScreen(onRequestPermissions: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("This app needs Bluetooth permission to scan for and connect to the ESP32 board.")
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
        }
    }
}
