package com.esp32ble.ota.data.ble

import java.util.UUID

/** Must match the GATT service/characteristics defined in ble-ota/main/gatt_svr.c. */
object BleOtaProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba0")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba1")
    val DATA_CHAR_UUID: UUID = UUID.fromString("f3e2d1c0-bfae-9d8c-7b6a-5f4e3d2c1ba2")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_START: Byte = 0x01
    const val CMD_END: Byte = 0x02
    const val CMD_ABORT: Byte = 0x03
    const val CMD_REBOOT: Byte = 0x04

    const val STATUS_IDLE: Byte = 0x00
    const val STATUS_IN_PROGRESS: Byte = 0x01
    const val STATUS_SUCCESS: Byte = 0x02
    const val STATUS_ERROR: Byte = 0x03

    /** What we ask for; the firmware's preferred MTU is 517, so this is the ceiling either side needs. */
    const val PREFERRED_MTU = 517
}
