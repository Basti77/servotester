package de.pflueger.servotester.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.pflueger.servotester.ble.ConnState
import de.pflueger.servotester.ble.MqttConfig
import de.pflueger.servotester.ble.OtaState
import de.pflueger.servotester.usb.UsbFlashState
import de.pflueger.servotester.ble.ScannedDevice
import de.pflueger.servotester.ble.WifiConfig
import de.pflueger.servotester.control.ServoConstants
import de.pflueger.servotester.control.ServoUiState
import de.pflueger.servotester.control.ServoViewModel
import de.pflueger.servotester.update.UpdateState

/** Permissions needed to scan/connect, dependent on API level. */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(vm: ServoViewModel, ui: ServoUiState) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) vm.startScan()
    }
    fun requestScan() {
        val perms = requiredBlePermissions()
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) vm.startScan() else permLauncher.launch(perms)
    }

    ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(20.dp),
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            ConnectionSection(vm, ui, onScan = ::requestScan)
            SectionDivider()
            LimitsSection(vm, ui)
            SectionDivider()
            PresetsSection(vm, ui)
            SectionDivider()
            WifiSection(vm)
            SectionDivider()
            MqttSection(vm, ui)
            SectionDivider()
            FirmwareSection(vm, ui)
            SectionDivider()
            UpdateSection(vm, ui)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    Divider()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

// ---- Connection -----------------------------------------------------------

