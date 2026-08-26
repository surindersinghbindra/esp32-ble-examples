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

/**
 * `content: @Composable () -> Unit` is a *higher-order function parameter*: a function type as an
 * argument, same idea as passing a lambda to `.map {}` on a list, except this one is marked
 * `@Composable` so it's allowed to itself call other Compose functions. `MainActivity` calls this
 * as `BleOtaTheme { ... the rest of the screen ... } `- Kotlin's trailing-lambda syntax lets the
 * last lambda argument be written outside the parentheses (and, since there are no other
 * arguments here, the parentheses are dropped entirely).
 */
@Composable
fun BleOtaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
