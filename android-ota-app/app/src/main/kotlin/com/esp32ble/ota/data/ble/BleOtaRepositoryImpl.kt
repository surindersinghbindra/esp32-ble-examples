package com.esp32ble.ota.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.esp32ble.ota.domain.model.BleDeviceInfo
import com.esp32ble.ota.domain.model.BleOtaException
import com.esp32ble.ota.domain.model.HeartRateSample
import com.esp32ble.ota.domain.model.LedConfig
import com.esp32ble.ota.domain.model.OtaTransferEvent
import com.esp32ble.ota.domain.repository.BleOtaRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Talks to the ble-ota firmware's custom GATT service (see ../../../../../../../ble-ota/main/gatt_svr.c
 * for the device side of this protocol).
 *
 * Android only allows one outstanding GATT operation per connection at a time, so every public
 * suspend function here runs under [opMutex]; callback results come back through [events] /
 * [statusNotifications], which the corresponding suspend call awaits.
 */
@SuppressLint("MissingPermission") // Permissions are requested by the UI before any of this runs.
class BleOtaRepositoryImpl(private val context: Context) : BleOtaRepository {

    private sealed interface GattEvent {
        data class ConnectionStateChanged(val newState: Int, val status: Int) : GattEvent
        data class ServicesDiscovered(val status: Int) : GattEvent
        data class MtuChanged(val mtu: Int, val status: Int) : GattEvent
        data class DescriptorWritten(val status: Int) : GattEvent
        data class CharacteristicWritten(val status: Int) : GattEvent
        data class CharacteristicRead(val status: Int, val value: ByteArray) : GattEvent
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val opMutex = Mutex()
    private val events = Channel<GattEvent>(Channel.BUFFERED)
    private val statusNotifications = Channel<Byte>(Channel.BUFFERED)

    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var ledConfigChar: BluetoothGattCharacteristic? = null
    private var hrMeasurementChar: BluetoothGattCharacteristic? = null
    private var hrRateControlChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23

    /**
     * Backpressure: bounded buffer that drops the *oldest* sample if nothing is collecting
     * [observeHeartRate] fast enough, instead of growing without bound or blocking the BLE
     * callback thread. This is the actual mechanism - see ObserveHeartRateUseCase's doc and the
     * app README for how the UI layer also throttles its own redraw rate on top of this.
     */
    private val heartRateEvents = MutableSharedFlow<HeartRateSample>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            events.trySend(GattEvent.ConnectionStateChanged(newState, status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.trySend(GattEvent.ServicesDiscovered(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            events.trySend(GattEvent.MtuChanged(mtu, status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.trySend(GattEvent.DescriptorWritten(status))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            events.trySend(GattEvent.CharacteristicWritten(status))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                events.trySend(GattEvent.CharacteristicRead(status, characteristic.value ?: ByteArray(0)))
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            events.trySend(GattEvent.CharacteristicRead(status, value))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleNotification(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }
    }

    private fun handleNotification(uuid: java.util.UUID, value: ByteArray?) {
        when (uuid) {
            BleOtaProtocol.CONTROL_CHAR_UUID ->
                value?.firstOrNull()?.let { statusNotifications.trySend(it) }
            HeartRateProtocol.MEASUREMENT_CHAR_UUID -> {
                // Byte 0 is the flags byte (see heart_rate_service.c); byte 1 is BPM for the
                // simple uint8 format this firmware always uses.
                val bytes = value ?: return
                if (bytes.size >= 2) {
                    val bpm = bytes[1].toInt() and 0xFF
                    heartRateEvents.tryEmit(HeartRateSample(bpm, System.currentTimeMillis()))
                }
            }
        }
    }

    private suspend inline fun <reified T : GattEvent> awaitEvent(timeoutMs: Long = 15_000L): T =
        withTimeout(timeoutMs) {
            while (true) {
                val event = events.receive()
                if (event is T) return@withTimeout event
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun BluetoothGatt.writeCharacteristicCompat(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = value
            writeCharacteristic(characteristic)
        }
    }

    private fun BluetoothGatt.writeDescriptorCompat(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            descriptor.value = value
            writeDescriptor(descriptor)
        }
    }

    /** Shared by any characteristic we need notifications from (OTA control, heart rate). */
    private suspend fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(BleOtaProtocol.CCCD_UUID)
            ?: throw BleOtaException("Characteristic ${characteristic.uuid} has no CCCD descriptor")
        if (!g.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            throw BleOtaException("Failed to enable notifications on ${characteristic.uuid}")
        }
        val ev = awaitEvent<GattEvent.DescriptorWritten>()
        if (ev.status != BluetoothGatt.GATT_SUCCESS) {
            throw BleOtaException("Enabling notifications on ${characteristic.uuid} failed (status=${ev.status})")
        }
    }

    private suspend fun disableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, false)
        val cccd = characteristic.getDescriptor(BleOtaProtocol.CCCD_UUID) ?: return
        if (g.writeDescriptorCompat(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
            runCatching { awaitEvent<GattEvent.DescriptorWritten>(timeoutMs = 3_000) }
        }
    }

    override fun scanForDevice(targetName: String): Flow<BleDeviceInfo> = callbackFlow {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(BleOtaException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }

        val seenAddresses = mutableSetOf<String>()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name == targetName && seenAddresses.add(result.device.address)) {
                    trySend(BleDeviceInfo(address = result.device.address, name = name))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleOtaException("BLE scan failed, error=$errorCode"))
            }
        }

        val filters = listOf(ScanFilter.Builder().setDeviceName(targetName).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            close(BleOtaException("Missing Bluetooth permission: ${e.message}"))
        }

        awaitClose {
            try {
                scanner.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Permission was revoked mid-scan; nothing to clean up beyond this.
            }
        }
    }

