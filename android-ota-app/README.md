# ESP32 BLE OTA - Android Companion App

A Jetpack Compose app that pushes firmware to the [`ble-ota`](../ble-ota/) ESP32-C6 example over
Bluetooth Low Energy: scan, connect, pick a firmware image, watch a live progress bar, and reboot
the board once it confirms the update succeeded.

Built with **MVVM + Clean Architecture** and explicit use cases - see [Architecture](#architecture)
below. Verified end-to-end on real hardware (see [Verified on hardware](#verified-on-hardware)).

## Screens / flow

Each step is its own UI state, driven by a single `OtaViewModel`:

1. **Idle** - "Scan for esp32-ble-ota" button
2. **Scanning** - spinner
3. **Device found** - shows name/address, "Connect" button
4. **Connecting** - spinner
5. **Connected** - shows negotiated MTU; pick firmware via **"Use bundled firmware"** (ships in
   `app/src/main/assets/firmware/ble_ota.bin`) or **"Pick file..."** (system file picker, any
   `.bin`); "Start Update" enables once firmware is loaded
6. **Updating** - live progress bar + `bytesSent / totalBytes` + percentage, then a "Validating..."
   state while the device checks the image
7. **Succeeded** - "Reboot Device" button (or "Disconnect without rebooting")
8. **Rebooting** - spinner, then back to Idle once the device restarts and drops the connection
9. **Failed** - reason shown, "Retry" (device stays connected, so this just re-sends without
   rescanning) or "Disconnect"

## Architecture

```
domain/                          -- pure Kotlin, no Android imports
├── model/        BleDeviceInfo, OtaTransferEvent, BleOtaException
├── repository/    BleOtaRepository, FirmwareSource   (interfaces only)
└── usecase/       ScanForEspDeviceUseCase, ConnectToDeviceUseCase,
                    PerformOtaUpdateUseCase, RebootDeviceUseCase,
                    LoadFirmwareFromAssetsUseCase, LoadFirmwareFromUriUseCase, ...

data/ble/                        -- Android BLE implementation of the domain interfaces
├── BleConstants.kt               GATT UUIDs/commands - must match ble-ota/main/gatt_svr.c
├── BleOtaRepositoryImpl.kt        BluetoothGatt plumbing, one GATT op at a time via a Mutex
└── FirmwareSourceImpl.kt          Reads bytes from assets/ or a content:// Uri

di/                               -- hand-rolled DI (no Hilt, see note below)
├── AppContainer.kt                Builds the repository + use case graph
└── ViewModelFactory.kt

presentation/ota/                 -- MVVM
├── OtaUiState.kt                  One sealed interface = one state per screen above
├── OtaViewModel.kt                 Talks only to use cases, never to android.bluetooth.*
└── OtaScreen.kt                   Compose UI, purely a function of OtaUiState

MainActivity.kt                   -- permission gating, file picker, wires everything together
```

**Why no Hilt/Koin:** `AppContainer` is a small manual container instead. Every dependency is
already expressed as an interface (`BleOtaRepository`, `FirmwareSource`), so swapping in a DI
framework later is a change contained entirely to `di/` - the domain and presentation layers
wouldn't need to change at all. This was a deliberate simplification to keep the build surface
small; add Hilt if the project grows.

## GATT protocol

Implements the client side of the protocol documented in
[`../ble-ota/README.md`](../ble-ota/README.md#gatt-protocol) - custom service
`f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba0`, control characteristic for START/END/ABORT/REBOOT commands
and status notifications, data characteristic for firmware bytes.

One thing worth calling out: **the data chunk size is capped at 512 bytes** regardless of the
negotiated ATT MTU (`BleOtaRepositoryImpl.kt`, `performOtaUpdate`), because the firmware's data
characteristic buffer (`OTA_DATA_MAX_CHUNK` in `gatt_svr.c`) is a fixed 512-byte array. This was
found by testing on real hardware, not by inspection - see below.

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
