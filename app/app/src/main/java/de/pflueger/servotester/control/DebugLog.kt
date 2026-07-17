package de.pflueger.servotester.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Direction/kind of a console line, used only for colour-coding in the UI. */
enum class LogDir {
    /** Command the app sent to the ESP32 (PWM/OUT/RATE/OTA). */
    TX,
    /** Value/notification the ESP32 reported back (Ist position, output, version). */
    RX,
    /** Firmware phase log (the ESP32's own dbg() lines) or app-side notes (resync). */
    INFO,
}

/** One timestamped console line. [seq] gives a stable key for LazyColumn. */
data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val dir: LogDir,
    val src: String,   // "BLE", "USB", "ESP", "app", "resync"
    val text: String,
) {
    val time: String get() = TIME_FMT.format(Date(timeMs))

    private companion object {
        val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Bounded, thread-safe ring buffer feeding the debug console. Every transport
 * (BLE, USB) and the ViewModel push here; the console screen renders the flow.
 *
 * Cheap by design — a single [MutableStateFlow] of an immutable list, capped at
 * [MAX] lines so a long ramp stream can't grow unbounded. Timestamps use wall
 * clock so the log lines up with anything else the user is watching.
 */
class DebugLog {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var seq = 0L

    @Synchronized
    fun add(dir: LogDir, src: String, text: String) {
        val e = LogEntry(seq++, System.currentTimeMillis(), dir, src, text)
        val next = _entries.value + e
        _entries.value = if (next.size > MAX) next.takeLast(MAX) else next
    }

    fun tx(src: String, text: String) = add(LogDir.TX, src, text)
    fun rx(src: String, text: String) = add(LogDir.RX, src, text)
    fun info(src: String, text: String) = add(LogDir.INFO, src, text)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX = 500
    }
}
