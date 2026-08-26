package com.esp32ble.ota.domain.model

/**
 * Which Bluetooth mechanism carries the bulk firmware bytes during an OTA update.
 *
 * This is a Kotlin `enum class`: a fixed, named set of possible values (here, exactly two).
 * Unlike a plain `Int` or `String` flag, the compiler enforces that every `when` branch on an
 * `OtaTransport` covers every case - if a third transport were ever added, every place that
 * switches on this would fail to compile until updated. That's the main reason to reach for an
 * enum (or a `sealed` type, seen elsewhere in this codebase) instead of a loose constant.
 */
enum class OtaTransport {
    /**
     * The default, always-available path: firmware bytes go through the OTA Data GATT
     * characteristic, one ATT (Attribute Protocol) write per chunk. Reliable, but every chunk
     * pays a full request/response round trip regardless of size.
     */
    GATT,

    /**
     * Educational/experimental fast path: bypasses per-chunk ATT/GATT overhead entirely by
     * opening a raw L2CAP CoC (Connection-Oriented Channel) - the same lower-level mechanism
     * real high-throughput BLE profiles (audio, fast firmware transfer) use. See
     * `BleOtaRepositoryImpl.streamFirmwareViaL2cap` and the ble-ota firmware's `ota_service.c`
     * for the technical explanation and what's simplified here versus a production
     * implementation. Requires Android 10 (API 29) or newer - `BluetoothDevice.createL2capChannel`
     * doesn't exist on older versions.
     */
    L2CAP_COC,
}
