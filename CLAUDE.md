# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository structure

This repo holds three **independent** projects that happen to live side by side. There is no
shared build system between them - each has its own toolchain and is built/run separately.

- `led-blink/` - ESP-IDF example: cycles the ESP32-C6's onboard addressable LED through colors.
  Simple, self-contained, not touched much after initial creation.
- `ble-ota/` - ESP-IDF firmware for an ESP32-C6-DevKitC: BLE OTA updates plus two extra GATT
  services (LED control, simulated Heart Rate). This is the active development target.
- `android-ota-app/` - Jetpack Compose Android app that drives `ble-ota` over Bluetooth. Also
  actively developed; must be kept in lockstep with `ble-ota`'s GATT protocol.

Root-level `README.md` is the index; each subproject has its own `README.md` with far more detail
than this file - **read the relevant subproject README before making non-trivial changes**,
especially `ble-ota/README.md`'s protocol tables and `android-ota-app/README.md`'s architecture
notes.

## Commands

### ble-ota (and led-blink) - ESP-IDF firmware

```bash
source ~/.espressif/v6.1-beta1/esp-idf/export.sh   # once per shell; this repo targets ESP-IDF v6.1-beta1
cd ble-ota                                          # or led-blink/
idf.py set-target esp32c6                           # only needed once, or after switching targets
idf.py build
idf.py -p /dev/cu.usbserial-10 flash monitor        # replace port; Ctrl-] exits monitor
```

`idf.py menuconfig` opens the config UI (Kconfig) for `sdkconfig`-level settings. Note: `ble-ota`'s
LED behavior is **not** a Kconfig option - it's live BLE+NVS configuration (see Architecture below).

