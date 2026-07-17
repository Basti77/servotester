package de.pflueger.servotester.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/** USB flashing lifecycle exposed to the UI. */
sealed class UsbFlashState {
    data object Idle : UsbFlashState()
    data object Searching : UsbFlashState()      // waiting for device / permission
    data object Connecting : UsbFlashState()     // reset into bootloader + sync
    data object Erasing : UsbFlashState()        // ROM erases the target region
    data class Writing(val percent: Int) : UsbFlashState()
    data object Verifying : UsbFlashState()
    data object Success : UsbFlashState()
    data class Error(val message: String) : UsbFlashState()
}

/**
 * Flashes ESP32-C3 firmware straight from the phone over a USB-C (OTG)
 * cable — no PC, no esptool install. This is the only path that also works
 * on a factory-fresh chip (BLE-OTA needs a running firmware first).
 *
 * Flow: find the ESP's USB-Serial/JTAG device (303A:1001) -> user grants
 * USB permission -> try to sync (already in bootloader?) -> otherwise
 * trigger the esptool reset sequence, survive the re-enumeration, sync ->
 * verify chip -> compressed flash + MD5 -> hard reset into the new app.
 *
 * Accepts both artifacts of the firmware build:
 *  - `ServoTester.ino.merged.bin` (>2 MB) -> written to 0x0 (full install)
 *  - `ServoTester.ino.bin` (app image)    -> otadata wiped + app to 0x10000
 */
class UsbFlasher(private val context: Context) {

    companion object {
        private const val FLASH_TOTAL_SIZE = 4 * 1024 * 1024   // C3 SuperMini: 4 MB
        // Offsets from the min_spiffs partition table used by the firmware build.
        private const val OTADATA_OFFSET = 0xD000
        private const val OTADATA_SIZE = 0x2000
        private const val APP0_OFFSET = 0x10000
    }

    private val usb = UsbAccess.manager(context)

    private val _state = MutableStateFlow<UsbFlashState>(UsbFlashState.Idle)
    val state: StateFlow<UsbFlashState> = _state.asStateFlow()

    fun reset() {
        if (_state.value is UsbFlashState.Success || _state.value is UsbFlashState.Error) {
            _state.value = UsbFlashState.Idle
        }
    }

    private fun busy(): Boolean = when (_state.value) {
        is UsbFlashState.Idle, is UsbFlashState.Success, is UsbFlashState.Error -> false
        else -> true
    }

    suspend fun flash(firmware: ByteArray) = withContext(Dispatchers.IO) {
        if (busy()) return@withContext
        var port: CdcAcmPort? = null
        try {
            val writes = planWrites(firmware)

            _state.value = UsbFlashState.Searching
            var device = awaitDevice(timeoutMs = 15_000)
                ?: throw IOException("Kein ESP32 gefunden. USB-C/OTG-Kabel eingesteckt?")
            if (!requestPermission(device)) throw IOException("USB-Zugriff wurde abgelehnt.")

            _state.value = UsbFlashState.Connecting
            port = openPort(device)
            var proto = EspRomProtocol(port)

            // Already sitting in the ROM bootloader (BOOT held / earlier try)?
            var synced = (1..3).any { proto.trySync() }
            if (!synced) {
                // Trigger download-mode reset; the device falls off the bus
                // and re-enumerates, so reopen everything afterwards.
                proto.resetIntoBootloader()
                port.close(); port = null
                delay(1500)
                device = awaitDevice(timeoutMs = 10_000)
                    ?: throw IOException("ESP32 nach Reset nicht wieder aufgetaucht — Kabel prüfen.")
                if (!requestPermission(device)) throw IOException("USB-Zugriff wurde abgelehnt.")
                port = openPort(device)
                proto = EspRomProtocol(port)
                synced = (1..8).any { proto.trySync().also { ok -> if (!ok) Thread.sleep(300) } }
            }
            if (!synced) {
                throw IOException(
                    "ROM-Bootloader antwortet nicht. Notfallweg: BOOT-Taste am ESP32 " +
                        "gedrückt halten, einstecken, loslassen — dann erneut versuchen."
                )
            }

            proto.checkChipIsC3()
            proto.attachFlash(FLASH_TOTAL_SIZE)

            val progressTotal = writes.sumOf { it.second.size }
            var progressDone = 0
            for ((offset, image) in writes) {
                _state.value = UsbFlashState.Erasing
                proto.flashCompressed(offset, image) { sent, total ->
                    // Map compressed progress onto this image's uncompressed share.
                    val imagePart = image.size.toLong() * sent / total
                    val pct = ((progressDone + imagePart) * 100 / progressTotal).toInt()
                    _state.value = UsbFlashState.Writing(pct.coerceIn(0, 100))
                }
                progressDone += image.size
            }

            _state.value = UsbFlashState.Verifying
            for ((offset, image) in writes) proto.verifyMd5(offset, image)

            proto.hardResetIntoApp()
            _state.value = UsbFlashState.Success
        } catch (e: Exception) {
            _state.value = UsbFlashState.Error(e.message ?: "USB-Flash fehlgeschlagen.")
        } finally {
            port?.close()
        }
    }

    // ---- Image handling -------------------------------------------------

    /** Decide what goes where, mirroring `esptool write_flash`. */
    private fun planWrites(firmware: ByteArray): List<Pair<Int, ByteArray>> {
        if (firmware.size < 4 || (firmware[0].toInt() and 0xFF) != 0xE9) {
            throw IOException("Keine ESP32-Firmware (merged.bin oder ServoTester.ino.bin wählen).")
        }
        return when {
            firmware.size > FLASH_TOTAL_SIZE ->
                throw IOException("Datei größer als der 4-MB-Flash des ESP32-C3.")
            // Full image (merged.bin): flash from 0x0. Trailing 0xFF padding
            // is trimmed — the erase still covers otadata + app0, so the
            // freshly written app is guaranteed to boot.
            firmware.size > 2 * 1024 * 1024 -> listOf(0x0 to trimPadding(firmware))
            // App-only image: wipe otadata so the bootloader picks app0
            // (where we write), regardless of what OTA slot ran before.
            firmware.size >= 64 * 1024 -> listOf(
                OTADATA_OFFSET to ByteArray(OTADATA_SIZE) { 0xFF.toByte() },
                APP0_OFFSET to trimPadding(firmware),
            )
            else -> throw IOException(
                "Datei zu klein für eine App-Firmware — bootloader.bin/partitions.bin " +
                    "einzeln flashen wird nicht unterstützt (merged.bin nehmen)."
            )
        }
    }

    /** Drop trailing 0xFF (erased-flash) padding; keeps 4-byte alignment. */
    private fun trimPadding(image: ByteArray): ByteArray {
        var end = image.size
        while (end > 4096 && image[end - 1] == 0xFF.toByte()) end--
        end = ((end + 3) / 4) * 4
        return if (end >= image.size) image else image.copyOfRange(0, end)
    }

    // ---- Device / permission plumbing -------------------------------------

    private suspend fun awaitDevice(timeoutMs: Long): UsbDevice? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UsbAccess.findEsp(usb)?.let { return it }
            delay(250)
        }
        return null
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        UsbAccess.requestPermission(context, usb, device)

    private fun openPort(device: UsbDevice): CdcAcmPort {
        val conn = usb.openDevice(device)
            ?: throw IOException("USB-Gerät ließ sich nicht öffnen.")
        return CdcAcmPort(device, conn).also { it.open() }
    }
}
