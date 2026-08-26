# ESP32-C6 Examples

A collection of ESP-IDF example projects for the ESP32-C6, each in its own self-contained
subfolder. Every subfolder is an independent ESP-IDF project — `cd` into it and run `idf.py`
commands from there. One subfolder, [`android-ota-app/`](android-ota-app/), is a real Android app
rather than an ESP-IDF project — see [Companion Android app](#companion-android-app) below.

## Examples

| Folder | Description |
|---|---|
| [`led-blink/`](led-blink/) | Blinks the onboard addressable RGB LED, cycling through a different color on each blink. |
| [`ble-ota/`](ble-ota/) | Pushes a new firmware image over BLE into a dual-partition (ota_0/ota_1) layout, with rollback protection if the new image never confirms itself healthy. |

More examples will be added as new subfolders alongside these.

## Companion Android app

[`android-ota-app/`](android-ota-app/) is a Jetpack Compose app (MVVM + Clean Architecture) that
drives the `ble-ota` example from a phone: scan, connect, pick a firmware file, watch a live
progress bar, and reboot the board once it confirms success. Verified end-to-end on real hardware
— see its own README for the architecture, protocol details, and what that test run found. It's a
separate Gradle project, not built by `idf.py`.

## Prerequisites (shared across all examples)

* [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c6/get-started/index.html) v6.1 (tested against `v6.1-beta1`)
* An ESP32-C6 dev board connected via USB-C

## Working with an example

```bash
source ~/.espressif/v6.1-beta1/esp-idf/export.sh   # activate the ESP-IDF environment
cd <example-folder>                                # e.g. led-blink/
idf.py set-target esp32c6
idf.py build
idf.py -p /dev/cu.usbserial-10 flash monitor       # replace with your board's serial port
```

See each example's own `README.md` for project-specific details, structure, and configuration
options.

## Mental Model: ESP-IDF vs. Android Projects

If you're coming from Android/Gradle development, here's a rough mapping to help orient yourself
in an ESP-IDF project (using [`led-blink/`](led-blink/) as the reference example):

| ESP-IDF (example project) | Android | Role |
|---|---|---|
| `led-blink/CMakeLists.txt` (top-level) | `settings.gradle` + top-level `build.gradle` | Declares the project and pulls in the build system |
| `led-blink/main/CMakeLists.txt` | `app/build.gradle` | Module-level: sources + deps for this component |
| `led-blink/main/idf_component.yml` | `dependencies { }` block in `build.gradle` | Declares external libraries (here: `led_strip`) |
| `led-blink/dependencies.lock` | `gradle.lockfile` / resolved version lock | Pins exact resolved dependency versions |
| `led-blink/managed_components/` | Gradle dependency cache (`~/.gradle/caches`) | Downloaded library sources, not hand-edited |
| `sdkconfig` + `sdkconfig.defaults*` | `gradle.properties` + build variants/flavors | Project-wide config flags, some per-target |
| `led-blink/main/Kconfig.projbuild` | Custom Gradle DSL / BuildConfig fields | Defines the configurable options (shows up in `idf.py menuconfig`, the way flavor options show up in Android Studio) |
| `led-blink/main/blink_example_main.c` → `app_main()` | `MainActivity.kt` → `onCreate()` | Entry point the framework calls into |
| `led-blink/build/` → `blink.bin` | `app/build/` → `app-debug.apk` | Compiled, flashable/installable artifact |
| `idf.py` | `./gradlew` | Wrapper script driving the whole build |
| `idf.py set-target esp32c6` | Product flavor / ABI split (`arm64-v8a`, etc.) | Picks which hardware variant you're building for |
| `idf.py -p PORT flash` | `./gradlew installDebug` / `adb install` | Pushes the built artifact onto the physical device |
| `idf.py monitor` | `adb logcat` | Streams the device's live log output |
| `~/.espressif/v6.1-beta1/esp-idf` (the SDK) | `~/Library/Android/sdk` | Shared toolchain/platform install, versioned independently of any one project |
| `.vscode/settings.json` (`idf.currentSetup`) | `local.properties` (`sdk.dir=...`) | Machine-local path to the SDK; not meant to be portable across machines (gitignored) |

**Where the analogy breaks down:** there's no OS, no VM, no activity lifecycle, no UI toolkit.
`app_main()` runs once on a FreeRTOS task and *is* the whole program — a `while (1) { ...
vTaskDelay(...) }` loop is closer to a raw background thread's run loop than anything in Android's
lifecycle. And "the LED" in `led-blink/` isn't a widget, it's a physical GPIO/WS2812 pin — there's
no view hierarchy underneath it.

This mapping is per-project — as more examples are added alongside `led-blink/`, each will have
its own `CMakeLists.txt`, `main/`, etc. in the same roles.