    override suspend fun connect(device: BleDeviceInfo): Result<Int> = opMutex.withLock {
        runCatching {
            val remote: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(device.address)
            val g = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g

            val connectEvent = awaitEvent<GattEvent.ConnectionStateChanged>(timeoutMs = 15_000)
            if (connectEvent.newState != BluetoothProfile.STATE_CONNECTED) {
                throw BleOtaException("Connection failed (status=${connectEvent.status})")
            }

            g.discoverServices()
            val discovered = awaitEvent<GattEvent.ServicesDiscovered>()
            if (discovered.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleOtaException("Service discovery failed (status=${discovered.status})")
            }

            val service = g.getService(BleOtaProtocol.SERVICE_UUID)
                ?: throw BleOtaException("OTA service not found on device")
            val ctrl = service.getCharacteristic(BleOtaProtocol.CONTROL_CHAR_UUID)
                ?: throw BleOtaException("OTA control characteristic not found")
            val data = service.getCharacteristic(BleOtaProtocol.DATA_CHAR_UUID)
                ?: throw BleOtaException("OTA data characteristic not found")
            controlChar = ctrl
            dataChar = data

            // LED and Heart Rate services are optional extras on top of core OTA - look them up
            // but don't fail the connection if a device variant doesn't have them.
            g.getService(LedServiceProtocol.SERVICE_UUID)
                ?.getCharacteristic(LedServiceProtocol.CONFIG_CHAR_UUID)
                ?.also { ledConfigChar = it }
            g.getService(HeartRateProtocol.SERVICE_UUID)?.let { hrService ->
                hrMeasurementChar = hrService.getCharacteristic(HeartRateProtocol.MEASUREMENT_CHAR_UUID)
                hrRateControlChar = hrService.getCharacteristic(HeartRateProtocol.RATE_CONTROL_CHAR_UUID)
            }

            g.requestMtu(BleOtaProtocol.PREFERRED_MTU)
            negotiatedMtu = runCatching { awaitEvent<GattEvent.MtuChanged>(timeoutMs = 5_000).mtu }
                .getOrDefault(23)

            enableNotifications(g, ctrl)

            negotiatedMtu
        }
    }

    override suspend fun readDeviceVersion(): Result<String> = opMutex.withLock {
        runCatching {
            val g = gatt ?: throw BleOtaException("Not connected")
            val service = g.getService(BleOtaProtocol.SERVICE_UUID)
                ?: throw BleOtaException("OTA service not found")
            val versionChar = service.getCharacteristic(BleOtaProtocol.VERSION_CHAR_UUID)
                ?: throw BleOtaException("Version characteristic not found")

            if (!g.readCharacteristic(versionChar)) {
                throw BleOtaException("Failed to initiate version read")
            }
            val ev = awaitEvent<GattEvent.CharacteristicRead>(timeoutMs = 5_000)
            if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleOtaException("Version read failed (status=${ev.status})")
            }
            String(ev.value, Charsets.UTF_8)
        }
    }

    override suspend fun disconnect() = opMutex.withLock {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        controlChar = null
        dataChar = null
        ledConfigChar = null
        hrMeasurementChar = null
        hrRateControlChar = null
    }

