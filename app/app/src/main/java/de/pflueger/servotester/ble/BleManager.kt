package de.pflueger.servotester.ble

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/** Discovered advertising device. */
data class ScannedDevice(val name: String, val address: String, val rssi: Int)

/** High-level connection lifecycle exposed to the UI. */
enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, CONNECTED, FAILED }

/** Firmware-update lifecycle exposed to the UI. */
sealed class OtaState {
    data object Idle : OtaState()
    data object Preparing : OtaState()
    data class Uploading(val sent: Int, val total: Int) : OtaState()
    data object Verifying : OtaState()
    data object Success : OtaState()
    data class Error(val message: String) : OtaState()
}

/**
 * Thin, self-contained BLE GATT client for the ESP32-C3 servo tester.
 *
 * Design notes:
 *  - Fast PWM stream uses WRITE_TYPE_NO_RESPONSE so the ramp (a value every
 *    ~20 ms) never backs up behind ACKs. If the stack is momentarily busy we
 *    simply drop the frame — the next ramp tick carries a fresher value anyway.
 *  - State / WiFi / MQTT use reliable writes (WRITE_TYPE_DEFAULT).
 *  - PWM + STATE notifications are enabled so the phone reflects the ESP's
 *    real ("Ist") value, including changes driven by MQTT.
 *  - Firmware update (BLE-OTA): chunks go out as write-without-response,
 *    paced by onCharacteristicWrite (fires when the stack can take the next
 *    one). Control handshake + MD5 verify via the OTA_CTRL characteristic;
 *    protocol documented in ServoUuids.kt / firmware ServoTester.ino.
 *
 * Callers MUST hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT (API 31+) before use;
 * that is the Activity's job, hence @SuppressLint("MissingPermission") here.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val btManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    /** Latest PWM value reported by the ESP32 (null until first notification). */
    private val _remotePwm = MutableStateFlow<Int?>(null)
    val remotePwm: StateFlow<Int?> = _remotePwm.asStateFlow()

    /** Latest output ON/OFF reported by the ESP32. */
    private val _remoteOutputOn = MutableStateFlow<Boolean?>(null)
    val remoteOutputOn: StateFlow<Boolean?> = _remoteOutputOn.asStateFlow()

    /** Firmware version string read from the ESP32 (null until read / if unsupported). */
    private val _fwVersion = MutableStateFlow<String?>(null)
    val fwVersion: StateFlow<String?> = _fwVersion.asStateFlow()

    /** Firmware-update progress. */
    private val _ota = MutableStateFlow<OtaState>(OtaState.Idle)
    val ota: StateFlow<OtaState> = _ota.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var pwmChar: BluetoothGattCharacteristic? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var wifiChar: BluetoothGattCharacteristic? = null
    private var mqttChar: BluetoothGattCharacteristic? = null
    private var versionChar: BluetoothGattCharacteristic? = null
    private var rateChar: BluetoothGattCharacteristic? = null
    private var otaCtrlChar: BluetoothGattCharacteristic? = null
    private var otaDataChar: BluetoothGattCharacteristic? = null

    /** Negotiated ATT MTU; payload per write = MTU - 3 (capped at 512 by spec). */
    private var mtu = 23

    // OTA plumbing: write-completion acks + control notifications, consumed
    // by the sequential coroutine in startOta().
    private val otaWriteAck = Channel<Boolean>(Channel.CONFLATED)
    private val otaCtrlEvents = Channel<ByteArray>(Channel.BUFFERED)

    private var scanning = false
    private val scanner get() = adapter?.bluetoothLeScanner

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ---- Scanning ---------------------------------------------------------

    fun startScan() {
        val s = scanner ?: return
        if (scanning) return
        _devices.value = emptyList()
        scanning = true
        _state.value = ConnState.SCANNING
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(ServoUuids.SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        s.startScan(filters, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        scanner?.stopScan(scanCallback)
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.DISCONNECTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: "ServoTester"
            val entry = ScannedDevice(name, dev.address, result.rssi)
            _devices.value = (_devices.value.filterNot { it.address == entry.address } + entry)
                .sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = ConnState.FAILED
        }
    }

    // ---- Connection -------------------------------------------------------

    fun connect(address: String) {
        stopScan()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: run {
            _state.value = ConnState.FAILED; return
        }
        _state.value = ConnState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clearChars()
        _remotePwm.value = null
        _remoteOutputOn.value = null
        _fwVersion.value = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun clearChars() {
        pwmChar = null; stateChar = null; wifiChar = null; mqttChar = null
        versionChar = null; rateChar = null; otaCtrlChar = null; otaDataChar = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnState.DISCOVERING
                    g.requestMtu(517)   // max ATT MTU: JSON configs + fast OTA chunks
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearChars()
                    _remotePwm.value = null
                    _remoteOutputOn.value = null
                    _fwVersion.value = null
                    _state.value = ConnState.DISCONNECTED
                    // A drop mid-upload fails the OTA; the deliberate reboot
                    // after Success also lands here and must NOT be an error.
                    when (_ota.value) {
                        is OtaState.Preparing, is OtaState.Uploading, is OtaState.Verifying ->
                            otaWriteAck.trySend(false)
                        else -> Unit
                    }
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(ServoUuids.SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                _state.value = ConnState.FAILED
                return
            }
            pwmChar = svc.getCharacteristic(ServoUuids.PWM)
            stateChar = svc.getCharacteristic(ServoUuids.STATE)
            wifiChar = svc.getCharacteristic(ServoUuids.WIFI)
            mqttChar = svc.getCharacteristic(ServoUuids.MQTT)
            versionChar = svc.getCharacteristic(ServoUuids.VERSION)
            rateChar = svc.getCharacteristic(ServoUuids.RATE)
            otaCtrlChar = svc.getCharacteristic(ServoUuids.OTA_CTRL)
            otaDataChar = svc.getCharacteristic(ServoUuids.OTA_DATA)
            _state.value = ConnState.CONNECTED

            // Enable notifications sequentially (STATE -> PWM -> OTA_CTRL ->
            // read VERSION), chained through the descriptor-write callback,
            // since only one GATT op may be in flight.
            stateChar?.let { enableNotifications(g, it) }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            when (descriptor.characteristic.uuid) {
                ServoUuids.STATE -> pwmChar?.let { enableNotifications(g, it) }
                ServoUuids.PWM ->
                    otaCtrlChar?.let { enableNotifications(g, it) } ?: readVersion(g)
                ServoUuids.OTA_CTRL -> readVersion(g)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            handleRead(ch.uuid, value, status)
            advanceInitialRead(g, ch.uuid)
        }

        @Deprecated("Pre-API 33 signature; delegates to the byte[] overload.")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            @Suppress("DEPRECATION")
            handleRead(ch.uuid, ch.value ?: ByteArray(0), status)
            advanceInitialRead(g, ch.uuid)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            // Paces the OTA chunk stream: one ack per completed write.
            if (ch.uuid == ServoUuids.OTA_DATA) {
                otaWriteAck.trySend(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) = handleNotification(ch.uuid, value)

        @Deprecated("Pre-API 33 signature; delegates to the byte[] overload.")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotification(ch.uuid, ch.value ?: return)
        }
    }

    private fun readVersion(g: BluetoothGatt) {
        versionChar?.let { g.readCharacteristic(it) }
    }

    /**
     * After the version read, pull the firmware's CURRENT position and output
     * state once (VERSION -> STATE -> PWM). Notifications only fire on *change*,
     * so a servo sitting still at connect would otherwise never tell us where it
     * is — leaving the ramp base and output toggle on stale defaults until the
     * user pokes something. Reading here syncs both immediately.
     */
    private fun advanceInitialRead(g: BluetoothGatt, justRead: java.util.UUID) {
        when (justRead) {
            ServoUuids.VERSION -> (stateChar ?: pwmChar)?.let { g.readCharacteristic(it) }
            ServoUuids.STATE -> pwmChar?.let { g.readCharacteristic(it) }
        }
    }

    private fun handleRead(uuid: java.util.UUID, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        when (uuid) {
            ServoUuids.STATE -> { if (value.isNotEmpty()) _remoteOutputOn.value = value[0].toInt() != 0; return }
            ServoUuids.PWM -> { if (value.size >= 2) _remotePwm.value = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8); return }
            else -> {}
        }
        if (uuid == ServoUuids.VERSION) {
            _fwVersion.value = value.toString(Charsets.UTF_8).trimEnd(' ')
        }
    }

    private fun handleNotification(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            ServoUuids.PWM -> if (value.size >= 2) {
                _remotePwm.value = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
            }
            ServoUuids.STATE -> if (value.isNotEmpty()) {
                _remoteOutputOn.value = value[0].toInt() != 0
            }
            ServoUuids.OTA_CTRL -> otaCtrlEvents.trySend(value)
        }
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(ServoUuids.CCCD) ?: return
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // ---- Writes -----------------------------------------------------------

    /** Fast, fire-and-forget PWM target (uint16 LE). Dropped silently if busy. */
    fun writePwm(valueUs: Int) {
        if (otaBusy()) return   // don't interleave with the firmware upload
        val g = gatt ?: return
        val ch = pwmChar ?: return
        val v = valueUs and 0xFFFF
        val bytes = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        writeChar(g, ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    /** Reliable output ON/OFF (uint8). */
    fun writeState(on: Boolean) {
        val g = gatt ?: return
        val ch = stateChar ?: return
        writeChar(g, ch, byteArrayOf(if (on) 1 else 0), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /** Reliable ramp-speed = full-span time in ms (uint16 LE). No-op on old firmware. */
    fun writeRampSpan(spanMs: Int) {
        val g = gatt ?: return
        val ch = rateChar ?: return
        val v = spanMs and 0xFFFF
        writeChar(g, ch, byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun writeWifi(config: WifiConfig) {
        val g = gatt ?: return
        val ch = wifiChar ?: return
        writeChar(g, ch, config.toJsonBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun writeMqtt(config: MqttConfig) {
        val g = gatt ?: return
        val ch = mqttChar ?: return
        writeChar(g, ch, config.toJsonBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /** @return true if the stack accepted the write (false = busy / error). */
    private fun writeChar(
        g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = writeType
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    // ---- Firmware update (BLE-OTA) ----------------------------------------

    private fun otaBusy(): Boolean = when (_ota.value) {
        is OtaState.Preparing, is OtaState.Uploading, is OtaState.Verifying -> true
        else -> false
    }

    /** Reset a finished/failed update back to Idle (UI dismiss). */
    fun resetOta() {
        if (!otaBusy()) _ota.value = OtaState.Idle
    }

    /**
     * Push a firmware image to the ESP32. Sequential suspend flow:
     * begin(size+md5) -> READY -> chunks (write-without-response, paced by
     * write acks) -> end -> SUCCESS (ESP verifies MD5, reboots, link drops).
     */
    suspend fun startOta(firmware: ByteArray) {
        if (otaBusy()) return
        val g = gatt
        val ctrl = otaCtrlChar
        val data = otaDataChar
        if (g == null || _state.value != ConnState.CONNECTED) {
            _ota.value = OtaState.Error("Nicht verbunden."); return
        }
        if (ctrl == null || data == null) {
            _ota.value = OtaState.Error("Diese ESP32-Firmware kann noch kein OTA-Update (einmalig per USB flashen)."); return
        }
        // Sanity: ESP32 app images start with magic 0xE9; the 4-MB merged.bin
        // (initial USB flash) is far bigger than the 1.9-MB OTA partition.
        if (firmware.isEmpty() || (firmware[0].toInt() and 0xFF) != 0xE9 || firmware.size > 1_966_080) {
            _ota.value = OtaState.Error("Keine gültige OTA-Datei — „ServoTester.ino.bin“ wählen (nicht merged.bin)."); return
        }

        _ota.value = OtaState.Preparing
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            drainOtaChannels()

            // begin: [0x01][size u32 LE][md5 hex, 32 ascii]
            val md5 = MessageDigest.getInstance("MD5").digest(firmware)
                .joinToString("") { "%02x".format(it) }
            val begin = ByteArray(37)
            begin[0] = 0x01
            for (i in 0..3) begin[1 + i] = (firmware.size shr (8 * i)).toByte()
            md5.toByteArray(Charsets.US_ASCII).copyInto(begin, 5)
            writeReliably(g, ctrl, begin)
            awaitOtaStatus(0x01, timeoutMs = 15_000)   // READY

            // chunk stream, one in flight, paced by onCharacteristicWrite
            val chunkSize = (mtu - 3).coerceAtMost(512)
            var sent = 0
            while (sent < firmware.size) {
                val end = minOf(sent + chunkSize, firmware.size)
                writeReliably(g, data, firmware.copyOfRange(sent, end),
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                withTimeout(5_000) {
                    if (!otaWriteAck.receive()) error("BLE-Schreibfehler bei Byte $sent.")
                }
                sent = end
                _ota.value = OtaState.Uploading(sent, firmware.size)
            }

            // end/commit: ESP verifies MD5, swaps boot partition, reboots
            _ota.value = OtaState.Verifying
            writeReliably(g, ctrl, byteArrayOf(0x02))
            awaitOtaStatus(0x02, timeoutMs = 30_000)   // SUCCESS
            _ota.value = OtaState.Success
        } catch (e: Exception) {
            // Best effort: tell the ESP to abort so it frees the OTA session.
            runCatching { writeChar(g, ctrl, byteArrayOf(0x03), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
            _ota.value = OtaState.Error(e.message ?: "Update fehlgeschlagen.")
        }
    }

    private fun drainOtaChannels() {
        while (otaWriteAck.tryReceive().isSuccess) Unit
        while (otaCtrlEvents.tryReceive().isSuccess) Unit
    }

    /** Retries busy writes briefly instead of dropping (OTA must not lose chunks). */
    private suspend fun writeReliably(
        g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ) {
        repeat(200) {
            if (_state.value != ConnState.CONNECTED) error("Verbindung getrennt.")
            if (writeChar(g, ch, bytes, writeType)) return
            delay(10)
        }
        error("BLE-Stack nimmt keine Daten an.")
    }

    /** Waits for an OTA_CTRL notification with the wanted status byte. */
    private suspend fun awaitOtaStatus(wanted: Int, timeoutMs: Long) {
        withTimeout(timeoutMs) {
            while (true) {
                val ev = otaCtrlEvents.receive()
                if (ev.isEmpty()) continue
                when (val st = ev[0].toInt() and 0xFF) {
                    wanted -> return@withTimeout
                    0xEE -> error("ESP meldet Fehler (Code ${if (ev.size >= 6) ev[5].toInt() and 0xFF else -1}).")
                    0x04 -> error("Update abgebrochen.")
                    else -> continue   // stray progress/status, keep waiting
                }
            }
        }
    }
}
