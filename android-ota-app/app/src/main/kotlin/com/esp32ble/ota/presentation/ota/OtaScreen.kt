package com.esp32ble.ota.presentation.ota

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.model.LedMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaScreen(
    state: OtaUiState,
    onScan: () -> Unit,
    onConnect: (BleDeviceInfo) -> Unit,
    onUseBundledFirmware: () -> Unit,
    onPickFirmware: () -> Unit,
    onStartUpdate: () -> Unit,
    onReboot: () -> Unit,
    onDisconnect: () -> Unit,
    onBackToStart: () -> Unit,
    ledConfig: LedConfig?,
    onSetLedMode: (LedMode) -> Unit,
    onSetLedColor: (Int, Int, Int) -> Unit,
    onSetLedBrightness: (Int) -> Unit,
    onSetLedBlinkIntervalMs: (Int) -> Unit,
    heartRateSubscribed: Boolean,
    heartRateFastMode: Boolean,
    currentBpm: Int?,
    heartRateHistory: List<Int>,
    onToggleHeartRate: () -> Unit,
    onSetHeartRateFastMode: (Boolean) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("ESP32 BLE OTA") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                OtaUiState.Idle -> IdleContent(onScan)
                OtaUiState.Scanning -> ScanningContent()
                is OtaUiState.DeviceFound -> DeviceFoundContent(state, onConnect, onScan)
                is OtaUiState.Connecting -> ConnectingContent(state)
                is OtaUiState.Connected -> {
                    ConnectedContent(
                        state, onUseBundledFirmware, onPickFirmware, onStartUpdate, onDisconnect,
                    )
                    LedControlSection(ledConfig, onSetLedMode, onSetLedColor, onSetLedBrightness, onSetLedBlinkIntervalMs)
                    HeartRateSection(
                        heartRateSubscribed, heartRateFastMode, currentBpm, heartRateHistory,
                        onToggleHeartRate, onSetHeartRateFastMode,
                    )
                }
                is OtaUiState.Updating -> UpdatingContent(state)
                is OtaUiState.UpdateSucceeded -> SucceededContent(onReboot, onDisconnect)
                is OtaUiState.UpdateFailed -> FailedContent(state, onStartUpdate, onDisconnect)
                is OtaUiState.Rebooting -> RebootingContent()
                is OtaUiState.Error -> ErrorContent(state, onBackToStart)
            }
        }
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit) {
    Text("Not connected.")
    Text("Make sure the ESP32-C6 running the ble-ota firmware is powered on and advertising.")
    Button(onClick = onScan) { Text("Scan for esp32-ble-ota") }
}

@Composable
private fun ScanningContent() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text("Scanning for esp32-ble-ota...")
    }
}

@Composable
private fun DeviceFoundContent(
    state: OtaUiState.DeviceFound,
    onConnect: (BleDeviceInfo) -> Unit,
    onScanAgain: () -> Unit,
) {
    Text("Found device:")
    Text("${state.device.name ?: "(unnamed)"} - ${state.device.address}")
    Button(onClick = { onConnect(state.device) }) { Text("Connect") }
    OutlinedButton(onClick = onScanAgain) { Text("Scan again") }
}

@Composable
private fun ConnectingContent(state: OtaUiState.Connecting) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text("Connecting to ${state.device.address}...")
    }
}

@Composable
private fun ConnectedContent(
    state: OtaUiState.Connected,
    onUseBundledFirmware: () -> Unit,
    onPickFirmware: () -> Unit,
    onStartUpdate: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text("Connected to ${state.device.name ?: state.device.address}")
    Text("Negotiated ATT MTU: ${state.mtu}")
    Text("Running version: ${state.deviceVersion ?: "(reading...)"}")
    HorizontalDivider()
    Text("Firmware to install:")
    if (state.firmware != null) {
        Text("${state.firmware.label} - ${state.firmware.sizeBytes} bytes")
        Text("Version: ${state.firmware.version ?: "(unknown)"}")
        if (state.deviceVersion != null && state.firmware.version == state.deviceVersion) {
            Text("WARNING: device already reports this exact version - proceeding will re-flash the same build.")
        }
    } else {
        Text("(none selected yet)")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onUseBundledFirmware) { Text("Use bundled firmware") }
        OutlinedButton(onClick = onPickFirmware) { Text("Pick file...") }
    }
    Button(onClick = onStartUpdate, enabled = state.firmware != null) { Text("Start Update") }
    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
}

