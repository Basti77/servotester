package de.pflueger.servotester.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Software "Smooth Transit" ramp (Lastenheft §3.2).
 *
 * Turns an abrupt target change into a linear slew so servo gears are never
 * slammed. The rate is [ServoConstants.RAMP_RATE_US_PER_MS]; because it is a
 * constant slew rate, smaller jumps take proportionally less time automatically
 * (a full 700..2300 travel = 2 s by default).
 *
 * Every intermediate value is delivered through [onValue] — the ViewModel wires
 * that to both the BLE write and the on-screen readout. Non-blocking: runs in a
 * coroutine on the supplied [scope], no Thread.sleep / no UI stalls.
 *
 * Note: the ESP32 firmware runs the same ramp on receive (authoritative fallback
 * if the app connection drops), so streaming intermediate values here is safe and
 * merely keeps the phone's readout honest.
 */
class RampController(
    private val scope: CoroutineScope,
    private val onValue: (Int) -> Unit,
) {
    /** The last value actually emitted (the "live" servo position as far as we know). */
    @Volatile
    var current: Int = ServoConstants.CENTER_US
        private set

    private var job: Job? = null

    /**
     * Slew rate in µs per millisecond. User-adjustable via the speed slider;
     * mirrors the firmware's authoritative rate so the on-screen ramp matches
     * what the servo actually does. Defaults to the "full span in 2 s" reading.
     */
    @Volatile
    var ratePerMs: Double = ServoConstants.RAMP_RATE_US_PER_MS

    /** True while an app-driven ramp is in flight (so remote echoes can be ignored). */
    val isRamping: Boolean
        get() = job?.isActive == true

    /**
     * Set a new target. The controller ramps [current] toward [target] at the
     * fixed slew rate. Calling again mid-ramp simply retargets from wherever we are.
     */
    fun setTarget(target: Int) {
        val clamped = target.coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
        job?.cancel()
        if (clamped == current) {
            onValue(current)   // idempotent: still confirm the value downstream
            return
        }
        job = scope.launch {
            val stepUs = ratePerMs * ServoConstants.RAMP_STEP_MILLIS
            while (isActive && current != clamped) {
                val remaining = clamped - current
                val delta = if (abs(remaining) <= stepUs) {
                    remaining.toDouble()
                } else {
                    stepUs * sign(remaining.toDouble())
                }
                current = (current + delta).roundToInt()
                    .coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
                onValue(current)
                if (current == clamped) break
                delay(ServoConstants.RAMP_STEP_MILLIS)
            }
        }
    }

    /**
     * Force the internal position without ramping — used to sync to a value the
     * firmware reports (e.g. right after connecting) so we don't ramp from a stale
     * assumed position.
     */
    fun syncTo(value: Int) {
        job?.cancel()
        current = value.coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
    }

    fun stop() {
        job?.cancel()
    }
}
