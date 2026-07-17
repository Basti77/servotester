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
 *  - routes it through the [RampController] (Smooth Transit),
 *  - streams each ramped value to the ESP32 over BLE,
 *  - mirrors the ESP's reported "Ist" values back into the UI.
 */
class ServoViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    val ble = BleManager(app)
    private val usbFlasher = UsbFlasher(app)
    private val usbLink = UsbControlLink(app)
    private val updateRepo = UpdateRepository()
    private val apkInstaller = ApkInstaller(app)

    private val _ui = MutableStateFlow(ServoUiState())
    val ui: StateFlow<ServoUiState> = _ui.asStateFlow()

    // The ramp pushes each intermediate value here: update the dial AND the
    // ESP — over whichever transport is up (each no-ops when disconnected).
    private val ramp = RampController(viewModelScope) { value ->
        _ui.update { it.copy(display = value) }
        ble.writePwm(value)
        usbLink.writePwm(value)
    }

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
                // Keep the app-side ramp in step with the persisted speed.
                ramp.ratePerMs = ServoConstants.rateForSpan(s.rampSpanMs)
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
                _ui.update { it.copy(remotePwm = p) }
                // Align the ramp base to what the firmware reports, so we don't ramp
                // from a stale assumed position (first sync after connecting, or an
                // external MQTT-driven change). BUT: while our own ramp is running,
                // these notifies are just echoes of the values we just wrote — adopting
                // them would cancel the ramp mid-flight and strand the servo partway.
                if (p != null && !ramp.isRamping &&
                    (_ui.value.conn == ConnState.CONNECTED || _ui.value.usbConnected)) {
                    ramp.syncTo(p)
                    // Reflect the true position on the dial too, so it doesn't sit
                    // on a stale default until the user drags it.
                    _ui.update { it.copy(target = p, display = p) }
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
    }

    // ---- Value control ----------------------------------------------------

    /** Clamp any requested value to the active software limits. */
    private fun clampToLimits(value: Int): Int =
        value.coerceIn(_ui.value.minLimit, _ui.value.maxLimit)

    /**
     * Move the servo to [value] (clamped to limits) and ramp there.
     *
     * When [arm] is set, a user-initiated move auto-enables the output if it was
     * off — pressing a preset or grabbing the jogdial should make the servo react,
     * not silently retarget a dead output. Internal re-clamps (e.g. after a limit
     * change) pass arm=false so they never start the servo on their own.
     */
    private fun moveTo(value: Int, arm: Boolean) {
        val v = clampToLimits(value)
        if (arm && !_ui.value.outputOn) {
            _ui.update { it.copy(target = v, outputOn = true) }
            ble.writeState(true)
            usbLink.writeState(true)
        } else {
            _ui.update { it.copy(target = v) }
        }
        ramp.setTarget(v)
    }

    /** Called continuously while the user drags the jogdial. */
    fun onJogTo(value: Int) = moveTo(value, arm = true)

    /** Quick-select preset (also respects the limits). */
    fun onPreset(index: Int) {
        val raw = _ui.value.presets.getOrNull(index) ?: return
        moveTo(raw, arm = true)
    }

    /** Center button -> 1500 µs (clamped into limits in case they exclude center). */
    fun onCenter() = moveTo(ServoConstants.CENTER_US, arm = true)

    /** Ramp-speed slider: full-span time in ms. Applied locally + pushed to the ESP. */
    fun setRampSpan(ms: Int) {
        val v = ms.coerceIn(ServoConstants.RAMP_SPAN_MIN_MS, ServoConstants.RAMP_SPAN_MAX_MS)
        ramp.ratePerMs = ServoConstants.rateForSpan(v)
        ble.writeRampSpan(v)
        usbLink.writeRampSpan(v)
        viewModelScope.launch { store.setRampSpan(v) }
    }

    fun toggleOutput() {
        val next = !_ui.value.outputOn
        _ui.update { it.copy(outputOn = next) }
        ble.writeState(next)
        usbLink.writeState(next)
    }

    // ---- Settings persistence --------------------------------------------

    fun saveLimits(min: Int, max: Int) = viewModelScope.launch {
        store.setLimits(min, max)
        // Re-clamp the current target into the new window immediately — but never
        // arm the output just because the limits changed.
        moveTo(_ui.value.target, arm = false)
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

    /** Query the repo for the latest published release. */
    fun checkForUpdates() = viewModelScope.launch {
        _ui.update { it.copy(update = UpdateState.Checking) }
        runCatching { updateRepo.fetchLatest() }
            .onSuccess { rel -> _ui.update { it.copy(update = UpdateState.Latest(rel)) } }
            .onFailure { e -> _ui.update { it.copy(update = UpdateState.Error(e.message ?: "Update-Prüfung fehlgeschlagen.")) } }
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
            _ui.update { it.copy(update = UpdateState.Error(e.message ?: "Firmware-Download fehlgeschlagen.")) }
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
            _ui.update { it.copy(update = UpdateState.Error(e.message ?: "App-Download fehlgeschlagen.")) }
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
        ramp.stop()
        ble.disconnect()
        usbLink.disconnect()
        super.onCleared()
    }
}
