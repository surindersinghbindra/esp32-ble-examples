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

For a mental model of how this project's structure maps to Android/Gradle concepts, see the
[repo-level README](../README.md#mental-model-esp-idf-vs-android-projects).

## License

This example is derived from Espressif's public-domain / CC0-licensed `blink` example.
