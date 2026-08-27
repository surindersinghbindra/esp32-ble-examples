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

Besides the core OTA mechanism, this firmware also carries two more GATT services, mostly as a
learning ground for other BLE patterns - **live, persisted control of the onboard LED**, and a
**standard Heart Rate service with a simulated BPM value**, used to demonstrate client-side
backpressure. See [Modular architecture](#modular-architecture) and the protocol sections below.

## Modular architecture

Each GATT service is its own self-contained ESP-IDF **component**, not just a file inside `main/`:

```
ble-ota/
├── main/main.c                        -- brings up the BLE stack, wires the 3 services together,
│                                          and owns the boot-time OTA rollback confirmation
└── components/
    ├── ota_service/                   -- push a new firmware image over BLE (see below)
    ├── led_service/                    -- live LED color/mode/brightness/blink, persisted to NVS
    └── heart_rate_service/              -- standard Bluetooth Heart Rate service + simulator
```

`main.c` calls each service's own `..._init()` once (after `ble_svc_gap_init()` /
`ble_svc_gatt_init()`, which must only happen once, globally) and fans out GAP connect/disconnect/
subscribe events to whichever services care about them. Each component is otherwise independent -
you could delete `heart_rate_service/` entirely and the OTA/LED functionality wouldn't notice.

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

## GATT protocol: OTA service (`components/ota_service/`)

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

## Fast-update path: L2CAP CoC (educational)

Every GATT write - including the Data characteristic above - travels as one ATT (Attribute
Protocol) packet per call, each a full request/response round trip. **L2CAP CoC** (Connection-
Oriented Channel) is a different, lower layer of the Bluetooth stack: instead of characteristics,
it's a private credit-based data pipe identified by a **PSM** (Protocol/Service Multiplexer - the
L2CAP equivalent of a TCP port). Once open, both sides exchange raw SDUs with no ATT framing at
all. This is the same mechanism real high-throughput BLE profiles (audio, fast firmware transfer)
use to get meaningfully better throughput than plain GATT.

`ota_service` registers an L2CAP CoC server once at boot, listening on a fixed PSM (`0x00F0`,
chosen from the LE dynamic range `0x0080`-`0x00FF`), as an alternative path for the bulk firmware
bytes only - START/END/REBOOT and status still go over the Control characteristic either way,
since ATT overhead is irrelevant for single bytes. See the big comment at the top of
`ota_service.c` for the technical detail (SDUs, credit-based flow control, why re-arming the
receive buffer doubles as the flow-control point).

**This is explicitly a learning example, not a hardened path** - fixed PSM instead of negotiated,
fixed buffer sizing, no reconnection handling.

**Verified finding, honestly reported:** the firmware side works correctly - confirmed via the
boot log (`OTA fast-update L2CAP CoC server listening on PSM 0x00f0`) across multiple test
attempts. The Android companion app's client-side connection (`BluetoothDevice.createL2capChannel`)
consistently failed to connect on the specific test phone used (a Redmi/Xiaomi device, Android 12)
with no corresponding L2CAP event ever appearing in this firmware's log - meaning the connection
attempt never even reached the board. This points to the phone's own Bluetooth HAL not reliably
supporting the L2CAP CoC *initiator* (client) role, which is a known, widely-reported
inconsistency across Android OEM Bluetooth stacks for this specific API (available since Android
10, but not uniformly implemented underneath it). The GATT path remains the reliable default for
exactly this reason - see `android-ota-app/README.md` for the client-side detail and code.

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

4. **Disconnects abort cleanly.** If the BLE link drops mid-transfer, `ota_service_on_disconnect()`
   calls `esp_ota_abort()` so the OTA handle isn't left open and the target partition isn't left in
   a half-written state for a future update to trip over.

5. **`self_check_ok()` is intentionally minimal** - it just confirms the app descriptor is
   readable and free heap looks sane. Treat it as a placeholder: extend it with whatever actually
   proves *your* firmware is healthy (a successful sensor read, a successful network connection,
   a real self-test) before you rely on this in a real product.

## What this example does *not* do (read before shipping anything)

