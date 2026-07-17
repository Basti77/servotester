package de.pflueger.servotester.control

/**
 * Central, single-source-of-truth constants for the servo domain.
 *
 * The Lastenheft contains a minor internal contradiction about the ramp speed:
 *  - §3.2 gives "1000→2000 µs in 2 s" (= 0.5 µs/ms)
 *  - both generation prompts give "full travel 700→2300 µs in 2 s" (= 0.8 µs/ms)
 *
 * We resolve this with a SINGLE slew-rate constant so that every jump is
 * proportional automatically. Default = "full 700..2300 span in 2 s".
 * To follow the literal 0.5 µs/ms reading instead, set
 * [RAMP_RATE_US_PER_MS] = 0.5 and ignore [RAMP_FULL_SPAN_MILLIS].
 */
object ServoConstants {

    /** Hard hardware limits of the servo pulse width, in microseconds. */
    const val HW_MIN_US = 700
    const val HW_MAX_US = 2300

    /** Neutral / center position. */
    const val CENTER_US = 1500

    /** Time the *full* hardware span (700..2300) must take when ramping. */
    const val RAMP_FULL_SPAN_MILLIS = 2000.0

    /** Derived slew rate in µs per millisecond. Smaller jumps scale linearly. */
    const val RAMP_RATE_US_PER_MS = (HW_MAX_US - HW_MIN_US) / RAMP_FULL_SPAN_MILLIS  // = 0.8

    /** How often the ramp emits an intermediate value (ms). 20 ms == one PWM frame @50Hz. */
    const val RAMP_STEP_MILLIS = 20L

    /**
     * User-adjustable ramp speed (app slider): the time the FULL 700..2300 span
     * should take, in ms. Kept in sync with the firmware's authoritative ramp
     * (BLE RATE characteristic / USB "RATE" line). Must match the firmware bounds.
     */
    const val RAMP_SPAN_MIN_MS = 200
    const val RAMP_SPAN_MAX_MS = 5000
    const val RAMP_SPAN_DEFAULT_MS = 2000

    /** Slew rate in µs/ms for a given full-span time. */
    fun rateForSpan(spanMs: Int): Double =
        (HW_MAX_US - HW_MIN_US) / spanMs.coerceIn(RAMP_SPAN_MIN_MS, RAMP_SPAN_MAX_MS).toDouble()
}
