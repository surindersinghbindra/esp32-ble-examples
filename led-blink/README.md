| Supported Targets | ESP32-C6 |
| ----------------- | -------- |

# ESP32-C6 Blink Example (Color-Cycling LED)

This is an [ESP-IDF](https://github.com/espressif/esp-idf) project that blinks the onboard
addressable RGB LED (WS2812) on an **ESP32-C6-DevKitC**, cycling through a different color on
every blink: red → green → blue → yellow → cyan → magenta → white → repeat.

## Hardware Required

* An ESP32-C6-DevKitC (or similar ESP32-C6 dev board with an onboard addressable LED on GPIO8)
* A USB-C cable connected to the board's USB-UART port, for power, flashing, and serial monitoring

## Software Required

* [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c6/get-started/index.html) v6.1 (this project was built and flashed against `v6.1-beta1`)
* Python 3 (installed automatically by ESP-IDF's `install.sh`)

## Project Structure

```
led_blink/
├── CMakeLists.txt              # Top-level project file; pulls in the ESP-IDF build system
├── sdkconfig.defaults          # Shared default config: CONFIG_BLINK_GPIO=8
├── sdkconfig.defaults.esp32c6  # C6-specific default: CONFIG_BLINK_LED_STRIP=y (addressable LED)
├── dependencies.lock           # Locked component-manager dependency versions (commit this)
├── main/
│   ├── CMakeLists.txt          # Module build rules (sources + include dirs)
│   ├── idf_component.yml       # Declares the espressif/led_strip dependency
│   ├── Kconfig.projbuild       # Options exposed in `idf.py menuconfig` (LED type, GPIO, period)
│   └── blink_example_main.c    # app_main(): configures the LED and cycles its color on each blink
├── managed_components/         # Auto-downloaded deps (gitignored, regenerated from the lock file)
└── build/                      # Compiled output: bootloader.bin, partition-table.bin, blink.bin (gitignored)
```

## Build and Flash

```bash
# One-time: set up the ESP-IDF Python environment (skip if already done)
~/.espressif/v6.1-beta1/esp-idf/install.sh esp32c6

# Each shell session: activate the ESP-IDF environment
source ~/.espressif/v6.1-beta1/esp-idf/export.sh

# Select the chip target (only needed once, or when switching targets)
idf.py set-target esp32c6

# Build
idf.py build

# Flash to the board and watch logs (Ctrl-] to exit the monitor)
idf.py -p /dev/cu.usbserial-10 flash monitor
```

Replace `/dev/cu.usbserial-10` with your board's actual serial port (`ls /dev/cu.*` to find it).

## Configuration

Run `idf.py menuconfig` and open **Example Configuration** to change:

* **Blink LED type** — `GPIO` for a plain LED, `LED strip` for an addressable LED (WS2812)
* **Blink GPIO number** — which pin drives the LED (default: 8, matching the C6-DevKitC's onboard LED)
* **Blink period in ms** — how fast it blinks

To change the color sequence itself, edit the `s_colors[][3]` array near the top of
[`main/blink_example_main.c`](main/blink_example_main.c) — each entry is an `{R, G, B}` triple
from 0–255.

## Mental Model: ESP-IDF vs. Android Projects

If you're coming from Android/Gradle development, here's a rough mapping to help orient yourself
in an ESP-IDF project:

| ESP-IDF (this project) | Android | Role |
|---|---|---|
| `CMakeLists.txt` (top-level) | `settings.gradle` + top-level `build.gradle` | Declares the project and pulls in the build system |
| `main/CMakeLists.txt` | `app/build.gradle` | Module-level: sources + deps for this component |
| `main/idf_component.yml` | `dependencies { }` block in `build.gradle` | Declares external libraries (here: `led_strip`) |
| `dependencies.lock` | `gradle.lockfile` / resolved version lock | Pins exact resolved dependency versions |
| `managed_components/` | Gradle dependency cache (`~/.gradle/caches`) | Downloaded library sources, not hand-edited |
| `sdkconfig` + `sdkconfig.defaults*` | `gradle.properties` + build variants/flavors | Project-wide config flags, some per-target |
| `main/Kconfig.projbuild` | Custom Gradle DSL / BuildConfig fields | Defines the configurable options (shows up in `idf.py menuconfig`, the way flavor options show up in Android Studio) |
| `main/blink_example_main.c` → `app_main()` | `MainActivity.kt` → `onCreate()` | Entry point the framework calls into |
| `build/` → `blink.bin` | `app/build/` → `app-debug.apk` | Compiled, flashable/installable artifact |
| `idf.py` | `./gradlew` | Wrapper script driving the whole build |
| `idf.py set-target esp32c6` | Product flavor / ABI split (`arm64-v8a`, etc.) | Picks which hardware variant you're building for |
| `idf.py -p PORT flash` | `./gradlew installDebug` / `adb install` | Pushes the built artifact onto the physical device |
| `idf.py monitor` | `adb logcat` | Streams the device's live log output |
| `~/.espressif/v6.1-beta1/esp-idf` (the SDK) | `~/Library/Android/sdk` | Shared toolchain/platform install, versioned independently of any one project |
| `.vscode/settings.json` (`idf.currentSetup`) | `local.properties` (`sdk.dir=...`) | Machine-local path to the SDK; not meant to be portable across machines (gitignored) |

**Where the analogy breaks down:** there's no OS, no VM, no activity lifecycle, no UI toolkit.
`app_main()` runs once on a FreeRTOS task and *is* the whole program — the `while (1) { ...
vTaskDelay(...) }` loop in `blink_example_main.c` is closer to a raw background thread's run loop
than anything in Android's lifecycle. And "the LED" here isn't a widget, it's a physical
GPIO/WS2812 pin — there's no view hierarchy underneath it.

## License

This example is derived from Espressif's public-domain / CC0-licensed `blink` example.