- **No authentication or encryption on the OTA transfer, deliberately.** Any BLE central in range
  can still connect and push firmware. Pairing/bonding *is* now wired up project-wide (see
  "Security: pairing and bonding" below) - it was deliberately left off the OTA Control/Data/
  Version characteristics rather than applied everywhere, so this section's warning stays accurate
  for the one path where it matters most: **anyone nearby can still push a firmware image without
  pairing first.** For anything beyond a bench test, add `BLE_GATT_CHR_F_WRITE_ENC` (and
  `_READ_ENC`) to `ota_service.c`'s characteristic definitions the same way `led_service.c` does,
  and/or verify a cryptographic signature on the image before calling `esp_ota_set_boot_partition()`.
- **No rollback-protection against re-installing an older version.** The Version characteristic
  and the version check in the clients (see below) only *warn* on a match - they don't inspect
  version numbers to block a downgrade, and there is no anti-rollback eFuse configured
  (`CONFIG_BOOTLOADER_APP_ANTI_ROLLBACK`). It will happily install an older or identical firmware
  if you ask it to.
- **Throughput is slow (a few KB/s).** The reference client uses write-with-response for every
  chunk, which round-trips over BLE per write - reliable, but not fast. A ~640KB image takes a
  few minutes. A faster client could use write-without-response with real credit-based flow
  control, but that's more complex than this example needs.

## GATT protocol: LED service (`components/led_service/`)

Custom service `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1bb0` with one characteristic:

| Characteristic | UUID | Properties | Purpose |
|---|---|---|---|
| Config | `...bb1` | read, write, indicate; read/write require an **encrypted link** | Get/set color, mode, brightness, blink timing |

This is **live, runtime configuration** - not the old Kconfig-based approach (that's gone; see the
git history if you want to compare). A write is applied to the LED immediately *and* saved to NVS
(flash storage that survives power loss and reboots), so whatever was last set is restored
automatically next boot - no rebuild or reflash needed to change how the LED behaves, unlike
everything else about updating this board.

Wire format, 7 bytes, same layout whether reading or writing:

| Byte(s) | Field | Values |
|---|---|---|
| 0 | mode | `0` off, `1` solid, `2` blink |
| 1-3 | red, green, blue | `0-255` each |
| 4 | brightness | `0-255` - scales red/green/blue down before display |
| 5-6 | blink_interval_ms | uint16, little-endian; only used in blink mode |

This is also the one characteristic in the project that requires pairing (see below) and uses an
**Indication** rather than a Notification: subscribe to it, and every successful write indicates
the new config back out, with NimBLE tracking the peer's acknowledgment of each one - deliberately
different from Heart Rate's fire-and-forget Notify, since a lost "the LED changed" confirmation
matters more than a lost heartbeat sample.

## Security: pairing and bonding

Every characteristic in this project used to run at Security Mode 1 / Level 1 (no security at
all) - anyone in range could read/write anything with no pairing step. That's still true for the
OTA and Heart Rate services (see the warning above), but `main.c` now configures NimBLE's Security
Manager project-wide:

```c
ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;   /* no display/keyboard -> Just Works pairing */
ble_hs_cfg.sm_bonding = 1;                    /* remember the pairing across reconnects */
ble_hs_cfg.sm_mitm = 0;                       /* Just Works has no MITM protection - honest, not a bug */
ble_hs_cfg.sm_sc = 1;                         /* LE Secure Connections, not legacy pairing */
```

plus `ble_store_config_init()` so bonds persist in NVS across reboots (`CONFIG_BT_NIMBLE_NVS_PERSIST=y`
in `sdkconfig.defaults`), and a `BLE_GAP_EVENT_REPEAT_PAIRING` handler that deletes a stale bond and
retries rather than permanently locking out a peer that lost its half (e.g. "Forget device" on the
phone).

None of this *forces* a connection to pair on its own - nothing here calls
`ble_gap_security_initiate()`. Pairing actually kicks off the first time a central touches an
encryption-requiring attribute (the LED Config characteristic above) and its BLE stack reacts to
the resulting ATT error by starting the Security Manager procedure itself; Android's `BluetoothGatt`
does this automatically. In the Bluetooth spec's terms, that characteristic is now Security Mode 1
/ Level 2: unauthenticated pairing with encryption, no MITM protection - deliberately the simplest
level that still exercises real pairing, matched to this board having no display or keyboard for
anything stronger (Passkey Entry, Numeric Comparison).

**Status: verified on hardware.** On this board's usual test phone (Redmi/Xiaomi, Android 12), the
first LED-config touch after connecting does trigger a system "Pair with esp32-ble-ota?" prompt,
and accepting it produces a real `encryption change event; status=0` in the serial log, followed by
the read/write succeeding. Two things worth knowing before you try this yourself:

