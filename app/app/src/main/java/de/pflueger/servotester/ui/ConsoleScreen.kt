package de.pflueger.servotester.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pflueger.servotester.control.LogDir
import de.pflueger.servotester.control.LogEntry

/**
 * Developer console: the raw TX/RX traffic plus the firmware's phase log, so a
 * dropped command or a stalled Ist-stream is visible instead of a silent
 * "connected but nothing happens". Colour-coded by direction; newest at the
 * bottom with auto-scroll while you're at the tail.
 */
@Composable
fun ConsoleContent(entries: List<LogEntry>, inner: PaddingValues) {
    val listState = rememberLazyListState()

    // Auto-scroll to the newest line as it arrives.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner)
            .padding(horizontal = 12.dp),
    ) {
        if (entries.isEmpty()) {
            Text(
                "Noch keine Ereignisse. Verbinde per BLE oder USB und bewege den Servo — " +
                    "gesendete Befehle (TX), Rückmeldungen (RX) und die Firmware-Phasen " +
                    "(ESP) erscheinen hier.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }

        val hScroll = rememberScrollState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll),
        ) {
            items(entries, key = { it.seq }) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val (tag, color) = when (e.dir) {
        LogDir.TX -> "TX " to MaterialTheme.colorScheme.primary
        LogDir.RX -> "RX " to MaterialTheme.colorScheme.tertiary
        LogDir.INFO -> "•  " to when (e.src) {
            "resync" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.secondary
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = e.time,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = tag,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = "%-6s ".format(e.src),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = color.copy(alpha = 0.9f),
        )
        Text(
            text = e.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
