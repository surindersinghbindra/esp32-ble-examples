# ESP32 BLE OTA - Android Companion App

A Jetpack Compose app for the [`ble-ota`](../ble-ota/) ESP32-C6 example's three GATT services:
push firmware over Bluetooth with a live progress bar and reboot button (OTA), live color/
brightness/blink control of the onboard LED (LED service), and a subscribe-and-graph view of a
simulated heart rate with a switch to stress-test client-side backpressure handling (Heart Rate
service).

Built with **MVVM + Clean Architecture**, explicit use cases, and **Hilt** for dependency injection
- see [Architecture](#architecture) below. Verified end-to-end on real hardware (see
[Verified on hardware](#verified-on-hardware)).

**Comments as learning material:** this codebase is intentionally more heavily commented than
typical production Kotlin - the comments call out Kotlin language features and idioms as they come
up (property delegates, sealed interfaces, `Result`, coroutines/Flow, reified generics, Compose's
`remember`/state hoisting, and so on), for someone learning Kotlin/Android alongside reading the
code. If you're reading this project for its architecture rather than as a tutorial, feel free to
skim past them.

## Screens / flow

Each step is its own UI state, driven by a single `OtaViewModel`:

1. **Idle** - "Scan for esp32-ble-ota" button
2. **Scanning** - spinner
3. **Device found** - shows name/address, "Connect" button
4. **Connecting** - spinner
5. **Connected** - shows negotiated MTU and the device's currently-running firmware version (read
   from the Version characteristic); pick firmware via **"Use bundled firmware"** (ships in
   `app/src/main/assets/firmware/ble_ota.bin`) or **"Pick file..."** (system file picker, any
   `.bin`) - once loaded, its embedded version is shown too, with a warning (not a hard block) if
   it matches what's already running; "Start Update" enables once firmware is loaded
6. **Updating** - live progress bar + `bytesSent / totalBytes` + percentage, then a "Validating..."
   state while the device checks the image
7. **Succeeded** - "Reboot Device" button (or "Disconnect without rebooting")
8. **Rebooting** - spinner, then back to Idle once the device restarts and drops the connection
9. **Failed** - reason shown, "Retry" (device stays connected, so this just re-sends without
   rescanning) or "Disconnect"

Two more sections appear underneath the OTA controls the whole time you're **Connected** (they're
independent of whatever OTA state you're in):

- **LED Control** - color presets (Red/Green/Blue/Yellow), a Off/Solid/Blink mode switch, a
  brightness slider, and (in Blink mode) a blink-interval slider. Every change writes the full
  7-byte config to the device immediately - see [GATT protocol: LED](#gatt-protocol-led-service).
- **Heart Rate** - a Subscribe/Unsubscribe toggle, current BPM, a live scrolling line graph, and a
  "Fast mode" switch that asks the board to notify at ~20/s instead of ~1/s specifically so you can
  watch the backpressure handling do something - see
  [GATT protocol: Heart Rate](#gatt-protocol-heart-rate-service--backpressure).

## Architecture

```
domain/                          -- pure Kotlin, no Android imports
├── model/        BleDeviceInfo, OtaTransferEvent, BleOtaException, FirmwareVersionReader,
│                 LedConfig, LedMode, HeartRateSample
├── repository/    BleOtaRepository, FirmwareSource   (interfaces only)
└── usecase/       ScanForEspDeviceUseCase, ConnectToDeviceUseCase, ReadDeviceVersionUseCase,
                    PerformOtaUpdateUseCase, RebootDeviceUseCase,
                    LoadFirmwareFromAssetsUseCase, LoadFirmwareFromUriUseCase,
                    ExtractFirmwareVersionUseCase, ReadLedConfigUseCase, WriteLedConfigUseCase,
                    ObserveHeartRateUseCase, SetHeartRateFastModeUseCase, ...

data/ble/                        -- Android BLE implementation of the domain interfaces
├── BleConstants.kt               GATT UUIDs/commands for all 3 services - must match the
│                                 corresponding ble-ota/components/*/*.c files
├── BleOtaRepositoryImpl.kt        BluetoothGatt plumbing, one GATT op at a time via a Mutex;
│                                 also owns the heart-rate backpressure buffer (see below)
└── FirmwareSourceImpl.kt          Reads bytes from assets/ or a content:// Uri

di/                               -- Hilt module
└── RepositoryModule.kt            @Binds BleOtaRepositoryImpl -> BleOtaRepository,
                                  FirmwareSourceImpl -> FirmwareSource

presentation/ota/                 -- MVVM
├── OtaUiState.kt                  One sealed interface = one state per screen above
├── OtaViewModel.kt                 @HiltViewModel; talks only to use cases, never to
│                                 android.bluetooth.*; also holds LED/heart-rate StateFlows
│                                 alongside uiState
└── OtaScreen.kt                   Compose UI, including the LED control section and a
                                  custom Canvas-based heart-rate graph

MainActivity.kt                   -- @AndroidEntryPoint; permission gating, file picker,
                                  wires everything together
BleOtaApplication.kt              -- @HiltAndroidApp entry point
```

**Dependency injection: Hilt.** Every dependency is already expressed as an interface
(`BleOtaRepository`, `FirmwareSource`), so the DI framework only needs one small module -
`RepositoryModule` - to bind each interface to its implementation. `BleOtaRepositoryImpl` is
`@Singleton` (one shared instance for the process lifetime, since it owns the live `BluetoothGatt`
connection); every `domain/usecase/*` class and `OtaViewModel` itself just declare
`@Inject constructor(...)` and Hilt wires the rest together automatically. The domain and
presentation layers don't import anything Hilt-specific beyond that one annotation each - the
interface boundaries mean the DI framework could be swapped again with no other code changes.

## GATT protocol: OTA service

Implements the client side of the protocol documented in
[`../ble-ota/README.md`](../ble-ota/README.md#gatt-protocol-ota-service-componentsota_service) -
custom service `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba0`, control characteristic for
START/END/ABORT/REBOOT commands and status notifications, data characteristic for firmware bytes,
and a read-only version characteristic.

One thing worth calling out: **the data chunk size is capped at 512 bytes** regardless of the
negotiated ATT MTU (`BleOtaRepositoryImpl.kt`, `performOtaUpdate`), because the firmware's data
characteristic buffer (`OTA_DATA_MAX_CHUNK` in `gatt_svr.c`) is a fixed 512-byte array. This was
found by testing on real hardware, not by inspection - see below.

**Firmware version check:** on connect, `ReadDeviceVersionUseCase` reads the device's Version
characteristic; once a firmware image is loaded, `ExtractFirmwareVersionUseCase` /
`FirmwareVersionReader` parses the same version string directly out of the image's
`esp_app_desc_t` header (offset `24 + 8 + 16` into the `.bin` - see the constants in
`FirmwareVersionReader`, which must stay in sync with `ble-ota/tools/ota_client.py`'s
`read_bin_version()`). If both match, the Connected screen shows a warning but **Start Update stays
enabled** - this is a warning, not a hard gate, matching the Python client's behavior.

## GATT protocol: LED service

Reads and writes the 7-byte config blob described in
[`../ble-ota/README.md`](../ble-ota/README.md#gatt-protocol-led-service-componentsled_service) -
`LedConfig.toWireBytes()` / `LedConfig.fromWireBytes()` handle the packing. On connect,
`ReadLedConfigUseCase` reads the current config once so the UI starts in sync with whatever the
board actually has (which may not be factory defaults, since it's persisted in NVS across
reboots); every button/slider change after that calls `WriteLedConfigUseCase` with a full updated
config (the whole 7 bytes are always sent together, never a partial update).

The brightness and blink-interval sliders use Compose's `onValueChangeFinished` rather than
`onValueChange` to trigger the actual BLE write - the slider drags locally at full frame rate for
a responsive feel, but only one write goes out when you let go, instead of one write per pixel of
drag.

## GATT protocol: Heart Rate service + backpressure

Subscribes to the standard Heart Rate Measurement characteristic (`0x2A37`) and writes the custom
Rate Control characteristic to flip between the board's ~1/s and ~20/s simulator modes - see
[`../ble-ota/README.md`](../ble-ota/README.md#gatt-protocol-heart-rate-service-componentsheart_rate_service)
for the device side.

This is the one place in the app that has to handle data arriving *faster than it's consumed*,
and it's handled in two layers:

1. **`BleOtaRepositoryImpl`** feeds every incoming notification into a `MutableSharedFlow` with a
   bounded extra buffer (64) and `BufferOverflow.DROP_OLDEST`. If nothing is collecting fast enough,
   old samples are silently dropped rather than piling up in memory or blocking the BLE callback
   thread - this is the actual backpressure mechanism.
2. **`OtaViewModel`** doesn't update the graph on every single sample either, even though the flow
   above hands them out as fast as they arrive. It keeps a `@Volatile`-equivalent "latest BPM"
   value updated cheaply on every notification, and a *separate* coroutine ticks every 200ms to
   sample that value into the graph's history list. So the graph redraws at a fixed ~5fps
   regardless of whether the board is notifying at 1/s or 20/s - the UI's redraw rate is fully
   decoupled from the arrival rate.

Together, these mean flipping "Fast mode" on doesn't cause growing memory use, dropped frames, or a
frozen UI - which is exactly what was confirmed on real hardware (see below).

## Fast update mode: L2CAP CoC (educational/experimental)

A "Fast update mode (L2CAP CoC)" switch on the Connected screen, `OtaTransport` (`GATT` or
`L2CAP_COC`) chosen before tapping Start Update - see
[`../ble-ota/README.md`](../ble-ota/README.md#fast-update-path-l2cap-coc-educational) for the
full technical explanation of what an L2CAP Connection-Oriented Channel is and why it can be
faster than GATT.

`BleOtaRepositoryImpl.streamFirmwareViaL2cap` opens a raw socket to the firmware's fixed PSM
(`OtaL2capProtocol.PSM = 0x00F0`) via `BluetoothDevice.createL2capChannel()` (Android 10+ only)
and writes the whole image through `BluetoothSocket.getOutputStream()` - no GATT characteristic
involved for the bulk bytes at all. `OutputStream.write()` blocks until the channel's own credit-
based flow control allows more data out, so - unlike the GATT path, and unlike the heart-rate
notification path - this loop needs no manual pacing or backpressure logic of its own.

**Honestly reported result, not glossed over:** this was implemented and tested on real hardware,
and the firmware side works (confirmed via its boot log). The Android client's
`socket.connect()` consistently failed on the test phone (Redmi/Xiaomi, Android 12) with no
corresponding event ever appearing in the firmware's log - meaning the connection attempt never
reached the board at all. This points to that phone's Bluetooth HAL not reliably supporting the
L2CAP CoC *client* role, a known inconsistency across Android OEM Bluetooth stacks for this API.
The code is correct as far as the public Android API goes - there's no alternative API for this
role - and may well work on other devices/chipsets; on this one, `GATT` remains the transport that
actually works, which is why it's the default.

## Verified on hardware

Built and run against a real ESP32-C6-DevKitC (flashed with the `ble-ota` firmware) from a
Redmi/Xiaomi Android 12 phone connected via `adb`:

- First real run failed with **status 13 (`GATT_INVALID_ATTRIBUTE_LENGTH`)**: the app computed a
  514-byte chunk size (`517`-byte negotiated MTU `- 3`), one byte over what the Python reference
  client happened to send, and 2 bytes over the firmware's fixed 512-byte buffer. The firmware
  correctly rejected the oversized write instead of accepting a partial/garbled chunk - exactly
  the kind of defensive check the protocol is supposed to have. Fixed by capping the app's chunk
  size at 512.
- After the fix: full transfer of a 641,200-byte image completed in about a minute, the device
  validated the image and switched its boot partition, the app showed the Succeeded screen,
  **Reboot Device** was tapped, and the device rebooted into `ota_0` (having previously been on
  `ota_1`), ran its self-check, and confirmed the new image (rollback cancelled) - full round trip
  end to end.
- The version-check feature was also verified live: after connecting, the Connected screen
  correctly showed the device's running version (read over BLE); loading the bundled firmware
  (same version, since the asset had just been re-synced) correctly triggered the "device already
  reports this exact version" warning, with Start Update remaining enabled per the warn-but-allow
  design.
- **LED control**, live: tapping the Blue preset changed the physical LED color immediately
  (confirmed visually); dragging the brightness slider up correctly increased the LED's intensity
  on release.
- **Heart Rate + backpressure**, live: tapping Subscribe showed a live BPM number and a smoothly
  scrolling graph updating roughly once per second. Flipping "Fast mode" on switched the board to
  ~20 notifications/sec - the graph kept redrawing smoothly at its own fixed rate with no visible
  lag, freeze, or crash, confirming the bounded-buffer + fixed-rate-sampling backpressure design
  above actually holds up under load rather than just in theory.

## Requirements

* Android Studio or the `gradle` CLI, JDK 17
* An Android device or emulator with Bluetooth LE (API 26+); physical device recommended, since
  BLE on emulators is unreliable/unsupported on most setups
* The `ble-ota` firmware flashed onto an ESP32-C6 and advertising as `esp32-ble-ota`

## Build & install

```bash
cd android-ota-app
./gradlew :app:assembleDebug        # or: gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, grant the Bluetooth permission when prompted (this is `BLUETOOTH_SCAN` +
`BLUETOOTH_CONNECT` on Android 12+, or `ACCESS_FINE_LOCATION` on older versions - both declared in
`AndroidManifest.xml`).

> **Note:** some OEM Android builds (MIUI/Xiaomi in particular) silently block `adb install` with
> `INSTALL_FAILED_USER_RESTRICTED` unless **Settings → Additional settings → Developer options →
> Install via USB** is enabled first.

## Keeping the bundled firmware in sync

`app/src/main/assets/firmware/ble_ota.bin` is a copy, not a build dependency - after rebuilding
`ble-ota`, refresh it manually:

```bash
cp ../ble-ota/build/ble_ota.bin app/src/main/assets/firmware/ble_ota.bin
```

## Known limitations

- No BLE bonding/encryption and no image signing - same caveat as the firmware itself (see
  [`../ble-ota/README.md`](../ble-ota/README.md#what-this-example-does-not-do-read-before-shipping-anything)).
- No cancel/abort button in the UI, even though the firmware supports `ABORT` (0x03) - an easy
  addition if you need it, just not wired up here.
- No automated tests yet. The use case boundaries are drawn so the ViewModel is testable against a
  fake `BleOtaRepository` without any Android dependencies, but no tests are checked in.
- Heart rate data is simulated on the board (a random walk, not a real sensor) - the graph/UI side
  is written the same way it would be for a real strap, but there's no actual physiological signal
  behind it.
