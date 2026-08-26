#!/usr/bin/env python3
"""Reference client for the ble-ota example's GATT OTA protocol.

Scans for the device, connects, and streams a firmware .bin file into
whichever OTA partition it isn't currently running from, then tells it to
validate and reboot into the new image.

Requires: pip install bleak

Usage:
    python ota_client.py <firmware.bin> [device_name]
"""
import asyncio
import sys
import time

from bleak import BleakScanner, BleakClient

CONTROL_UUID = "f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba1"
DATA_UUID = "f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba2"
VERSION_UUID = "f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba3"

CMD_START = bytes([0x01])
CMD_END = bytes([0x02])
CMD_ABORT = bytes([0x03])
CMD_REBOOT = bytes([0x04])

STATUS_NAMES = {0x00: "IDLE", 0x01: "IN_PROGRESS", 0x02: "SUCCESS", 0x03: "ERROR"}

# Offset of esp_app_desc_t.version within a built .bin: esp_image_header_t
# (24 bytes) + the first segment's esp_image_segment_header_t (8 bytes), then
# esp_app_desc_t.version is the first real field (after magic_word,
# secure_version, and a reserved uint32[2]) at +16 into that struct.
APP_DESC_OFFSET = 24 + 8
VERSION_FIELD_OFFSET = APP_DESC_OFFSET + 16
VERSION_FIELD_LEN = 32


def read_bin_version(path: str) -> str:
    with open(path, "rb") as f:
        f.seek(VERSION_FIELD_OFFSET)
        raw = f.read(VERSION_FIELD_LEN)
    return raw.split(b"\x00", 1)[0].decode("utf-8", errors="replace")


async def main():
    if len(sys.argv) < 2:
        print(f"usage: {sys.argv[0]} <firmware.bin> [device_name]")
        sys.exit(1)

    fw_path = sys.argv[1]
    device_name = sys.argv[2] if len(sys.argv) > 2 else "esp32-ble-ota"

    with open(fw_path, "rb") as f:
        firmware = f.read()
    print(f"Loaded {len(firmware)} bytes from {fw_path}")

    new_version = read_bin_version(fw_path)
    print(f"New firmware version: {new_version!r}")

    print(f"Scanning for '{device_name}'...")
    dev = await BleakScanner.find_device_by_name(device_name, timeout=10.0)
    if dev is None:
        print("Device not found. Is it powered on and advertising?")
        sys.exit(1)

    status_event = asyncio.Event()
    last_status = {"value": None}

    def on_status(_handle, data: bytearray):
        code = data[0]
        last_status["value"] = code
        print(f"  status <- {STATUS_NAMES.get(code, hex(code))}")
        status_event.set()

    async with BleakClient(dev) as client:
        # Write-with-response paces itself on the ATT-level ack from the
        # peripheral, so it can't outrun the link the way write-without-
        # response can. That's the whole reason this client is slow but
        # reliable - see the README for the tradeoff.
        chunk_size = max(20, client.mtu_size - 3)
        print(f"Connected. Negotiated MTU {client.mtu_size} -> chunk size {chunk_size} bytes")

        # --- Firmware version check (precaution, not a hard gate) ---
        # Warns if the device already reports the same version as the image
        # we're about to push, but still lets the update proceed - useful
        # when you're deliberately re-flashing the same version to test.
        try:
            device_version_bytes = await client.read_gatt_char(VERSION_UUID)
            device_version = device_version_bytes.decode("utf-8", errors="replace")
            print(f"Device is currently running version: {device_version!r}")
            if device_version == new_version:
                print("*** WARNING: device already reports this exact version. ***")
                print("*** Proceeding anyway (re-flashing the same version). ***")
        except Exception as e:
            print(f"Could not read device version (older firmware without this characteristic?): {e}")

        await client.start_notify(CONTROL_UUID, on_status)

        print("Sending START...")
        status_event.clear()
        await client.write_gatt_char(CONTROL_UUID, CMD_START, response=True)
        await asyncio.wait_for(status_event.wait(), timeout=5.0)
        if last_status["value"] != 0x01:
            print("Device did not report IN_PROGRESS, aborting")
            sys.exit(1)

        print(f"Streaming {len(firmware)} bytes (this takes a couple of minutes)...")
        t0 = time.time()
        sent = 0
        for offset in range(0, len(firmware), chunk_size):
            chunk = firmware[offset:offset + chunk_size]
            await client.write_gatt_char(DATA_UUID, chunk, response=True)
            sent += len(chunk)
            if sent % (chunk_size * 50) < chunk_size:
                pct = 100 * sent / len(firmware)
                print(f"  {sent}/{len(firmware)} bytes ({pct:.0f}%)")
        dt = time.time() - t0
        print(f"Sent {sent} bytes in {dt:.1f}s ({sent / dt / 1024:.1f} KB/s)")

        print("Sending END...")
        status_event.clear()
        await client.write_gatt_char(CONTROL_UUID, CMD_END, response=True)
        try:
            await asyncio.wait_for(status_event.wait(), timeout=15.0)
        except asyncio.TimeoutError:
            print("Timed out waiting for END result (device may already be rebooting)")
            return

        if last_status["value"] != 0x02:
            print("OTA FAILED - device kept running its current firmware")
            return

        print("OTA SUCCESS - new image validated, boot partition switched")
        print("Sending REBOOT...")
        await client.write_gatt_char(CONTROL_UUID, CMD_REBOOT, response=True)
        print("Device is rebooting into the new image")


if __name__ == "__main__":
    asyncio.run(main())