    override suspend fun readLedConfig(): Result<LedConfig> = opMutex.withLock {
        runCatching {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ch = ledConfigChar ?: throw BleOtaException("LED service not found on device")
            if (!g.readCharacteristic(ch)) {
                throw BleOtaException("Failed to initiate LED config read")
            }
            val ev = awaitEvent<GattEvent.CharacteristicRead>(timeoutMs = 5_000)
            if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleOtaException("LED config read failed (status=${ev.status})")
            }
            LedConfig.fromWireBytes(ev.value) ?: throw BleOtaException("Malformed LED config from device")
        }
    }

    override suspend fun writeLedConfig(config: LedConfig): Result<Unit> = opMutex.withLock {
        runCatching {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ch = ledConfigChar ?: throw BleOtaException("LED service not found on device")
            if (!g.writeCharacteristicCompat(ch, config.toWireBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                throw BleOtaException("Failed to write LED config")
            }
            val ev = awaitEvent<GattEvent.CharacteristicWritten>()
            if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleOtaException("LED config write rejected (status=${ev.status})")
            }
        }
    }

    override fun observeHeartRate(): Flow<HeartRateSample> = flow {
        opMutex.withLock {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ch = hrMeasurementChar ?: throw BleOtaException("Heart rate service not found on device")
            enableNotifications(g, ch)
        }
        emitAll(heartRateEvents)
    }.onCompletion {
        val g = gatt
        val ch = hrMeasurementChar
        if (g != null && ch != null) {
            runCatching { opMutex.withLock { disableNotifications(g, ch) } }
        }
    }

    override suspend fun setHeartRateFastMode(fast: Boolean): Result<Unit> = opMutex.withLock {
        runCatching {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ch = hrRateControlChar ?: throw BleOtaException("Heart rate service not found on device")
            val payload = byteArrayOf(if (fast) 1 else 0)
            if (!g.writeCharacteristicCompat(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                throw BleOtaException("Failed to write heart rate control")
            }
            val ev = awaitEvent<GattEvent.CharacteristicWritten>()
            if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                throw BleOtaException("Heart rate control write rejected (status=${ev.status})")
            }
        }
    }

    override fun performOtaUpdate(firmware: ByteArray): Flow<OtaTransferEvent> = flow {
        opMutex.withLock {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ctrl = controlChar ?: throw BleOtaException("Not connected")
            val data = dataChar ?: throw BleOtaException("Not connected")

            // Discard any stale status byte left over from a previous run.
            while (statusNotifications.tryReceive().isSuccess) { /* drain */ }

            writeControlCommand(g, ctrl, BleOtaProtocol.CMD_START)
            val startStatus = withTimeout(5_000) { statusNotifications.receive() }
            if (startStatus != BleOtaProtocol.STATUS_IN_PROGRESS) {
                emit(OtaTransferEvent.Failure("Device did not acknowledge START (status=$startStatus)"))
                return@withLock
            }

            // The firmware's data characteristic buffer (OTA_DATA_MAX_CHUNK in gatt_svr.c) is a
            // fixed 512 bytes, so cap here even if a larger MTU gets negotiated - otherwise the
            // device correctly (and safely) rejects the oversized write with GATT_INVALID_ATTRIBUTE_LENGTH.
            val chunkSize = (negotiatedMtu - 3).coerceIn(20, 512)
            var sent = 0
            var offset = 0
            while (offset < firmware.size) {
                val end = (offset + chunkSize).coerceAtMost(firmware.size)
                writeDataChunk(g, data, firmware.copyOfRange(offset, end))
                sent += end - offset
                offset = end
                emit(OtaTransferEvent.Progress(sent, firmware.size))
            }

            emit(OtaTransferEvent.Validating)
            writeControlCommand(g, ctrl, BleOtaProtocol.CMD_END)
            val endStatus = withTimeout(20_000) { statusNotifications.receive() }
            if (endStatus == BleOtaProtocol.STATUS_SUCCESS) {
                emit(OtaTransferEvent.Success)
            } else {
                emit(OtaTransferEvent.Failure("Device rejected the image (status=$endStatus)"))
            }
        }
    }.catch { e ->
        emit(OtaTransferEvent.Failure(e.message ?: "Unknown BLE error"))
    }

    override suspend fun reboot(): Result<Unit> = opMutex.withLock {
        runCatching {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ctrl = controlChar ?: throw BleOtaException("Not connected")
            writeControlCommand(g, ctrl, BleOtaProtocol.CMD_REBOOT)
        }
    }

    private suspend fun writeControlCommand(
        g: BluetoothGatt,
        ctrl: BluetoothGattCharacteristic,
        cmd: Byte,
    ) {
        if (!g.writeCharacteristicCompat(ctrl, byteArrayOf(cmd), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            throw BleOtaException("Failed to send command 0x%02x".format(cmd))
        }
        val ev = awaitEvent<GattEvent.CharacteristicWritten>()
        if (ev.status != BluetoothGatt.GATT_SUCCESS) {
            throw BleOtaException("Command 0x%02x rejected (status=${ev.status})".format(cmd))
        }
    }

    private suspend fun writeDataChunk(
        g: BluetoothGatt,
        data: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        if (!g.writeCharacteristicCompat(data, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
            throw BleOtaException("Failed to write a firmware data chunk")
        }
        val ev = awaitEvent<GattEvent.CharacteristicWritten>()
        if (ev.status != BluetoothGatt.GATT_SUCCESS) {
            throw BleOtaException("Firmware data chunk rejected (status=${ev.status})")
        }
    }
}
