package com.esp32ble.ota.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1565C0)
private val BlueDark = Color(0xFF90CAF9)

private val LightColors = lightColorScheme(primary = Blue)
private val DarkColors = darkColorScheme(primary = BlueDark)

@Composable
fun BleOtaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
