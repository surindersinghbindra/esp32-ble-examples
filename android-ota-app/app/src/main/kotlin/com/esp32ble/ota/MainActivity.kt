package com.esp32ble.ota

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.esp32ble.ota.di.AppContainer
import com.esp32ble.ota.di.ViewModelFactory
import com.esp32ble.ota.presentation.ota.OtaScreen
import com.esp32ble.ota.presentation.ota.OtaViewModel
import com.esp32ble.ota.presentation.theme.BleOtaTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val viewModel: OtaViewModel by viewModels {
        ViewModelFactory(AppContainer(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var hasPermissions by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(hasAllPermissions())
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { grants ->
                hasPermissions = grants.values.all { it }
            }

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
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val ledConfig by viewModel.ledConfig.collectAsStateWithLifecycle()
                    val heartRateSubscribed by viewModel.heartRateSubscribed.collectAsStateWithLifecycle()
                    val heartRateFastMode by viewModel.heartRateFastMode.collectAsStateWithLifecycle()
                    val currentBpm by viewModel.currentBpm.collectAsStateWithLifecycle()
                    val heartRateHistory by viewModel.heartRateHistory.collectAsStateWithLifecycle()
                    OtaScreen(
                        state = uiState,
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
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
}

@androidx.compose.runtime.Composable
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
