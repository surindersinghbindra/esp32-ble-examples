| Supported Targets | ESP32-C6 |
| ----------------- | -------- |

# BLE OTA Example (Dual-Partition, Rollback-Safe)

Pushes a new firmware image to the board over Bluetooth Low Energy and installs it using
ESP-IDF's standard **ota_0 / ota_1** dual-partition scheme - no factory partition. Whichever
slot the board is *not* currently running from is always the update target, so a bad or
interrupted transfer can never overwrite the only bootable image.

This has been tested end-to-end on real hardware: a BLE central (a Python script using
[`bleak`](https://github.com/hbldh/bleak), or the companion Android app) streams a `.bin` into the
inactive slot, the board validates it and switches its boot partition, the client explicitly
triggers a reboot, and the board confirms the new image is healthy before making the switch
permanent.

The firmware also drives the onboard addressable LED solid red - purely as a visible "yes, the new
image is really running" marker, unrelated to the OTA logic itself. It's set from its own FreeRTOS
task (`led_init_task` in `main.c`) rather than inline as the first line of `app_main()`: calling
the RMT-based LED driver that early was unreliable in testing (the strip silently kept showing a
stale color from a previous flash instead of the one just requested); deferring it to a task that
runs once the rest of system init gets going fixed it.

## How it works

```
BLE Central (phone / PC)                    ESP32-C6
      |  write CONTROL = START ------------>  esp_ota_begin() on the inactive
      |                                       partition (ota_0 or ota_1)
      |  write DATA (firmware bytes) ------>  esp_ota_write(), repeated
      |  ...                                  for every chunk
      |  write CONTROL = END --------------->  esp_ota_end() validates the image
      |                                       (magic byte + SHA-256), then
      |                                       esp_ota_set_boot_partition()
      |                                       (only on success) - no reboot yet
      |  <----------- notify SUCCESS/ERROR
      |
      |  write CONTROL = REBOOT ------------>  esp_restart() (separate, explicit
      |                                       step, so a client can confirm
      |                                       SUCCESS before committing to it)
      |                                             |
      |                                             v
      |                                       new image boots in
      |                                       "pending verify" state
      |                                             |
      |                                       app_main() runs a self-check;
      |                                       on success, calls
      |                                       esp_ota_mark_app_valid_
      |                                       cancel_rollback()
```

## GATT protocol

Custom service `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba0` with three characteristics:

| Characteristic | UUID | Properties | Purpose |
|---|---|---|---|
| Control | `...ba1` | read, write, notify | Send commands, receive status |
| Data | `...ba2` | write, write-without-response | Raw firmware bytes, in order |
| Version | `...ba3` | read | Running firmware's version string (UTF-8, no null terminator) |

**Control commands** (write a single byte):

| Byte | Command |
|---|---|
| `0x01` | START - begin an OTA update into the inactive slot |
| `0x02` | END - finalize and validate the image (does **not** reboot) |
| `0x03` | ABORT - cancel an in-progress update |
| `0x04` | REBOOT - restart now, into whichever partition is set to boot |

**Control notifications** (subscribe to receive status):

| Byte | Status |
|---|---|
| `0x00` | IDLE |
| `0x01` | IN_PROGRESS |
| `0x02` | SUCCESS |
| `0x03` | ERROR |

## Precautions this example takes (and why)

1. **No factory partition - only ota_0 / ota_1.** `CONFIG_PARTITION_TABLE_TWO_OTA_LARGE=y`
   (see `sdkconfig.defaults`). `esp_ota_get_next_update_partition()` always targets the slot that
   is *not* currently running, so the running firmware is never the one being overwritten.

2. **Image validation before switching boot partitions.** `esp_ota_end()` checks the image magic
   byte and SHA-256 before it's accepted. If the transfer was corrupted, truncated, or interrupted
   (a disconnect, a dropped packet), validation fails, `esp_ota_set_boot_partition()` is never
   called, and the board just keeps running its current firmware. **Verified on hardware:** an
   early test run with an unreliable write-without-response client left the image incomplete, and
   the board correctly rejected it (`invalid segment length`, `New image failed verification`) and
   stayed on the old firmware instead of trying to boot a corrupt image.

3. **Automatic rollback if the new image never confirms itself.**
   `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` makes the bootloader boot a freshly-installed image in
   an `ESP_OTA_IMG_PENDING_VERIFY` state. `main.c` checks for that state at startup, runs a basic
   health check (`self_check_ok()`), and only calls `esp_ota_mark_app_valid_cancel_rollback()` if
   it passes. **If the board never reaches that call again** - because the new firmware crashes,
   hangs, or the watchdog fires before then - the *next* reset makes the bootloader automatically
   revert to the previous, known-good slot instead of retrying the broken one. This is what
   actually prevents a bad OTA update from bricking the board, and it's the main reason to prefer
   this over a factory-only or single-partition layout.

4. **Disconnects abort cleanly.** If the BLE link drops mid-transfer, `gatt_svr_ota_on_disconnect()`
   calls `esp_ota_abort()` so the OTA handle isn't left open and the target partition isn't left in
   a half-written state for a future update to trip over.

5. **`self_check_ok()` is intentionally minimal** - it just confirms the app descriptor is
   readable and free heap looks sane. Treat it as a placeholder: extend it with whatever actually
   proves *your* firmware is healthy (a successful sensor read, a successful network connection,
   a real self-test) before you rely on this in a real product.

## What this example does *not* do (read before shipping anything)

- **No authentication or encryption on the OTA transfer.** Any BLE central in range can connect
  and push firmware. For anything beyond a bench test, add BLE bonding/encryption (see the
  `bleprph` example under `nimble/` in ESP-IDF for `sm_bonding`/`sm_mitm`/`sm_sc` and
  `BLE_GATT_CHR_F_WRITE_ENC`) and/or verify a cryptographic signature on the image before calling
  `esp_ota_set_boot_partition()`.
- **No rollback-protection against re-installing an older version.** The Version characteristic
  and the version check in the clients (see below) only *warn* on a match - they don't inspect
  version numbers to block a downgrade, and there is no anti-rollback eFuse configured
  (`CONFIG_BOOTLOADER_APP_ANTI_ROLLBACK`). It will happily install an older or identical firmware
  if you ask it to.
- **Throughput is slow (a few KB/s).** The reference client uses write-with-response for every
  chunk, which round-trips over BLE per write - reliable, but not fast. A ~640KB image takes a
  few minutes. A faster client could use write-without-response with real credit-based flow
  control, but that's more complex than this example needs.

## LED configuration

`idf.py menuconfig` -> **BLE OTA Example Configuration** -> **Status LED mode** (backed by
`main/Kconfig.projbuild`):

| Mode | Behavior |
|---|---|
| Only Red (default) | Steady red, always on |
| Only Green | Steady green, always on |
| Rotate | Cycles Red -> Green -> Blue -> Off, repeating, `n` seconds per phase |

**Seconds between LED rotation phases** (`CONFIG_LED_ROTATE_DELAY_SECONDS`, default 4, range 1-60)
only applies in Rotate mode - the solid modes just stay lit. This is purely cosmetic (which build
is visibly running); it has nothing to do with the OTA transfer itself.

To build a one-off variant without changing the checked-in default, edit the generated `sdkconfig`
directly (this is exactly what `menuconfig` does under the hood) and rebuild:

```bash
idf.py menuconfig   # interactively, under BLE OTA Example Configuration
# or, non-interactively:
sed -i '' 's/^CONFIG_LED_MODE_ONLY_RED=y/# CONFIG_LED_MODE_ONLY_RED is not set/' sdkconfig
sed -i '' 's/^# CONFIG_LED_MODE_ROTATE is not set/CONFIG_LED_MODE_ROTATE=y/' sdkconfig
idf.py build
```

## Firmware version check

Before pushing an update, both `tools/ota_client.py` and the Android app read the connected
device's **Version** characteristic and compare it against the version embedded in the `.bin` file
they're about to send (parsed directly from the image's `esp_app_desc_t` header - see
`read_bin_version()` in the Python script, or `FirmwareVersionReader` in the Android app). If they
match, both **warn but still allow the update to proceed** - useful when you're deliberately
re-flashing the same version to test the OTA path itself, rather than a hard gate that would get in
your way. If you want a real gate (e.g. reject exact re-installs, or reject downgrades by parsed
semantic version), that's a straightforward extension of the same check.

The version string itself is whatever `idf.py` embedded from `git describe` at build time (you've
seen it in the logs throughout this README, e.g. `1ecc512-dirty`) - there's no separate manual
version number to keep in sync.

## Hardware Required

* An ESP32-C6 dev board with **4MB or larger flash** (the ota_0/ota_1 partitions are 1700K each).
  This was tested against an ESP32-C6-DevKitC with 8MB flash.
* A USB-C cable for flashing/monitoring the initial firmware
* A BLE central to perform the update: a PC/Mac running the included Python script, or any BLE
  app (e.g. nRF Connect) if you're driving the GATT writes manually

## Software Required

* ESP-IDF v6.1 (tested against `v6.1-beta1`)
* Python 3 with [`bleak`](https://pypi.org/project/bleak/) installed, to run `tools/ota_client.py`
  (see [Python OTA testing from a Mac](#python-ota-testing-from-a-mac) below for the exact
  commands and what needs installing)

## Build and initial flash (over USB, same as any other example)

```bash
source ~/.espressif/v6.1-beta1/esp-idf/export.sh
idf.py set-target esp32c6
idf.py build
idf.py -p /dev/cu.usbserial-10 flash monitor
```

The very first flash goes to `ota_0` (there's no factory partition). Watch the log - you should
see the BLE stack come up and advertise as `esp32-ble-ota`.

## Python OTA testing from a Mac

This is every command actually used to build, push, and observe an OTA update from a Mac over
Bluetooth - no phone required.

### One-time setup

You need Python 3 (already on macOS, or `brew install python3`) and a virtual environment with
[`bleak`](https://pypi.org/project/bleak/) (the cross-platform BLE library the script uses) and,
optionally, [`pyserial`](https://pypi.org/project/pyserial/) if you also want to watch the boot log
over USB at the same time as the BLE transfer:

```bash
cd ble-ota
python3 -m venv .venv
source .venv/bin/activate
pip install bleak pyserial
```

`bleak` is the only hard requirement for `tools/ota_client.py` itself; `pyserial` is only needed if
you write your own little log-watching script like the one below - `idf.py monitor` (from an
ESP-IDF shell) covers the same need without installing anything extra.

### Build the image you want to push

```bash
source ~/.espressif/v6.1-beta1/esp-idf/export.sh   # if not already sourced in this shell
idf.py build
```

### Push it over BLE

```bash
source .venv/bin/activate   # if it's a new shell
python tools/ota_client.py build/ble_ota.bin
```

This scans for `esp32-ble-ota`, reads its current version, warns if it matches the image you're
about to send, streams `build/ble_ota.bin` into the inactive OTA slot with live progress, reports
SUCCESS or ERROR, and - only on success - sends REBOOT.

Optional second device-name argument, if you've renamed the firmware's advertised name:

```bash
python tools/ota_client.py build/ble_ota.bin my-custom-device-name
```

### Watching the boot log while an update runs (optional)

The simplest option is `idf.py monitor` in a second terminal (needs the ESP-IDF environment
sourced, same as any build):

```bash
idf.py -p /dev/cu.usbserial-10 monitor   # Ctrl-] to exit; replace the port with yours
```

If you'd rather not have a monitor process holding the serial port (it needs to be closed before
`idf.py flash` can use the port), this is the small standalone `pyserial` script used to capture
logs during development of this example - reads for a fixed duration and writes to a file, no
device reset performed:

```bash
python3 -c "
import serial, time
s = serial.Serial('/dev/cu.usbserial-10', 115200, timeout=1)
end = time.time() + 120  # seconds to capture
while time.time() < end:
    line = s.readline()
    if line:
        print(line.decode(errors='replace').rstrip())
s.close()
"
```

Replace `/dev/cu.usbserial-10` with your board's port (`ls /dev/cu.*` to find it) in every command
above.

## Companion Android app

[`../android-ota-app/`](../android-ota-app/) is a Jetpack Compose app that does the same thing
with a UI: scan, connect, pick a firmware file (or use the one bundled in its assets), push it
with a live progress bar, and tap a button to reboot once it reports success.

## Project Structure

```
ble-ota/
├── CMakeLists.txt
├── sdkconfig.defaults           # BLE stack, ota_0/ota_1 partition table, rollback enable
├── sdkconfig.defaults.esp32c6
├── dependencies.lock             # Locked component-manager dependency versions (commit this)
├── main/
│   ├── CMakeLists.txt
│   ├── idf_component.yml         # Declares the espressif/led_strip dependency (status LED)
│   ├── Kconfig.projbuild         # Status LED mode + rotation delay (idf.py menuconfig)
│   ├── main.c                    # BLE/NimBLE plumbing + boot-time rollback confirmation + status LED
│   ├── gatt_svr.h
│   └── gatt_svr.c                # OTA GATT service: control/data/version characteristics, esp_ota_* calls
└── tools/
    └── ota_client.py             # Reference BLE OTA client (bleak-based)
```
