package de.pflueger.servotester.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.pflueger.servotester.control.ServoConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "servo_settings")

/** All persisted, user-configurable app settings. */
data class AppSettings(
    val minLimit: Int = ServoConstants.HW_MIN_US,
    val maxLimit: Int = ServoConstants.HW_MAX_US,
    /** 4 quick-select preset values: [0],[1] left side, [2],[3] right side. */
    val presets: List<Int> = listOf(700, 1000, 2000, 2300),
    /** Ramp speed: time for the full 700..2300 span, in ms (mirrored to firmware). */
    val rampSpanMs: Int = ServoConstants.RAMP_SPAN_DEFAULT_MS,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    // MQTT broker config (pushed to ESP32 over BLE; disabled => firmware skips MQTT)
    val mqttEnabled: Boolean = false,
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val mqttUser: String = "",
    val mqttPass: String = "",
)

/**
 * Thin wrapper around DataStore. Exposes a reactive [settings] flow and typed
 * setters. Limits are always kept inside the hardware envelope and min < max.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            minLimit = p[MIN].coerceHwOr(ServoConstants.HW_MIN_US),
            maxLimit = p[MAX].coerceHwOr(ServoConstants.HW_MAX_US),
            presets = listOf(
                p[PRESET0] ?: 700,
                p[PRESET1] ?: 1000,
                p[PRESET2] ?: 2000,
                p[PRESET3] ?: 2300,
            ),
            rampSpanMs = (p[RAMP_SPAN] ?: ServoConstants.RAMP_SPAN_DEFAULT_MS)
                .coerceIn(ServoConstants.RAMP_SPAN_MIN_MS, ServoConstants.RAMP_SPAN_MAX_MS),
            lastDeviceAddress = p[DEV_ADDR],
            lastDeviceName = p[DEV_NAME],
            mqttEnabled = p[MQTT_ON] ?: false,
            mqttHost = p[MQTT_HOST] ?: "",
            mqttPort = p[MQTT_PORT] ?: 1883,
            mqttUser = p[MQTT_USER] ?: "",
            mqttPass = p[MQTT_PASS] ?: "",
        )
    }

    suspend fun setLimits(min: Int, max: Int) {
        val lo = min.coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
        val hi = max.coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
        // Guard against inverted limits — keep at least a 1 µs window.
        val (a, b) = if (lo < hi) lo to hi else hi to (hi + 1).coerceAtMost(ServoConstants.HW_MAX_US)
        context.dataStore.edit { it[MIN] = a; it[MAX] = b }
    }

    suspend fun setPreset(index: Int, value: Int) {
        val key = presetKey(index) ?: return
        context.dataStore.edit {
            it[key] = value.coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
        }
    }

    suspend fun setRampSpan(ms: Int) {
        context.dataStore.edit {
            it[RAMP_SPAN] = ms.coerceIn(ServoConstants.RAMP_SPAN_MIN_MS, ServoConstants.RAMP_SPAN_MAX_MS)
        }
    }

    suspend fun setLastDevice(address: String?, name: String?) {
        context.dataStore.edit {
            if (address != null) it[DEV_ADDR] = address else it.remove(DEV_ADDR)
            if (name != null) it[DEV_NAME] = name else it.remove(DEV_NAME)
        }
    }

    suspend fun setMqtt(enabled: Boolean, host: String, port: Int, user: String, pass: String) {
        context.dataStore.edit {
            it[MQTT_ON] = enabled
            it[MQTT_HOST] = host
            it[MQTT_PORT] = port
            it[MQTT_USER] = user
            it[MQTT_PASS] = pass
        }
    }

    private companion object {
        val MIN = intPreferencesKey("min_limit")
        val MAX = intPreferencesKey("max_limit")
        val PRESET0 = intPreferencesKey("preset_0")
        val PRESET1 = intPreferencesKey("preset_1")
        val PRESET2 = intPreferencesKey("preset_2")
        val PRESET3 = intPreferencesKey("preset_3")
        val RAMP_SPAN = intPreferencesKey("ramp_span_ms")
        val DEV_ADDR = stringPreferencesKey("dev_addr")
        val DEV_NAME = stringPreferencesKey("dev_name")
        val MQTT_ON = booleanPreferencesKey("mqtt_on")
        val MQTT_HOST = stringPreferencesKey("mqtt_host")
        val MQTT_PORT = intPreferencesKey("mqtt_port")
        val MQTT_USER = stringPreferencesKey("mqtt_user")
        val MQTT_PASS = stringPreferencesKey("mqtt_pass")

        fun presetKey(index: Int) = when (index) {
            0 -> PRESET0; 1 -> PRESET1; 2 -> PRESET2; 3 -> PRESET3; else -> null
        }

        fun Int?.coerceHwOr(default: Int): Int =
            (this ?: default).coerceIn(ServoConstants.HW_MIN_US, ServoConstants.HW_MAX_US)
    }
}
