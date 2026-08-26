package com.esp32ble.ota

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * `@HiltAndroidApp` is what actually switches Hilt on for this whole app. Behind the scenes it
 * makes this class generate (at compile time, via the KSP annotation processor configured in
 * `app/build.gradle.kts`) the root of Hilt's dependency graph - a Dagger "component" that lives
 * as long as the app process does. Every other Hilt annotation used elsewhere in this codebase
 * (`@HiltViewModel`, `@AndroidEntryPoint`, `@Inject constructor`, `@Module`) only works because
 * this class exists and is registered as the app's `<application>` class in AndroidManifest.xml.
 *
 * Kotlin note: this class has no body at all - `class BleOtaApplication : Application()` alone
 * would be enough to compile. The `@HiltAndroidApp` annotation is doing all the real work by
 * triggering Hilt's code generation; we don't need to override any of `Application`'s lifecycle
 * methods ourselves.
 */
@HiltAndroidApp
class BleOtaApplication : Application()
