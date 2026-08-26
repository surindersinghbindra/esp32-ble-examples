package com.esp32ble.ota.domain.model

/** One BLE heart-rate notification: beats-per-minute plus when it arrived on the phone. */
data class HeartRateSample(val bpm: Int, val timestampMs: Long)