- **A canceled or ignored prompt times out at the protocol level, not just in the app.** NimBLE's
  Security Manager gives a pairing attempt about 30 seconds; if nothing answers the prompt in time,
  the log shows `encryption change event; status=13` (`BLE_HS_ETIMEOUT`) and the phone disconnects.
  That's correct behavior, not a bug - the app-side timeout for this operation was deliberately set
  well above 30s (see the Android app's README) so it's the phone, not the app, that gives up first.
- **After a canceled/timed-out prompt, Android won't offer to pair again on its own.** Several
  reconnect attempts in a row connected and disconnected within a second or two, with no new pairing
  attempt logged at all - Android's Bluetooth stack appears to cache the rejection per-device for
  the session. The fix is on the phone, not the firmware or app: **Settings → Bluetooth → find the
  device → Forget** (or toggle Bluetooth off/on), then reconnect. Once bonded successfully, normal
  reconnects don't re-prompt, as expected.

One more thing seen in the log around this same handshake, worth a mention in case it resurfaces:
an HCI-level `BLE_ERR_INV_HCI_CMD_PARMS` on an `ogf=0x08, ocf=0x0027` command (LE Set Data Length)
right as the connection's MTU/subscription settled. It didn't block anything - encryption still
came up successfully right after - but if you see it and something *does* break, that's the command
to go dig into first.

**Not implemented**: a Filter Accept List (only allow already-bonded peers to connect at all,
rather than just gating individual characteristics) would be the natural next step - NimBLE
supports it via `ble_gap_adv_start()`'s filter policy, but it adds real complexity (managing the
accept list as bonds are created/deleted) for a single-user bench project where anyone nearby
being able to *see* the device isn't the actual risk.

## Connection parameters

Right after a connection is established, `main.c` calls `ble_gap_update_params()` asking for a
30-50ms interval, no slave latency, and a 4s supervision timeout - the usual reason a peripheral
does this at all is trading a little responsiveness for lower power draw versus whatever the
central proposed by default. The outcome (accepted as-is, or renegotiated) comes back as a
`BLE_GAP_EVENT_CONN_UPDATE` and is only logged, not acted on further. The firmware never requests
anything beyond this one fixed request - it doesn't, for example, ask for tighter parameters during
an OTA transfer and relax them afterward, which would be the natural next step if transfer speed
ever needed to improve without touching the data path itself.

## GATT protocol: Heart Rate service (`components/heart_rate_service/`)

Uses the **standard Bluetooth SIG Heart Rate Service** (not a custom one), so any generic BLE
viewer app (nRF Connect, etc.) recognizes it correctly - plus one custom characteristic that isn't
part of the standard:

| Characteristic | UUID | Properties | Purpose |
|---|---|---|---|
| Heart Rate Measurement | `0x2A37` (standard) | notify | Flags byte (`0x06`) + 1 byte BPM |
| Body Sensor Location | `0x2A38` (standard) | read | Fixed `0x01` (Chest) |
| Rate Control | `f3e2d1c0-...-1bc0` (custom) | write | `0` = normal ~1/s, `1` = fast ~20/s |

The BPM value is simulated - a small random walk around 55-160, updated and notified at whichever
rate the Rate Control characteristic last selected. Real heart rate hardware only ever notifies at
~1/s and never needs backpressure handling; the fast mode exists purely so a client has something
to actually stress-test its backpressure handling against. See the companion Android app's README
for how it handles that on the receiving end - a bounded drop-oldest buffer plus a UI redraw rate
decoupled from the BLE notify rate.

## GATT attribute reference: every service, characteristic, and CCCD

A consolidated reference for the whole GATT server this firmware exposes - the per-service
sections above explain *why* each one exists; this is the complete *what*, in one place.

### Services and characteristics

| Service | Characteristic | UUID | Properties | CCCD? | Security |
|---|---|---|---|---|---|
| OTA (`...1ba0`) | Control | `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba1` | read, write, **notify** | Yes | None |
| OTA (`...1ba0`) | Data | `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba2` | write, write-without-response | No | None |
| OTA (`...1ba0`) | Version | `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba3` | read | No | None |
| LED (`...1bb0`) | Config | `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1bb1` | read-enc, write-enc, **indicate** | Yes | Encrypted link (Mode 1 / Level 2) |
| Heart Rate (`0x180D`, standard) | Heart Rate Measurement | `0x2A37` (standard) | **notify** | Yes | None |
| Heart Rate (`0x180D`, standard) | Body Sensor Location | `0x2A38` (standard) | read | No | None |
| Heart Rate (`0x180D`, standard) | Rate Control | `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1bc0` | write | No | None |