@Composable
private fun ConnectionSection(vm: ServoViewModel, ui: ServoUiState, onScan: () -> Unit) {
    SectionTitle("Verbindung")

    // Bluetooth (wireless, the everyday path)
    Text("Bluetooth", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.conn == ConnState.SCANNING) {
            OutlinedButton(onClick = vm::stopScan) { Text("Stop") }
        } else {
            Button(onClick = onScan) { Text("Suchen") }
        }
        if (ui.conn == ConnState.CONNECTED || ui.conn == ConnState.CONNECTING || ui.conn == ConnState.DISCOVERING) {
            OutlinedButton(onClick = vm::disconnect) { Text("Trennen") }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (ui.conn == ConnState.CONNECTED) {
        Text(
            "✓ Verbunden — Firmware ${ui.fwVersion ?: "…"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
    }
    if (ui.devices.isEmpty()) {
        if (ui.conn != ConnState.CONNECTED) Text(
            "Keine Geräte gefunden. ESP32 einschalten und „Suchen“ drücken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ui.devices.forEach { dev -> DeviceRow(dev) { vm.connect(dev) } }
    }

    // USB cable (works without any pairing — e.g. right after flashing)
    Spacer(Modifier.height(12.dp))
    Text("USB-Kabel", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ui.usbConnected) {
            OutlinedButton(onClick = vm::disconnectUsb) { Text("Trennen") }
        } else {
            Button(onClick = vm::connectUsb) { Text("USB verbinden") }
        }
    }
    Spacer(Modifier.height(4.dp))
    val usbErr = ui.usbConnError
    when {
        usbErr != null -> Text(usbErr, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        // Handshake result — an open port alone proves nothing.
        ui.usbConnected && ui.usbFwOk == true -> Text(
            "✓ Firmware ${ui.fwVersion ?: "?"} antwortet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        ui.usbConnected && ui.usbFwOk == null -> Text(
            "Port offen — warte auf Antwort der Firmware …",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.usbConnected -> Text(
            "ESP32 hängt am Kabel, aber die Firmware antwortet nicht. Vermutlich " +
                "zu alt (USB-Steuerung braucht ≥ 1.1.0) oder sie läuft nicht — " +
                "unten per USB die aktuelle Firmware flashen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> Text(
            "Steuert den Servotester direkt über das USB-C/OTG-Kabel (Firmware ab 1.1.0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(dev: ScannedDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dev.name, fontWeight = FontWeight.Medium)
            Text(dev.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${dev.rssi} dBm", style = MaterialTheme.typography.labelSmall)
    }
}

// ---- Limits ---------------------------------------------------------------

@Composable
private fun LimitsSection(vm: ServoViewModel, ui: ServoUiState) {
    SectionTitle("Servo-Limits (µs)")
    var minText by remember(ui.minLimit) { mutableStateOf(ui.minLimit.toString()) }
    var maxText by remember(ui.maxLimit) { mutableStateOf(ui.maxLimit.toString()) }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = minText,
            onValueChange = { minText = it.filter(Char::isDigit) },
            label = { Text("Min") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = { maxText = it.filter(Char::isDigit) },
            label = { Text("Max") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Hardware erlaubt ${ServoConstants.HW_MIN_US}–${ServoConstants.HW_MAX_US} µs.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val mn = minText.toIntOrNull() ?: ServoConstants.HW_MIN_US
        val mx = maxText.toIntOrNull() ?: ServoConstants.HW_MAX_US
        vm.saveLimits(mn, mx)
    }) { Text("Limits speichern") }
}

// ---- Presets --------------------------------------------------------------

@Composable
private fun PresetsSection(vm: ServoViewModel, ui: ServoUiState) {
    SectionTitle("Festwert-Tasten (µs)")
    Text(
        "Frei belegbar. Alternativ: eine Taste im Hauptscreen lang drücken speichert den aktuell eingestellten Wert.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // Local editable copy; re-seeded whenever the stored presets change.
    val texts = remember(ui.presets) {
        mutableStateListOf(*ui.presets.map { it.toString() }.toTypedArray())
    }
    val labels = listOf("Taste 1 (links)", "Taste 2 (links)", "Taste 3 (rechts)", "Taste 4 (rechts)")

    for (row in 0..1) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (col in 0..1) {
                val i = row * 2 + col
                OutlinedTextField(
                    value = texts.getOrElse(i) { "" },
                    onValueChange = { texts[i] = it.filter(Char::isDigit) },
                    label = { Text(labels[i]) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Button(onClick = {
        texts.forEachIndexed { i, t ->
            t.toIntOrNull()?.let { vm.savePreset(i, it) }
        }
    }) { Text("Festwerte speichern") }
}

// ---- WiFi -----------------------------------------------------------------

@Composable
private fun WifiSection(vm: ServoViewModel) {
    SectionTitle("WLAN am ESP32 (über BLE)")
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var useStatic by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var gw by remember { mutableStateOf("") }
    var mask by remember { mutableStateOf("255.255.255.0") }
    var dns by remember { mutableStateOf("") }

    LabeledField("SSID", ssid) { ssid = it }
    LabeledField("Passwort", pass, isPassword = true) { pass = it }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Switch(checked = useStatic, onCheckedChange = { useStatic = it })
        Spacer(Modifier.width(8.dp))
        Text("Statische IP")
    }
    if (useStatic) {
        LabeledField("IP-Adresse", ip, KeyboardType.Number) { ip = it }
        LabeledField("Gateway", gw, KeyboardType.Number) { gw = it }
        LabeledField("Subnetzmaske", mask, KeyboardType.Number) { mask = it }
        LabeledField("DNS", dns, KeyboardType.Number) { dns = it }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = ssid.isNotBlank(),
        onClick = {
            vm.sendWifi(
                WifiConfig(
                    ssid = ssid.trim(),
                    pass = pass,
                    useStaticIp = useStatic,
                    ip = ip.trim(),
                    gateway = gw.trim(),
                    mask = mask.trim(),
                    dns = dns.trim(),
                )
            )
        },
    ) { Text("An ESP32 senden") }
}

// ---- MQTT -----------------------------------------------------------------

@Composable
private fun MqttSection(vm: ServoViewModel, ui: ServoUiState) {
    SectionTitle("MQTT (optional)")
    var enabled by remember(ui.settings.mqttEnabled) { mutableStateOf(ui.settings.mqttEnabled) }
    var host by remember(ui.settings.mqttHost) { mutableStateOf(ui.settings.mqttHost) }
    var port by remember(ui.settings.mqttPort) { mutableStateOf(ui.settings.mqttPort.toString()) }
    var user by remember(ui.settings.mqttUser) { mutableStateOf(ui.settings.mqttUser) }
    var pass by remember(ui.settings.mqttPass) { mutableStateOf(ui.settings.mqttPass) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Switch(checked = enabled, onCheckedChange = { enabled = it })
        Spacer(Modifier.width(8.dp))
        Text(if (enabled) "MQTT aktiv" else "MQTT deaktiviert")
    }
    if (enabled) {
        LabeledField("Broker-Host", host) { host = it }
        LabeledField("Port", port, KeyboardType.Number) { port = it.filter(Char::isDigit) }
        LabeledField("Benutzer", user) { user = it }
        LabeledField("Passwort", pass, isPassword = true) { pass = it }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        vm.saveMqtt(
            MqttConfig(
                enabled = enabled,
                host = host.trim(),
                port = port.toIntOrNull() ?: 1883,
                user = user.trim(),
                pass = pass,
            )
        )
    }) { Text("MQTT speichern & senden") }
}

// ---- Firmware update (BLE-OTA) ---------------------------------------------

@Composable
private fun FirmwareSection(vm: ServoViewModel, ui: ServoUiState) {
    SectionTitle("Firmware-Update (ESP32)")

    val connected = ui.conn == ConnState.CONNECTED
    val busy = ui.ota is OtaState.Preparing || ui.ota is OtaState.Uploading || ui.ota is OtaState.Verifying

    Text(
        "Firmware-Version: " + (ui.fwVersion ?: if (connected) "unbekannt" else "— (nicht verbunden)"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startFirmwareUpdate(uri) }

    Button(
        enabled = connected && !busy,
        onClick = { filePicker.launch(arrayOf("application/octet-stream", "*/*")) },
    ) { Text("Firmware-Datei (.bin) wählen …") }

    Spacer(Modifier.height(8.dp))
    when (val ota = ui.ota) {
        is OtaState.Idle -> Text(
            "Überträgt „ServoTester.ino.bin“ per Bluetooth. Der Servo-Ausgang wird " +
                "währenddessen abgeschaltet, danach startet der ESP32 neu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is OtaState.Preparing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("Update wird vorbereitet …", style = MaterialTheme.typography.bodySmall)
        }
        is OtaState.Uploading -> {
            val fraction = if (ota.total > 0) ota.sent.toFloat() / ota.total else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "Übertrage … ${ota.sent / 1024} / ${ota.total / 1024} kB (${(fraction * 100).toInt()} %)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is OtaState.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("Prüfe und übernehme Firmware …", style = MaterialTheme.typography.bodySmall)
        }
        is OtaState.Success -> {
            Text(
                "Update erfolgreich! Der ESP32 startet neu — danach bitte neu verbinden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissOta) { Text("OK") }
        }
        is OtaState.Error -> {
            Text(
                ota.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissOta) { Text("OK") }
        }
    }

    Spacer(Modifier.height(16.dp))
    UsbFlashSubsection(vm, ui)
}

/**
 * Flashing over a USB-C (OTG) cable — the zero-PC path that also works on a
 * factory-fresh ESP32 and as recovery when BLE-OTA is not available.
 */
@Composable
private fun UsbFlashSubsection(vm: ServoViewModel, ui: ServoUiState) {
    Text("Per USB-Kabel (auch fabrikneue ESP32)", style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))

    val busy = when (ui.usbFlash) {
        is UsbFlashState.Idle, is UsbFlashState.Success, is UsbFlashState.Error -> false
        else -> true
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startUsbFlash(uri) }

    Button(
        enabled = !busy,
        onClick = { filePicker.launch(arrayOf("application/octet-stream", "*/*")) },
    ) { Text("Per USB flashen (.bin wählen) …") }

    Spacer(Modifier.height(8.dp))
    when (val st = ui.usbFlash) {
        is UsbFlashState.Idle -> Text(
            "ESP32 mit USB-C/OTG-Kabel ans Handy anschließen. Nimmt „merged.bin“ " +
                "(Komplett-Installation) oder „ServoTester.ino.bin“. Kein PC nötig.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UsbFlashState.Searching -> UsbProgressLine("Suche ESP32 am USB-Port … (USB-Zugriff erlauben)")
        is UsbFlashState.Connecting -> UsbProgressLine("Verbinde mit dem ROM-Bootloader …")
        is UsbFlashState.Erasing -> UsbProgressLine("Lösche Flash — kann eine Minute dauern …")
        is UsbFlashState.Writing -> {
            LinearProgressIndicator(
                progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("Schreibe Firmware … ${st.percent} %", style = MaterialTheme.typography.bodySmall)
        }
        is UsbFlashState.Verifying -> UsbProgressLine("Prüfe Firmware (MD5) …")
        is UsbFlashState.Success -> {
            Text(
                "Fertig! Firmware geflasht und geprüft — der ESP32 startet jetzt neu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissUsbFlash) { Text("OK") }
        }
        is UsbFlashState.Error -> {
            Text(st.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissUsbFlash) { Text("OK") }
        }
    }
}

@Composable
private fun UsbProgressLine(text: String) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall)
}

// ---- Online update --------------------------------------------------------

@Composable
private fun UpdateSection(vm: ServoViewModel, ui: ServoUiState) {
    SectionTitle("Online-Update")

    Text(
        "Installiert: App v${ui.appVersion.ifBlank { "?" }} · " +
            "Firmware ${ui.fwVersion ?: "— (nicht verbunden)"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    val checking = ui.update is UpdateState.Checking
    val downloading = ui.update is UpdateState.Downloading
    Button(enabled = !checking && !downloading, onClick = vm::checkForUpdates) {
        Text(if (checking) "Suche …" else "Nach Update suchen")
    }
    Spacer(Modifier.height(8.dp))

    when (val st = ui.update) {
        is UpdateState.Idle -> Text(
            "Prüft die neueste Version im GitHub-Projekt (Basti77/servotester) und " +
                "kann App und Firmware direkt von dort aktualisieren.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UpdateState.Checking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is UpdateState.Error -> {
            Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissUpdate) { Text("OK") }
        }
        is UpdateState.Notice -> {
            Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::dismissUpdate) { Text("OK") }
        }
        is UpdateState.Downloading -> {
            LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("${st.label} lädt … ${st.percent} %", style = MaterialTheme.typography.bodySmall)
        }
        is UpdateState.Latest -> {
            val rel = st.release
            if (rel == null) {
                Text(
                    "Noch kein Release veröffentlicht. Sobald eins mit APK/.bin online ist, " +
                        "erscheint es hier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Neuestes Release: ${rel.name} (${rel.tag})", fontWeight = FontWeight.Medium)
                if (rel.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rel.notes.take(240),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = rel.firmware != null, onClick = vm::updateFirmwareOnline) {
                        Text("Firmware")
                    }
                    Button(enabled = rel.apk != null, onClick = vm::updateAppOnline) {
                        Text("App")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "„Firmware" + "“ lädt die .bin und flasht sie über die aktive BLE-/USB-" +
                        "Verbindung. „App" + "“ lädt die APK und startet den System-Installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Small helpers --------------------------------------------------------

@Composable
private fun LabeledField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
