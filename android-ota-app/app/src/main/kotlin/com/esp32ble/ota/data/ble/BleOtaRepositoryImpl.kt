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
import com.esp32ble.ota.domain.model.OtaTransport
import com.esp32ble.ota.domain.repository.BleOtaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to the ble-ota firmware's custom GATT service (see ../../../../../../../ble-ota/main/gatt_svr.c
 * for the device side of this protocol).
 *
 * Android only allows one outstanding GATT operation per connection at a time, so every public
 * suspend function here runs under [opMutex]; callback results come back through [events] /
 * [statusNotifications], which the corresponding suspend call awaits.
 *
 * `@Singleton` tells Hilt to build this class exactly once for the whole app process and hand
 * back that same instance every time something asks for a [BleOtaRepository] - essential here,
 * since a live `BluetoothGatt` connection (`gatt` below) needs to be shared across every screen
 * that touches it, not recreated per-screen. Without this annotation, Hilt's default is to build
 * a fresh instance at every injection site, which would silently break the whole "stay connected
 * while navigating the app" assumption everything else here relies on.
 */
@Singleton
@SuppressLint("MissingPermission") // Permissions are requested by the UI before any of this runs.
class BleOtaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleOtaRepository {

    // Android's BluetoothGattCallback is itself callback-based (each method fires independently,
    // whenever the OS feels like it), but every public function below is `suspend` - so each
    // event type gets wrapped as one case of this sealed interface and funneled through the
    // `events` Channel just below, letting `awaitEvent<T>()` turn "wait for the next matching
    // callback" into an ordinary suspending call site instead of nested callback lambdas.
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