A held-open `idf.py monitor` (or the VS Code ESP-IDF extension's monitor) will block `idf.py flash`
with a "port busy" error - `lsof /dev/cu.usbserial-10` and kill the process if that happens.

### ble-ota - pushing an OTA update over BLE from a Mac (no phone needed)

```bash
cd ble-ota
python3 -m venv .venv && source .venv/bin/activate && pip install bleak pyserial   # once
python tools/ota_client.py build/ble_ota.bin                 # scans, pushes, reboots on success
```

Full command reference (including watching logs during a push) is in
`ble-ota/README.md#python-ota-testing-from-a-mac`.

### android-ota-app

```bash
cd android-ota-app
gradle :app:assembleDebug            # or ./gradlew if present; project uses Gradle 9.7.1 / AGP 9.3.2
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Bundled demo firmware lives at `app/src/main/assets/firmware/ble_ota.bin` and is a **plain copy**,
not a build dependency - after changing `ble-ota` firmware, resync manually:

```bash
cp ../ble-ota/build/ble_ota.bin app/src/main/assets/firmware/ble_ota.bin
```

MIUI/Xiaomi phones can silently block `adb install` (`INSTALL_FAILED_USER_RESTRICTED`) unless
Settings → Additional settings → Developer options → **Install via USB** is enabled.

No test suite exists yet in either the firmware or the app.

## Architecture

### ble-ota: modular GATT services as ESP-IDF components

`main/main.c` only brings up the NimBLE stack, calls `ble_svc_gap_init()` / `ble_svc_gatt_init()`
exactly once (must not be duplicated per-service), then calls each service component's own
`..._init()`, and fans out GAP connect/disconnect/subscribe callbacks to whichever services
registered interest. It also owns the boot-time OTA rollback confirmation (`self_check_ok()`),
since that's about the currently-running app, not any one service. Connection-level (not
service-specific) BLE config lives here too: the Security Manager setup (`ble_hs_cfg.sm_*`,
`ble_store_config_init()` for persisted bonds - see `ble-ota/README.md`'s security section) and an
example `ble_gap_update_params()` connection-parameter request sent right after connect.

Each `components/*_service/` is independent and exposes its own GATT service:

- `ota_service` - custom service `...1ba0`: Control (commands/status notify), Data (firmware
  bytes), Version (read-only version string). Dual-partition OTA (`ota_0`/`ota_1`, no factory
  partition) with `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE` for automatic rollback if a new image
  never confirms itself healthy after boot. Reboot is a separate explicit command from END, so a
  client can see a confirmed-success state before committing to restart. Also runs an **educational**
  L2CAP CoC (Connection-Oriented Channel) server on a fixed PSM (`OTA_L2CAP_PSM = 0x00F0`),
  registered once at boot in `ota_l2cap_init()`, as an optional high-throughput alternative to the
  per-chunk GATT Data writes - see `ble-ota/README.md`'s "Fast-update path" section. Verified
  working on the firmware side; the Android client (`streamFirmwareViaL2cap` in
  `BleOtaRepositoryImpl.kt`) is implemented per the public API but failed to connect on the one test
  phone tried (Redmi/Xiaomi, Android 12) - documented as a likely OEM Bluetooth HAL limitation, not
  a code bug. GATT remains the default transport.
- `led_service` - custom service `...1bb0`: one Config characteristic (7-byte: mode, R, G, B,
  brightness, blink_interval_ms). Writes apply to the LED immediately **and** persist to NVS
  (survives reboots/power loss) - this replaced an earlier Kconfig-based approach specifically so
  LED behavior could change without a rebuild/reflash. This is also the project's one **security
  example**: read/write require an encrypted link (`BLE_GATT_CHR_F_READ_ENC`/`WRITE_ENC`), and
  successful writes go out as an **Indication** (not Notify) - deliberately contrasted with Heart
  Rate's Notify. OTA and Heart Rate stay unauthenticated/unencrypted on purpose - see
  `ble-ota/README.md`'s "Security: pairing and bonding" section - **verified on hardware**:
  pairing works, but a canceled/ignored prompt times out at the protocol level (~30s) and Android
  then refuses to re-prompt until the device is "Forgotten" in the phone's Bluetooth settings.
- `heart_rate_service` - **standard** Bluetooth SIG Heart Rate service (0x180D/0x2A37/0x2A38, not
  custom), plus one custom Rate Control characteristic to switch the simulated BPM's notify rate
  between ~1/s (realistic) and ~20/s (deliberately fast, for exercising client backpressure).

All custom UUIDs share the base `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1bXX`, varying only the last byte
- see the protocol tables in `ble-ota/README.md` for the exact UUID/byte-format for every
characteristic. **The Android app's `BleConstants.kt` UUIDs and wire-format parsing
(`LedConfig`, `FirmwareVersionReader`) must stay byte-for-byte in sync with the firmware side** -
there is no schema shared between them, just matching hand-written constants on both ends.

### Known hardware-specific gotchas (already fixed, but relevant if touched again)

- The LED (`led_strip`/RMT-based) must be initialized from its own FreeRTOS task, not inline as the
  first statement of `app_main()` - doing it that early was unreliable on this board (silently kept
  showing a stale color instead of the one just requested).
- The OTA Data characteristic's write buffer is a fixed 512 bytes
  (`OTA_DATA_MAX_CHUNK` in `ota_service.c`). Clients must cap their chunk size at
  `min(negotiated_MTU - 3, 512)` - sending exactly `MTU - 3` bytes can exceed this and gets
  correctly rejected with `GATT_INVALID_ATTRIBUTE_LENGTH`. Found via real on-device testing, not
  code review.

### android-ota-app: Clean Architecture / MVVM

```
domain/       pure Kotlin - models, repository interfaces, use cases. No android.bluetooth.* here.
data/ble/     BleOtaRepositoryImpl - the only place touching android.bluetooth.*. One GATT
              operation in flight at a time, enforced via a Mutex (Android BLE requirement).
di/           Hilt: RepositoryModule (@Binds impls to their domain interfaces). Every
              use case and OtaViewModel itself use @Inject constructor; BleOtaRepositoryImpl
              is @Singleton (owns the one live BluetoothGatt connection for the process).
              MainActivity is @AndroidEntryPoint, BleOtaApplication is @HiltAndroidApp.
presentation/ MVVM: OtaViewModel (@HiltViewModel, StateFlow-based) + OtaScreen (Compose, one
              state per UI step).
```

Toolchain note: Hilt 2.60.1 + KSP 2.3.11 build cleanly against this project's Kotlin 2.4.10 (via
AGP 9.3.2's built-in Kotlin support, not a separate `kotlin-android` plugin) - confirmed via an
actual `gradle :app:assembleDebug`, not just version-number inspection, since no KSP release
exactly matching 2.4.10 was published at the time this was pinned.

The whole app is **more heavily commented than typical production Kotlin, on purpose** - comments
throughout explain Kotlin/Compose/coroutines language features and idioms as they appear, since
this project doubles as material for learning Kotlin. Keep that density up in new code touched
here rather than reverting to terse/uncommented style, unless told otherwise.

`OtaUiState` is a sealed interface with one variant per screen/step (Idle, Scanning, Connected,
Updating, etc.) - the Composable is a `when` over this, not a single mutable form.

Heart rate notifications implement real backpressure in two layers, since the board can notify
faster than the UI should redraw: `BleOtaRepositoryImpl` buffers incoming values in a
`MutableSharedFlow` with a bounded extra buffer and `DROP_OLDEST` overflow (drops old samples
instead of growing unbounded or blocking the BLE callback thread); `OtaViewModel` separately
samples the latest value onto the graph's history list on its own fixed ~5fps timer, decoupling
redraw rate from BLE notification rate entirely.

Firmware version checking works by reading the same `esp_app_desc_t` byte offset from both sides
independently: `ota_service`'s Version characteristic on the device, and a fixed-offset read
(`24 + 8 + 16` into the `.bin`) in both `ota_client.py`'s `read_bin_version()` and the app's
`FirmwareVersionReader`. If a firmware image's header layout ever changes, both parsers need
updating together.
