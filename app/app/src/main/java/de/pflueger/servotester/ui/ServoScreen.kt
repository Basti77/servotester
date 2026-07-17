package de.pflueger.servotester.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pflueger.servotester.ble.ConnState
import de.pflueger.servotester.control.ServoConstants
import de.pflueger.servotester.control.ServoViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServoScreen(vm: ServoViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { SettingsDrawer(vm, ui) },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ServoTester") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Einstellungen")
                        }
                    },
                    actions = { ConnectionBadge(ui) },
                )
            }
        ) { inner ->
            ServoContent(vm, ui, inner)
        }
    }
}

@Composable
private fun ConnectionBadge(ui: de.pflueger.servotester.control.ServoUiState) {
    // The badge only claims a live link once the firmware has actually
    // answered (BLE: version characteristic read; USB: "FW ..." handshake).
    val (label, color) = when {
        // USB has priority in the badge — it's the deliberate, wired choice.
        ui.usbConnected && ui.usbFwOk == true ->
            "USB · FW ${ui.fwVersion ?: "?"}" to MaterialTheme.colorScheme.tertiary
        ui.usbConnected && ui.usbFwOk == null ->
            "USB · prüfe…" to MaterialTheme.colorScheme.secondary
        ui.usbConnected ->
            "USB · keine Antwort" to MaterialTheme.colorScheme.error
        ui.conn == ConnState.CONNECTED && ui.fwVersion != null ->
            "BLE · FW ${ui.fwVersion}" to MaterialTheme.colorScheme.tertiary
        ui.conn == ConnState.CONNECTED ->
            "BLE · verbunden" to MaterialTheme.colorScheme.tertiary
        ui.conn == ConnState.CONNECTING || ui.conn == ConnState.DISCOVERING ->
            "Verbinde…" to MaterialTheme.colorScheme.secondary
        ui.conn == ConnState.SCANNING ->
            "Suche…" to MaterialTheme.colorScheme.secondary
        ui.conn == ConnState.FAILED ->
            "Fehler" to MaterialTheme.colorScheme.error
        else ->
            "Getrennt" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.18f), shape = CircleShape, modifier = Modifier.padding(end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun ServoContent(vm: ServoViewModel, ui: de.pflueger.servotester.control.ServoUiState, inner: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // Numeric readout: live (ramped) value, plus the target if still moving.
        Text(
            text = "${ui.display} µs",
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (ui.display != ui.target) "→ ${ui.target} µs" else "Limit ${ui.minLimit}–${ui.maxLimit} µs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Jogdial with the central ON/OFF toggle.
        Jogdial(
            value = ui.display,
            min = ui.minLimit,
            max = ui.maxLimit,
            onValueChange = vm::onJogTo,
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            CenterToggle(on = ui.outputOn, onClick = vm::toggleOutput)
        }

        Spacer(Modifier.height(12.dp))

        // Ramp-speed slider: how fast the angle is allowed to change. Doubles as
        // a handy servo stress test (crank it up to slam the gears, down to creep).
        SpeedSlider(spanMs = ui.settings.rampSpanMs, onSpanChange = vm::setRampSpan)

        Spacer(Modifier.height(12.dp))

        // Center (1500 µs) quick button.
        OutlinedButton(onClick = vm::onCenter) {
            Text("Zentrieren · 1500 µs")
        }

        Spacer(Modifier.weight(1f))

        val context = LocalContext.current
        // Long-press assigns the current dialed value to that preset.
        fun assign(index: Int) {
            vm.assignPresetFromCurrent(index)
            Toast.makeText(context, "Preset ${index + 1} = ${ui.target} µs gespeichert", Toast.LENGTH_SHORT).show()
        }

        // 4 preset buttons: two left, two right.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PresetButton(ui.presets.getOrElse(0) { 700 }, onClick = { vm.onPreset(0) }, onLongClick = { assign(0) })
                PresetButton(ui.presets.getOrElse(1) { 1000 }, onClick = { vm.onPreset(1) }, onLongClick = { assign(1) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PresetButton(ui.presets.getOrElse(2) { 2000 }, onClick = { vm.onPreset(2) }, onLongClick = { assign(2) })
                PresetButton(ui.presets.getOrElse(3) { 2300 }, onClick = { vm.onPreset(3) }, onLongClick = { assign(3) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Kurz tippen = anfahren · lang drücken = aktuellen Wert speichern",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SpeedSlider(spanMs: Int, onSpanChange: (Int) -> Unit) {
    val min = ServoConstants.RAMP_SPAN_MIN_MS.toFloat()
    val max = ServoConstants.RAMP_SPAN_MAX_MS.toFloat()
    // Invert the axis so dragging RIGHT speeds the servo up (smaller span).
    fun spanToPos(s: Int) = min + max - s.toFloat()
    fun posToSpan(p: Float) = (min + max - p).roundToInt()

    // Local, smooth drag state; the persisted value is only committed on release
    // so we don't spam the ESP (reliable BLE write) and DataStore per pixel.
    var pos by remember(spanMs) { mutableFloatStateOf(spanToPos(spanMs)) }
    val liveSpan = posToSpan(pos)

    Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Tempo · Vollausschlag in %.1f s".format(liveSpan / 1000f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onSpanChange(posToSpan(pos)) },
            valueRange = min..max,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("langsam", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("schnell", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CenterToggle(on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        modifier = Modifier.size(120.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(if (on) "AN" else "AUS", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(value: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Text(
            "$value",
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
    }
}