@Composable
private fun LedControlSection(
    config: LedConfig?,
    onSetMode: (LedMode) -> Unit,
    onSetColor: (Int, Int, Int) -> Unit,
    onSetBrightness: (Int) -> Unit,
    onSetBlinkIntervalMs: (Int) -> Unit,
) {
    HorizontalDivider()
    Text("LED Control", style = MaterialTheme.typography.titleMedium)
    if (config == null) {
        Text("(reading current LED state...)")
        return
    }

    Text("Color presets:")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSetColor(255, 0, 0) }) { Text("Red") }
        OutlinedButton(onClick = { onSetColor(0, 255, 0) }) { Text("Green") }
        OutlinedButton(onClick = { onSetColor(0, 0, 255) }) { Text("Blue") }
        OutlinedButton(onClick = { onSetColor(255, 255, 0) }) { Text("Yellow") }
    }

    Text("Mode:")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LedModeButton("Off", LedMode.OFF, config.mode, onSetMode)
        LedModeButton("Solid", LedMode.SOLID, config.mode, onSetMode)
        LedModeButton("Blink", LedMode.BLINK, config.mode, onSetMode)
    }

    Text("Brightness: ${config.brightness}")
    var brightnessPos by remember(config.brightness) { mutableStateOf(config.brightness.toFloat()) }
    Slider(
        value = brightnessPos,
        onValueChange = { brightnessPos = it },
        onValueChangeFinished = { onSetBrightness(brightnessPos.toInt()) },
        valueRange = 0f..255f,
    )

    if (config.mode == LedMode.BLINK) {
        Text("Blink interval: ${config.blinkIntervalMs} ms")
        var blinkPos by remember(config.blinkIntervalMs) { mutableStateOf(config.blinkIntervalMs.toFloat()) }
        Slider(
            value = blinkPos,
            onValueChange = { blinkPos = it },
            onValueChangeFinished = { onSetBlinkIntervalMs(blinkPos.toInt()) },
            valueRange = 100f..2000f,
        )
    }
}

@Composable
private fun LedModeButton(label: String, mode: LedMode, current: LedMode, onSetMode: (LedMode) -> Unit) {
    if (mode == current) {
        Button(onClick = { onSetMode(mode) }) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSetMode(mode) }, colors = ButtonDefaults.outlinedButtonColors()) { Text(label) }
    }
}

@Composable
private fun HeartRateSection(
    subscribed: Boolean,
    fastMode: Boolean,
    currentBpm: Int?,
    history: List<Int>,
    onToggleSubscribe: () -> Unit,
    onSetFastMode: (Boolean) -> Unit,
) {
    HorizontalDivider()
    Text("Heart Rate", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onToggleSubscribe) { Text(if (subscribed) "Unsubscribe" else "Subscribe") }
        if (subscribed) {
            Text("${currentBpm ?: "--"} BPM")
        }
    }
    if (subscribed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fast mode (~20/s, backpressure demo)")
            Switch(checked = fastMode, onCheckedChange = onSetFastMode)
        }
        HeartRateGraph(history)
    }
}

@Composable
private fun HeartRateGraph(history: List<Int>) {
    val lineColor = Color(0xFFE53935)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        if (history.size < 2) return@Canvas
        val minBpm = 50f
        val maxBpm = 170f
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { index, bpm ->
            val x = index * stepX
            val normalized = ((bpm - minBpm) / (maxBpm - minBpm)).coerceIn(0f, 1f)
            val y = size.height - normalized * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f))
    }
}

@Composable
private fun UpdatingContent(state: OtaUiState.Updating) {
    if (state.validating) {
        Text("Transfer complete - validating image on the device...")
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        val fraction = if (state.totalBytes > 0) state.bytesSent.toFloat() / state.totalBytes else 0f
        val percent = (fraction * 100).toInt()
        Text("Sending firmware... $percent%")
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text("${state.bytesSent} / ${state.totalBytes} bytes")
    }
}

@Composable
private fun SucceededContent(onReboot: () -> Unit, onDisconnect: () -> Unit) {
    Text("Update succeeded - the new image is validated and set to boot.")
    Text("It will keep running the previous firmware until you reboot it.")
    Button(onClick = onReboot) { Text("Reboot Device") }
    OutlinedButton(onClick = onDisconnect) { Text("Disconnect without rebooting") }
}

@Composable
private fun FailedContent(
    state: OtaUiState.UpdateFailed,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text("Update failed: ${state.reason}")
    Text("The device kept running its current firmware - it was never left in a broken state.")
    Button(onClick = onRetry) { Text("Retry") }
    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
}

@Composable
private fun RebootingContent() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text("Rebooting device...")
    }
}

@Composable
private fun ErrorContent(state: OtaUiState.Error, onBackToStart: () -> Unit) {
    Text("Something went wrong: ${state.message}")
    Button(onClick = onBackToStart) { Text("Back to start") }
}
