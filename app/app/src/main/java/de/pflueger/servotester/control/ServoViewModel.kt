package de.pflueger.servotester.control

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.pflueger.servotester.ble.BleManager
import de.pflueger.servotester.ble.ConnState
import de.pflueger.servotester.ble.MqttConfig
import de.pflueger.servotester.ble.OtaState
import de.pflueger.servotester.ble.ScannedDevice
import de.pflueger.servotester.ble.WifiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import de.pflueger.servotester.data.AppSettings
import de.pflueger.servotester.data.SettingsStore
import de.pflueger.servotester.update.ApkInstaller
import de.pflueger.servotester.update.UpdateRepository
import de.pflueger.servotester.update.UpdateState
import de.pflueger.servotester.usb.UsbControlLink
import de.pflueger.servotester.usb.UsbFlashState
import de.pflueger.servotester.usb.UsbFlasher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the UI needs to render, in one immutable snapshot. */
data class ServoUiState(
    val target: Int = ServoConstants.CENTER_US,   // where we are ramping to
    val display: Int = ServoConstants.CENTER_US,  // live, ramped value shown on the dial
    val outputOn: Boolean = false,
    val minLimit: Int = ServoConstants.HW_MIN_US,
    val maxLimit: Int = ServoConstants.HW_MAX_US,
    val presets: List<Int> = listOf(700, 1000, 2000, 2300),
    val conn: ConnState = ConnState.DISCONNECTED,
    val devices: List<ScannedDevice> = emptyList(),
    val remotePwm: Int? = null,
    val remoteOutputOn: Boolean? = null,
    val fwVersion: String? = null,
    val ota: OtaState = OtaState.Idle,
    val usbFlash: UsbFlashState = UsbFlashState.Idle,
    val usbConnected: Boolean = false,
    /** USB handshake: null = probing, true = firmware answered, false = silence. */
    val usbFwOk: Boolean? = null,
    val usbConnError: String? = null,
    val settings: AppSettings = AppSettings(),
    /** This app's own version name (for the update screen). */
    val appVersion: String = "",
    /** Online-update (GitHub releases) flow state. */
    val update: UpdateState = UpdateState.Idle,
)

/**
 * Orchestrates the servo control surface:
 *  - clamps every requested value into the user's software limits (§3.2),
 *  - sends it as the target to the ESP32 (which runs the authoritative ramp),
 *  - mirrors the ESP's reported "Ist" position back onto the dial, so the
 *    readout stays in lockstep with the real servo (no app-side second ramp).
 */
class ServoViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    /** Shared console log; every transport and the resync watchdog write here. */
    val debug = DebugLog()
    val debugEntries get() = debug.entries

    val ble = BleManager(app, debug)
    private val usbFlasher = UsbFlasher(app)
    private val usbLink = UsbControlLink(app, debug)
    private val updateRepo = UpdateRepository()
    private val apkInstaller = ApkInstaller(app)

    private val _ui = MutableStateFlow(ServoUiState())
    val ui: StateFlow<ServoUiState> = _ui.asStateFlow()

    // Ramping is done ONCE, authoritatively, in the firmware. The app only sends
    // the target and mirrors the position the ESP reports back — so the dial can
    // never run ahead of the real servo (no double-ramp desync). See moveTo() and
    // the remotePwm collector below.

    // Resync watchdog state: a discrete command (preset/center/toggle) is a single
    // packet; write-without-response can drop it silently and nothing re-sends it,
    // which strands the servo on the old target while the app thinks it moved. The
    // watchdog re-issues a command whose effect never showed up. Counters bound the
    // retries so a genuinely unreachable target can't loop forever.
    private var pwmResyncLeft = 0
    private var outResyncLeft = 0
    private var lastRemoteForResync: Int? = null
    private var stallChecks = 0

    init {
        // Our own version name, shown next to the "latest release" on the update screen.
        _ui.update {
            it.copy(appVersion = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
            }.getOrDefault(""))
        }
        // Persisted settings -> UI (limits, presets, mqtt config).
        viewModelScope.launch {
            store.settings.collect { s ->
                _ui.update {
                    it.copy(
                        settings = s,
                        minLimit = s.minLimit,
                        maxLimit = s.maxLimit,
                        presets = s.presets,
                    )
                }
            }
        }
        // BLE connection lifecycle. On a fresh link, push our ramp speed so the
        // firmware's authoritative ramp matches the slider (it persists its own,
        // but the app's setting wins).
        viewModelScope.launch {
            ble.state.collect { st ->
                _ui.update { it.copy(conn = st) }
                if (st == ConnState.CONNECTED) ble.writeRampSpan(_ui.value.settings.rampSpanMs)
            }
        }
        viewModelScope.launch {
            usbLink.connected.collect { c ->
                _ui.update { it.copy(usbConnected = c) }
                if (c) usbLink.writeRampSpan(_ui.value.settings.rampSpanMs)
            }
        }
        viewModelScope.launch { ble.devices.collect { d -> _ui.update { it.copy(devices = d) } } }
        // ESP-reported values from EITHER transport (BLE wins if both are up;
        // also covers MQTT-driven changes reported back by the firmware).
        viewModelScope.launch {
            combine(ble.remotePwm, usbLink.remotePwm) { b, u -> b ?: u }.collect { p ->
                _ui.update { st ->
                    val connected = st.conn == ConnState.CONNECTED || st.usbConnected
                    if (p == null || !connected) return@update st.copy(remotePwm = p)
                    // The dial ALWAYS shows the position the ESP reports — that's the
                    // real servo, so display can't outrun it. Adopt it as the target
                    // too only when we were settled (display==target): that means this
                    // change came from outside (connect sync / MQTT), not from a move
                    // we started — during our own move the target must stay the goal.
                    val wasSettled = st.display == st.target
                    st.copy(
                        remotePwm = p,
                        display = p,
                        target = if (wasSettled) p else st.target,
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(ble.remoteOutputOn, usbLink.remoteOutputOn) { b, u -> b ?: u }.collect { on ->
                _ui.update { it.copy(remoteOutputOn = on, outputOn = on ?: it.outputOn) }
            }
        }
        viewModelScope.launch {
            combine(ble.fwVersion, usbLink.fwVersion) { b, u -> b ?: u }
                .collect { v -> _ui.update { it.copy(fwVersion = v) } }
        }
        viewModelScope.launch { ble.ota.collect { o -> _ui.update { it.copy(ota = o) } } }
        viewModelScope.launch { usbFlasher.state.collect { u -> _ui.update { it.copy(usbFlash = u) } } }
        viewModelScope.launch { usbLink.fwResponded.collect { ok -> _ui.update { it.copy(usbFwOk = ok) } } }

        // Resync watchdog: catches a dropped discrete command by re-sending it once
        // its effect fails to appear. See the counters above for why this exists.
        viewModelScope.launch {
            while (true) {
                delay(RESYNC_POLL_MS)
                val st = _ui.value
                val connected = st.conn == ConnState.CONNECTED || st.usbConnected
                if (!connected) { pwmResyncLeft = 0; outResyncLeft = 0; lastRemoteForResync = null; continue }

                // Lost OUT toggle: the ESP reports an output state we didn't intend.
                val ro = st.remoteOutputOn
                if (ro != null && ro != st.outputOn && outResyncLeft > 0) {
                    outResyncLeft--
                    debug.info("resync", "Ausgang ${if (st.outputOn) "AN" else "AUS"} erneut senden (ESP: ${if (ro) "AN" else "AUS"})")
                    ble.writeState(st.outputOn); usbLink.writeState(st.outputOn)
                }

                // Lost PWM target: the servo is neither at the target nor moving
                // toward it (Ist unchanged across two polls) -> the target packet
                // was almost certainly dropped. Re-send it.
                val remote = st.remotePwm
                if (remote != null && remote != st.target) {
                    if (remote == lastRemoteForResync) {
                        stallChecks++
                        if (stallChecks >= 2 && pwmResyncLeft > 0) {
                            pwmResyncLeft--
                            stallChecks = 0
                            debug.info("resync", "Ziel ${st.target} erneut senden (Ist steht bei $remote)")
                            ble.writePwm(st.target, reliable = true); usbLink.writePwm(st.target)
                        }
                    } else stallChecks = 0
                } else stallChecks = 0
                lastRemoteForResync = remote
            }
        }
    }

    // ---- Value control ----------------------------------------------------

    /** Clamp any requested value to the active software limits. */
    private fun clampToLimits(value: Int): Int =
        value.coerceIn(_ui.value.minLimit, _ui.value.maxLimit)

    /**
     * Send [value] (clamped) as the new target. The firmware runs the ramp and
     * reports its live position back, which drives the dial — so the readout is
     * always in step with the actual servo, never ahead of it.
     *
     * When [arm] is set, a user-initiated move auto-enables the output if it was
     * off — pressing a preset or grabbing the jogdial should make the servo react,
     * not silently retarget a dead output. Internal re-clamps (e.g. after a limit
     * change) pass arm=false so they never start the servo on their own.
     */
    private fun moveTo(value: Int, arm: Boolean, stream: Boolean) {
        val v = clampToLimits(value)
        val armNow = arm && !_ui.value.outputOn
        val connected = _ui.value.conn == ConnState.CONNECTED || _ui.value.usbConnected
        _ui.update {
            it.copy(
                target = v,
                outputOn = if (armNow) true else it.outputOn,
                // Connected: the dial follows the ESP's reported position (below).
                // Offline: nothing reports back, so track the requested value.
                display = if (connected) it.display else v,
            )
        }
        if (armNow) { ble.writeState(true); usbLink.writeState(true); outResyncLeft = RESYNC_TRIES }
        // Discrete moves get the acked BLE write; the jogdial stream stays
        // write-without-response. Either way, arm the resync watchdog so a lost
        // command doesn't strand the servo.
        ble.writePwm(v, reliable = !stream)
        usbLink.writePwm(v)
        pwmResyncLeft = RESYNC_TRIES
        stallChecks = 0
    }

    /** Called continuously while the user drags the jogdial. */
    fun onJogTo(value: Int) = moveTo(value, arm = true, stream = true)

    /** Quick-select preset (also respects the limits). */
    fun onPreset(index: Int) {
        val raw = _ui.value.presets.getOrNull(index) ?: return
        moveTo(raw, arm = true, stream = false)
    }

    /** Center button -> 1500 µs (clamped into limits in case they exclude center). */
    fun onCenter() = moveTo(ServoConstants.CENTER_US, arm = true, stream = false)

    /** Ramp-speed slider: full-span time in ms. Pushed to the ESP (which ramps). */
    fun setRampSpan(ms: Int) {
        val v = ms.coerceIn(ServoConstants.RAMP_SPAN_MIN_MS, ServoConstants.RAMP_SPAN_MAX_MS)
        ble.writeRampSpan(v)
        usbLink.writeRampSpan(v)
        viewModelScope.launch { store.setRampSpan(v) }
    }

    fun toggleOutput() {
        val next = !_ui.value.outputOn
        _ui.update { it.copy(outputOn = next) }
        ble.writeState(next)
        usbLink.writeState(next)
        outResyncLeft = RESYNC_TRIES
    }

    // ---- Debug console ----------------------------------------------------

    fun setConsoleEnabled(on: Boolean) = viewModelScope.launch { store.setConsoleEnabled(on) }

    fun clearDebug() = debug.clear()

    // ---- Settings persistence --------------------------------------------

    fun saveLimits(min: Int, max: Int) = viewModelScope.launch {
        store.setLimits(min, max)
        // Re-clamp the current target into the new window immediately — but never
        // arm the output just because the limits changed.
        moveTo(_ui.value.target, arm = false, stream = false)
    }

    fun savePreset(index: Int, value: Int) = viewModelScope.launch { store.setPreset(index, value) }

    /** Long-press action: store the currently dialed value onto preset [index]. */
    fun assignPresetFromCurrent(index: Int) = savePreset(index, _ui.value.target)

    fun saveMqtt(config: MqttConfig) = viewModelScope.launch {
        store.setMqtt(config.enabled, config.host, config.port, config.user, config.pass)
        ble.writeMqtt(config)   // push to ESP right away if connected
    }

    fun sendWifi(config: WifiConfig) = ble.writeWifi(config)

    // ---- Firmware update (BLE-OTA) -----------------------------------------

    /** Reads the picked .bin and streams it to the ESP32. Progress lands in [ServoUiState.ota]. */
    fun startFirmwareUpdate(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        // null/empty falls through to startOta's validation -> visible error state
        ble.startOta(bytes ?: ByteArray(0))
    }

    fun dismissOta() = ble.resetOta()

    // ---- Online update (GitHub releases) ----------------------------------

    /** Turn raw network exceptions into something a human can act on. */
    private fun netErrorMessage(e: Throwable): String = when {
        e is java.net.UnknownHostException ||
            e.message?.contains("resolve host", ignoreCase = true) == true ->
            "Keine Internetverbindung erreichbar (DNS). Bitte WLAN/Mobilfunk prüfen — " +
                "im Heimnetz ggf. Pi-hole/Firewall."
        e is java.net.SocketTimeoutException ->
            "Zeitüberschreitung — Server nicht erreichbar. Internetverbindung prüfen."
        e is java.net.ConnectException || e is java.io.IOException ->
            "Keine Verbindung zum Server. Internetverbindung prüfen."
        else -> e.message ?: "Unbekannter Fehler."
    }

    /** Query the repo for the latest published release. */
    fun checkForUpdates() = viewModelScope.launch {
        _ui.update { it.copy(update = UpdateState.Checking) }
        runCatching { updateRepo.fetchLatest() }
            .onSuccess { rel -> _ui.update { it.copy(update = UpdateState.Latest(rel)) } }
            .onFailure { e -> _ui.update { it.copy(update = UpdateState.Error(netErrorMessage(e))) } }
    }

    /** Download the release firmware .bin and flash it over the live transport. */
    fun updateFirmwareOnline() = viewModelScope.launch(Dispatchers.IO) {
        val rel = (_ui.value.update as? UpdateState.Latest)?.release ?: return@launch
        val asset = rel.firmware ?: run {
            _ui.update { it.copy(update = UpdateState.Error("Dieses Release enthält keine Firmware-.bin.")) }
            return@launch
        }
        val viaBle = _ui.value.conn == ConnState.CONNECTED
        val viaUsb = _ui.value.usbConnected
        if (!viaBle && !viaUsb) {
            _ui.update { it.copy(update = UpdateState.Error("Erst per BLE oder USB verbinden, dann Firmware aktualisieren.")) }
            return@launch
        }
        val bytes = runCatching {
            updateRepo.download(asset.url) { pct ->
                _ui.update { it.copy(update = UpdateState.Downloading("Firmware", pct)) }
            }
        }.getOrElse { e ->
            _ui.update { it.copy(update = UpdateState.Error(netErrorMessage(e))) }
            return@launch
        }
        // Reset the update banner; the existing flash flow (ota / usbFlash) now
        // drives its own progress UI. BLE-OTA is preferred when both are up.
        _ui.update { it.copy(update = UpdateState.Latest(rel)) }
        if (viaBle) {
            ble.startOta(bytes)
        } else {
            usbLink.disconnect()   // the flasher needs the port for itself
            usbFlasher.flash(bytes)
        }
    }

    /** Download the release APK and hand it to the system installer. */
    fun updateAppOnline() = viewModelScope.launch(Dispatchers.IO) {
        val rel = (_ui.value.update as? UpdateState.Latest)?.release ?: return@launch
        val asset = rel.apk ?: run {
            _ui.update { it.copy(update = UpdateState.Error("Dieses Release enthält keine App-APK.")) }
            return@launch
        }
        val bytes = runCatching {
            updateRepo.download(asset.url) { pct ->
                _ui.update { it.copy(update = UpdateState.Downloading("App", pct)) }
            }
        }.getOrElse { e ->
            _ui.update { it.copy(update = UpdateState.Error(netErrorMessage(e))) }
            return@launch
        }
        val err = apkInstaller.install(bytes)
        _ui.update {
            it.copy(update = if (err == null)
                UpdateState.Notice("Installer gestartet — bitte im System-Dialog bestätigen.")
            else UpdateState.Error(err))
        }
    }

    fun dismissUpdate() = _ui.update { it.copy(update = UpdateState.Idle) }

    // ---- Firmware flashing over USB-C (works on a factory-fresh ESP32) -----

    fun startUsbFlash(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        usbLink.disconnect()   // the flasher needs the port for itself
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        // null/empty falls through to planWrites' validation -> visible error state
        usbFlasher.flash(bytes ?: ByteArray(0))
    }

    fun dismissUsbFlash() = usbFlasher.reset()

    // ---- Connection actions ----------------------------------------------

    fun startScan() = ble.startScan()
    fun stopScan() = ble.stopScan()

    /** Wired control over the same USB-C cable used for flashing. */
    fun connectUsb() = viewModelScope.launch {
        _ui.update { it.copy(usbConnError = null) }
        val err = usbLink.connect(viewModelScope)
        _ui.update { it.copy(usbConnError = err) }
    }

    fun disconnectUsb() = usbLink.disconnect()

    fun connect(device: ScannedDevice) = viewModelScope.launch {
        store.setLastDevice(device.address, device.name)
        ble.connect(device.address)
    }

    fun disconnect() = ble.disconnect()

    override fun onCleared() {
        ble.disconnect()
        usbLink.disconnect()
        super.onCleared()
    }

    private companion object {
        /** Watchdog poll interval; two stable polls (~700 ms) confirm a stall. */
        const val RESYNC_POLL_MS = 350L
        /** Max automatic re-sends of one dropped command before giving up. */
        const val RESYNC_TRIES = 3
    }
}