All custom (non-standard-SIG) UUIDs share the base `f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1bXX`, varying
only the last byte - see each characteristic's UUID column above for the exact byte. The Android
app's `BleConstants.kt` (`BleOtaProtocol`, `LedServiceProtocol`, `HeartRateProtocol`,
`OtaL2capProtocol`) mirrors every one of these values by hand; there's no shared schema between
firmware and app, just matching constants kept in sync manually on both ends.

**Attribute handles are deliberately not listed here** - NimBLE assigns them at boot, in service
registration order (`ota_service_init()`, then `led_service_init()`, then
`heart_rate_service_init()` in `main.c`), and they can shift if that order or the set of
characteristics ever changes. Treat them the way any real BLE client has to: discover them via the
standard GATT service-discovery procedure (by UUID) rather than hardcoding a number. If you want to
see the actual handles a given build assigned, `main.c`'s `gatt_register_cb()` logs each one
(`ESP_LOGD`) at boot - bump the log level to Debug to see them.

### CCCD (Client Characteristic Configuration Descriptor)

Three characteristics above are marked "CCCD? Yes" because they have `NOTIFY` and/or `INDICATE` in
their properties - the standard `0x2902` descriptor a client writes to actually turn that stream on
or off. **None of this project's service definitions declare that descriptor themselves** - every
`.flags` entry with `BLE_GATT_CHR_F_NOTIFY` and/or `BLE_GATT_CHR_F_INDICATE` above gets one added by
NimBLE automatically, which is why you won't find a `.descriptors` array anywhere in
`ota_service.c`, `led_service.c`, or `heart_rate_service.c`.

The descriptor's value is 2 bytes, and only the bottom two bits mean anything:

| Value written | Effect |
|---|---|
| `0x0000` | Turn off both - the default, unsubscribed state |
| `0x0001` | Enable Notifications |
| `0x0002` | Enable Indications |
| `0x0003` | Enable both (only meaningful on a characteristic that sets both flags - none here do) |

A client writing to this descriptor is what fires `BLE_GAP_EVENT_SUBSCRIBE` on the firmware side -
`main.c`'s GAP event handler reads `event->subscribe.cur_notify` / `cur_indicate` off that event and
forwards it to whichever service owns the characteristic (`ota_service_on_subscribe()`,
`led_service_on_subscribe()`, `heart_rate_service_on_subscribe()`), which is how each service knows
whether it's currently safe to call `ble_gatts_notify_custom()` / `ble_gatts_indicate()` at all -
notifying or indicating a characteristic nobody has subscribed to is a silent no-op the BLE stack
would otherwise just drop.

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

[`../android-ota-app/`](../android-ota-app/) is a Jetpack Compose app that talks to all three
services: scan/connect/push firmware with a live progress bar and a reboot button (OTA), color
presets/mode/brightness/blink controls (LED), and a subscribe toggle with a live scrolling BPM
graph plus a fast-mode switch to see backpressure handling in action (Heart Rate).

## Project Structure

```
ble-ota/
├── CMakeLists.txt
├── sdkconfig.defaults           # BLE stack, ota_0/ota_1 partition table, rollback enable
├── sdkconfig.defaults.esp32c6
├── dependencies.lock            # Locked component-manager dependency versions (commit this)
├── main/
│   ├── CMakeLists.txt
│   └── main.c                   # BLE/NimBLE plumbing + wires up the 3 services below +
│                                 # boot-time OTA rollback confirmation
├── components/
│   ├── ota_service/
│   │   ├── CMakeLists.txt
│   │   ├── include/ota_service.h
│   │   └── ota_service.c         # Control/Data/Version characteristics, esp_ota_* calls
│   ├── led_service/
│   │   ├── CMakeLists.txt
│   │   ├── idf_component.yml     # Declares the espressif/led_strip dependency
│   │   ├── include/led_service.h
│   │   └── led_service.c         # Config characteristic, NVS persistence, LED driving task
│   └── heart_rate_service/
│       ├── CMakeLists.txt
│       ├── include/heart_rate_service.h
│       └── heart_rate_service.c  # Standard Heart Rate service + BPM simulator task
└── tools/
    └── ota_client.py             # Reference BLE OTA client (bleak-based)
```
