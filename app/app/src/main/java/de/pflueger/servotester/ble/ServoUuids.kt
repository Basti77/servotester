package de.pflueger.servotester.ble

import org.json.JSONObject
import java.util.UUID

/**
 * BLE GATT contract shared with the ESP32-C3 firmware.
 *
 * Custom 128-bit Service with the characteristics required by the Lastenheft §2.2:
 *  - PWM value      (read / write / notify)  uint16 little-endian, target µs
 *  - Output state   (read / write / notify)  uint8  0 = OFF, 1 = ON
 *  - Network config (write)                  UTF-8 JSON  {ssid,pass,ip,gw,mask,dns}
 *  - MQTT config    (write)                  UTF-8 JSON  {host,port,user,pass,enabled}
 *  - Version        (read)                   UTF-8 firmware version string
 *  - OTA control    (write / notify)         firmware update handshake, see below
 *  - OTA data       (write no-response)      raw firmware chunks
 *
 * OTA protocol (all integers little-endian):
 *  app -> OTA_CTRL: [0x01][size:u32][md5hex:32 ascii] begin
 *                   [0x02] end/commit (verify + reboot), [0x03] abort
 *  fw  -> notify:   [status:u8][received:u32][err:u8]
 *                   status 0x01 ready, 0x02 success, 0x04 aborted, 0xEE error
 *
 * Keep these UUIDs in sync with the firmware's ServoTester.ino.
 */
object ServoUuids {
    val SERVICE: UUID = UUID.fromString("b1a70000-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val PWM: UUID = UUID.fromString("b1a70001-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val STATE: UUID = UUID.fromString("b1a70002-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val WIFI: UUID = UUID.fromString("b1a70003-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val MQTT: UUID = UUID.fromString("b1a70004-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val VERSION: UUID = UUID.fromString("b1a70005-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    /** Ramp speed: uint16 LE = time for the full 700..2300 span, in ms (read / write). */
    val RATE: UUID = UUID.fromString("b1a70006-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val OTA_CTRL: UUID = UUID.fromString("b1a70010-5c4d-4b8e-9f10-2a3b4c5d6e7f")
    val OTA_DATA: UUID = UUID.fromString("b1a70011-5c4d-4b8e-9f10-2a3b4c5d6e7f")

    /** Standard Client Characteristic Configuration Descriptor for enabling notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Static network configuration pushed to the ESP32 over the WIFI characteristic. */
data class WifiConfig(
    val ssid: String,
    val pass: String,
    val useStaticIp: Boolean,
    val ip: String = "",
    val gateway: String = "",
    val mask: String = "",
    val dns: String = "",
) {
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        put("ssid", ssid)
        put("pass", pass)
        put("static", useStaticIp)
        if (useStaticIp) {
            put("ip", ip)
            put("gw", gateway)
            put("mask", mask)
            put("dns", dns)
        }
    }.toString().toByteArray(Charsets.UTF_8)
}

/** MQTT broker configuration pushed to the ESP32. Disabled => firmware skips MQTT entirely. */
data class MqttConfig(
    val enabled: Boolean,
    val host: String = "",
    val port: Int = 1883,
    val user: String = "",
    val pass: String = "",
) {
    fun toJsonBytes(): ByteArray = JSONObject().apply {
        put("enabled", enabled)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
    }.toString().toByteArray(Charsets.UTF_8)
}
