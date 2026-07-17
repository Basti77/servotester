package de.pflueger.servotester.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Control channel over the USB-C cable — the wired sibling of [de.pflueger.servotester.ble.BleManager].
 *
 * Talks the firmware's newline-delimited text protocol on the ESP32-C3's
 * USB-Serial/JTAG port:
 *   app -> ESP:  "PWM <us>", "OUT <0|1>", "GET"
 *   ESP -> app:  "PWM <us>", "OUT <0|1>", "FW <version>"
 * Unknown lines (firmware log output shares the port) are ignored.
 *
 * Writes go through a small drop-oldest queue drained by a writer coroutine,
 * mirroring the BLE manager's "a stale ramp value is worthless" philosophy —
 * the next 20-ms tick brings a fresher one anyway.
 */
class UsbControlLink(private val context: Context) {

    private val usb = UsbAccess.manager(context)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _remotePwm = MutableStateFlow<Int?>(null)
    val remotePwm: StateFlow<Int?> = _remotePwm.asStateFlow()

    private val _remoteOutputOn = MutableStateFlow<Boolean?>(null)
    val remoteOutputOn: StateFlow<Boolean?> = _remoteOutputOn.asStateFlow()

    private val _fwVersion = MutableStateFlow<String?>(null)
    val fwVersion: StateFlow<String?> = _fwVersion.asStateFlow()

    /**
     * Handshake result: an open port only proves a chip is plugged in, NOT
     * that our firmware is alive. null = still probing, true = firmware
     * answered GET with its version, false = no answer (firmware too old,
     * not running, or the chip sits in the bootloader).
     */
    private val _fwResponded = MutableStateFlow<Boolean?>(null)
    val fwResponded: StateFlow<Boolean?> = _fwResponded.asStateFlow()

    private var port: CdcAcmPort? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var handshakeJob: Job? = null
    private var detachReceiver: BroadcastReceiver? = null

    private val txQueue = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** @return null on success, or a user-displayable error message. */
    suspend fun connect(scope: CoroutineScope): String? = withContext(Dispatchers.IO) {
        if (_connected.value) return@withContext null
        val device = UsbAccess.findEsp(usb)
            ?: return@withContext "Kein ESP32 am USB-Port gefunden — OTG-Kabel eingesteckt?"
        if (!UsbAccess.requestPermission(context, usb, device)) {
            return@withContext "USB-Zugriff wurde abgelehnt."
        }
        val p = try {
            val conn = usb.openDevice(device) ?: error("USB-Gerät ließ sich nicht öffnen.")
            CdcAcmPort(device, conn).also { it.open() }
        } catch (e: Exception) {
            return@withContext e.message ?: "USB-Verbindung fehlgeschlagen."
        }
        // Assert DTR like a serial monitor so the firmware's `if (Serial)`
        // host-connected check passes. Plain DTR does NOT trigger the
        // bootloader reset — that needs the full esptool sequence.
        p.setDtrRts(dtr = true, rts = false)
        port = p
        _connected.value = true

        readJob = scope.launch(Dispatchers.IO) { readLoop(p) }
        writeJob = scope.launch(Dispatchers.IO) {
            for (line in txQueue) {
                if (!_connected.value) break
                runCatching { p.write((line + "\n").toByteArray(Charsets.US_ASCII), 500) }
                    .onFailure { disconnect() }
            }
        }
        // Unplugging the cable must tear the link down cleanly.
        detachReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) disconnect()
            }
        }.also {
            ContextCompat.registerReceiver(
                context, it, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_EXPORTED,   // system broadcast
            )
        }
        // Handshake: keep asking until the firmware identifies itself. GET is
        // idempotent, so retrying is harmless; ~4 s covers a boot in progress.
        _fwResponded.value = null
        handshakeJob = scope.launch(Dispatchers.IO) {
            repeat(4) {
                send("GET")
                val v = withTimeoutOrNull(1_000) { fwVersion.first { it != null } }
                if (v != null) {
                    _fwResponded.value = true
                    return@launch
                }
            }
            _fwResponded.value = false
        }
        null
    }

    fun disconnect() {
        if (!_connected.value && port == null) return
        _connected.value = false
        readJob?.cancel(); readJob = null
        writeJob?.cancel(); writeJob = null
        handshakeJob?.cancel(); handshakeJob = null
        _fwResponded.value = null
        detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        detachReceiver = null
        port?.close(); port = null
        _remotePwm.value = null
        _remoteOutputOn.value = null
        _fwVersion.value = null
    }

    // ---- Commands ----------------------------------------------------------

    fun writePwm(valueUs: Int) { if (_connected.value) send("PWM $valueUs") }

    fun writeState(on: Boolean) { if (_connected.value) send("OUT ${if (on) 1 else 0}") }

    fun writeRampSpan(spanMs: Int) { if (_connected.value) send("RATE $spanMs") }

    private fun send(line: String) { txQueue.trySend(line) }

    // ---- Incoming lines -----------------------------------------------------

    private fun readLoop(p: CdcAcmPort) {
        val buf = ByteArray(1024)
        val line = StringBuilder()
        while (_connected.value && readJob?.isActive != false) {
            val n = try { p.read(buf, 200) } catch (_: Exception) { disconnect(); return }
            for (i in 0 until n) {
                when (val c = buf[i].toInt().toChar()) {
                    '\n', '\r' -> {
                        if (line.isNotEmpty()) handleLine(line.toString())
                        line.setLength(0)
                    }
                    else -> {
                        line.append(c)
                        if (line.length > 128) line.setLength(0)   // log garbage
                    }
                }
            }
        }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("PWM ") -> line.substring(4).trim().toIntOrNull()?.let {
                _remotePwm.value = it
            }
            line.startsWith("OUT ") -> _remoteOutputOn.value = line.substring(4).trim() == "1"
            line.startsWith("FW ") -> _fwVersion.value = line.substring(3).trim()
        }
    }
}