    // `inline` + `reified T`: normally a generic type parameter is erased at runtime (the JVM
    // wouldn't know what `T` was, so `event is T` couldn't compile) - marking the function `inline`
    // makes the compiler paste its body into every call site instead of compiling it once, and
    // that's specifically what `reified` needs to keep the real type available for the `is T`
    // check below. This is why callers can write the pleasant `awaitEvent<GattEvent.MtuChanged>()`
    // instead of passing a `Class<T>` token around by hand.
    /**
     * Drops any already-queued events before starting a *new* operation. Safe only because
     * [opMutex] guarantees one GATT operation is in flight at a time: anything sitting in
     * [events] when a new operation begins can only be a stale leftover from a *previous*
     * operation that gave up waiting (see the encrypted-characteristic timeout note on
     * [readLedConfig] / [writeLedConfig] below) - the real event for the operation we're about
     * to start hasn't been triggered yet, since we haven't issued its GATT call. Without this,
     * a late callback from an abandoned operation could be wrongly consumed by the next
     * `awaitEvent<T>()` call of the same event type, handing back someone else's result.
     */
    private fun drainStaleEvents() {
        while (events.tryReceive().isSuccess) {
            /* discarded - see KDoc above */
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

    // `callbackFlow {}` bridges an old-style callback API (ScanCallback) into a Flow: `trySend`
    // inside the callback pushes a value out to whoever is collecting, and `awaitClose {}` is the
    // required cleanup block that runs when the collector stops (cancelled, or the flow itself
    // closes) - here, that's where `scanner.stopScan(...)` lives, guaranteeing the BLE radio isn't
    // left scanning forever just because a caller like `startScan()`'s `.first()` moved on.
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
            drainStaleEvents()
            if (!g.readCharacteristic(ch)) {
                throw BleOtaException("Failed to initiate LED config read")
            }
            /*
             * This characteristic requires an encrypted link (see led_service.c /
             * BLE_GATT_CHR_F_READ_ENC) - the *first* time it's touched after connecting, this
             * read is what triggers Android's system pairing flow. That can easily take longer
             * than a plain unencrypted read: the user has to notice and tap a system "Pair with
             * esp32-ble-ota?" prompt, then LE Secure Connections' key exchange has to finish -
             * so this gets a generous timeout instead of the few-hundred-ms an ordinary read
             * would need, specifically to outlast a human, not just the radio.
             */
            val ev = awaitEvent<GattEvent.CharacteristicRead>(timeoutMs = 30_000)
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
            drainStaleEvents()
            if (!g.writeCharacteristicCompat(ch, config.toWireBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                throw BleOtaException("Failed to write LED config")
            }
            /* Same reasoning as readLedConfig()'s timeout above - this write can also be the
             * one that triggers pairing, if the read above hasn't already. */
            val ev = awaitEvent<GattEvent.CharacteristicWritten>(timeoutMs = 30_000)
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

    override fun performOtaUpdate(
        firmware: ByteArray,
        transport: OtaTransport,
    ): Flow<OtaTransferEvent> = flow {
        opMutex.withLock {
            val g = gatt ?: throw BleOtaException("Not connected")
            val ctrl = controlChar ?: throw BleOtaException("Not connected")

            // Discard any stale status byte left over from a previous run.
            while (statusNotifications.tryReceive().isSuccess) { /* drain */ }

            // START/END/REBOOT and the status they trigger always go over the tiny Control
            // characteristic regardless of transport - ATT overhead only matters for the bulk
            // data below, not for single command/status bytes.
            writeControlCommand(g, ctrl, BleOtaProtocol.CMD_START)
            val startStatus = withTimeout(5_000) { statusNotifications.receive() }
            if (startStatus != BleOtaProtocol.STATUS_IN_PROGRESS) {
                emit(OtaTransferEvent.Failure("Device did not acknowledge START (status=$startStatus)"))
                return@withLock
            }

            when (transport) {
                OtaTransport.GATT -> streamFirmwareViaGatt(g, firmware)
                OtaTransport.L2CAP_COC -> streamFirmwareViaL2cap(g.device, firmware)
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

    /**
     * Default transport: one GATT write per chunk, each an ATT request that waits for the
     * device's response before the next one goes out (see `writeDataChunk` below) - reliable,
     * always available, but every chunk pays a full protocol round trip.
     *
     * This is declared as an extension function on `FlowCollector<OtaTransferEvent>` (the type
     * the `flow { ... }` builder above gives its lambda) purely so it can call `emit(...)`
     * directly, the same as if this code were written inline in that lambda. Kotlin lets you
     * write a function "as if it were a member" of any type this way - here it means the
     * progress-reporting logic can live in its own well-named function instead of being nested
     * inline, without losing the ability to emit into the same flow.
     */
    private suspend fun FlowCollector<OtaTransferEvent>.streamFirmwareViaGatt(
        g: BluetoothGatt,
        firmware: ByteArray,
    ) {
        val data = dataChar ?: throw BleOtaException("Not connected")
        // The firmware's data characteristic buffer (OTA_DATA_MAX_CHUNK in ota_service.c) is a
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
    }

    /**
     * Educational fast path: opens a raw L2CAP CoC (Connection-Oriented Channel) socket straight
     * to the firmware's fixed PSM and streams the whole image through it as plain bytes - no ATT
     * framing, no per-chunk request/response. `BluetoothSocket.getOutputStream().write(...)` is a
     * *blocking* call here: it only returns once the underlying L2CAP credit-based flow control
     * allows more data out, which is why this loop needs no manual pacing or backpressure logic
     * of its own, unlike the GATT path above (which waits on an explicit ATT response per chunk)
     * or the heart-rate notification path (which needs its own buffering, see `observeHeartRate`).
     *
     * This is deliberately simple for learning purposes, not a hardened implementation - see the
     * README for what a production version would need to add (proper error recovery if the
     * socket drops mid-transfer, tuning the chunk size against the negotiated L2CAP MPS rather
     * than assuming a fixed value that must match the firmware exactly).
     */
    private suspend fun FlowCollector<OtaTransferEvent>.streamFirmwareViaL2cap(
        device: BluetoothDevice,
        firmware: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw BleOtaException("L2CAP CoC requires Android 10 (API 29) or newer")
        }

        // Opening the channel and connecting are both blocking calls - IO dispatcher, not Main.
        val socket = withContext(Dispatchers.IO) { device.createL2capChannel(OtaL2capProtocol.PSM) }
        try {
            withContext(Dispatchers.IO) { socket.connect() }

            val chunkSize = OtaL2capProtocol.CHUNK_SIZE
            var sent = 0
            var offset = 0
            while (offset < firmware.size) {
                val end = (offset + chunkSize).coerceAtMost(firmware.size)
                withContext(Dispatchers.IO) {
                    socket.outputStream.write(firmware, offset, end - offset)
                }
                sent += end - offset
                offset = end
                emit(OtaTransferEvent.Progress(sent, firmware.size))
            }
            withContext(Dispatchers.IO) { socket.outputStream.flush() }
        } finally {
            withContext(Dispatchers.IO) { runCatching { socket.close() } }
        }
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
