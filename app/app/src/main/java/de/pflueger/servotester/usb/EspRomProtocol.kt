package de.pflueger.servotester.usb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * The subset of Espressif's serial ROM-bootloader protocol needed to flash
 * an ESP32-C3 — a Kotlin re-implementation of what esptool.py does in
 * "--no-stub" mode:
 *
 *   sync -> chip check -> SPI attach/params -> FLASH_DEFL_BEGIN (erases) ->
 *   FLASH_DEFL_DATA chunks (zlib-compressed image) -> FLASH_DEFL_END ->
 *   SPI_FLASH_MD5 verify -> hard reset.
 *
 * Framing is SLIP (RFC 1055): packets delimited by 0xC0, with 0xC0 escaped
 * as 0xDB 0xDC and 0xDB as 0xDB 0xDD. Command packets are
 * [0x00][op][len:u16][checksum:u32][payload]; responses are
 * [0x01][op][len:u16][value:u32][payload], where the payload's last 4 bytes
 * (ROM loader) carry the status: 0x00 = ok, else [1] = error code.
 *
 * No stub loader is uploaded: the C3 ROM natively supports compressed
 * writes and MD5, which keeps this implementation small and robust.
 */
class EspRomProtocol(private val port: CdcAcmPort) {

    companion object {
        // Command opcodes (esptool loader.py)
        private const val ESP_SYNC = 0x08
        private const val ESP_READ_REG = 0x0A
        private const val ESP_SPI_SET_PARAMS = 0x0B
        private const val ESP_SPI_ATTACH = 0x0D
        private const val ESP_FLASH_DEFL_BEGIN = 0x10
        private const val ESP_FLASH_DEFL_DATA = 0x11
        private const val ESP_FLASH_DEFL_END = 0x12
        private const val ESP_SPI_FLASH_MD5 = 0x13

        /** ROM loader accepts 0x400-byte blocks (stub would allow 0x4000). */
        private const val FLASH_WRITE_SIZE = 0x400

        /** Chip-detect magic register + known ESP32-C3 silicon revisions. */
        private const val CHIP_MAGIC_REG = 0x4000_1000L
        private val ESP32C3_MAGICS = setOf(0x6921506FL, 0x1B31506FL, 0x4881606FL, 0x4361606FL)

        private const val STATUS_BYTES = 4       // ROM loader (stub would use 2)
        private const val DEFAULT_TIMEOUT_MS = 3000
        private const val ERASE_TIMEOUT_PER_MB_MS = 30_000
        private const val MD5_TIMEOUT_PER_MB_MS = 8_000
    }

    private val readBuf = ByteArray(16 * 1024)
    private var pending = ByteArrayOutputStream()

    // ---- Reset strategies (esptool reset.py, USB-Serial/JTAG variants) ----

    /** Reset into the ROM bootloader (download mode). The device will drop
     *  off the bus and re-enumerate — the caller must reconnect afterwards. */
    fun resetIntoBootloader() {
        port.setDtrRts(dtr = false, rts = false)  // idle
        Thread.sleep(100)
        port.setDtrRts(dtr = true, rts = false)   // "IO0 low"
        Thread.sleep(100)
        port.setDtrRts(dtr = true, rts = true)    // must pass through (1,1) …
        port.setDtrRts(dtr = false, rts = true)   // … never (0,0): "reset"
        Thread.sleep(100)
        port.setDtrRts(dtr = false, rts = false)
    }

    /** Hard reset out of the bootloader into the (new) application. */
    fun hardResetIntoApp() {
        port.setDtrRts(dtr = false, rts = true)
        Thread.sleep(200)
        port.setDtrRts(dtr = false, rts = false)
    }

    // ---- Handshake ---------------------------------------------------------

    /** One sync attempt. @return true if the ROM answered. */
    fun trySync(): Boolean {
        val payload = ByteArray(36)
        payload[0] = 0x07; payload[1] = 0x07; payload[2] = 0x12; payload[3] = 0x20
        for (i in 4 until 36) payload[i] = 0x55
        return try {
            command(ESP_SYNC, payload, 0, timeoutMs = 500)
            // The ROM queues several identical sync replies — drain them.
            while (true) {
                val f = readFrame(120) ?: break
                if (f.isEmpty()) break
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Verify we are talking to an ESP32-C3 (chip-detect magic register). */
    fun checkChipIsC3() {
        val magic = readReg(CHIP_MAGIC_REG) and 0xFFFF_FFFFL
        if (magic !in ESP32C3_MAGICS) {
            throw IOException(
                "Unerwarteter Chip (Magic 0x%08X) — nur ESP32-C3 wird unterstützt.".format(magic)
            )
        }
    }

    fun readReg(addr: Long): Long {
        val resp = command(ESP_READ_REG, packU32(addr.toInt()), 0, DEFAULT_TIMEOUT_MS)
        return resp.value.toLong() and 0xFFFF_FFFFL
    }

    /** Attach the SPI flash + declare its geometry (required in no-stub mode). */
    fun attachFlash(totalSizeBytes: Int) {
        // hspi_arg 0 = default pins; ROM loader wants 4 extra zero bytes.
        command(ESP_SPI_ATTACH, packU32(0) + packU32(0), 0, DEFAULT_TIMEOUT_MS)
        val params = packU32(0) + packU32(totalSizeBytes) +
            packU32(64 * 1024) + packU32(4 * 1024) + packU32(256) + packU32(0xFFFF)
        command(ESP_SPI_SET_PARAMS, params, 0, DEFAULT_TIMEOUT_MS)
    }

    // ---- Compressed flashing ------------------------------------------------

    /**
     * Write [image] to flash at [offset] using the ROM's zlib-inflate path
     * (FLASH_DEFL_*). The ROM erases the target region during BEGIN — that
     * single response can take tens of seconds for multi-MB regions.
     */
    fun flashCompressed(offset: Int, image: ByteArray, onProgress: (sent: Int, total: Int) -> Unit) {
        val compressed = deflate(image)
        val numBlocks = (compressed.size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val eraseBlocks = (image.size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val writeSize = eraseBlocks * FLASH_WRITE_SIZE

        val eraseTimeout = DEFAULT_TIMEOUT_MS +
            (writeSize.toLong() * ERASE_TIMEOUT_PER_MB_MS / 1_048_576).toInt()
        // C3 ROM expects the extra "encrypted" word (0 = plaintext).
        val begin = packU32(writeSize) + packU32(numBlocks) +
            packU32(FLASH_WRITE_SIZE) + packU32(offset) + packU32(0)
        command(ESP_FLASH_DEFL_BEGIN, begin, 0, eraseTimeout)

        var seq = 0
        var sent = 0
        while (sent < compressed.size) {
            val end = minOf(sent + FLASH_WRITE_SIZE, compressed.size)
            val block = compressed.copyOfRange(sent, end)
            val header = packU32(block.size) + packU32(seq) + packU32(0) + packU32(0)
            // Inflating + writing a block can hit a slow flash-erase path.
            command(ESP_FLASH_DEFL_DATA, header + block, checksum(block), 10_000)
            sent = end
            seq++
            onProgress(sent, compressed.size)
        }
        // 1 = stay in the loader (we still want to verify via MD5).
        command(ESP_FLASH_DEFL_END, packU32(1), 0, DEFAULT_TIMEOUT_MS)
    }

    /** Ask the ROM for the MD5 of the flashed region and compare. */
    fun verifyMd5(offset: Int, image: ByteArray) {
        val expected = MessageDigest.getInstance("MD5").digest(image)
            .joinToString("") { "%02x".format(it) }
        val timeout = DEFAULT_TIMEOUT_MS +
            (image.size.toLong() * MD5_TIMEOUT_PER_MB_MS / 1_048_576).toInt()
        val payload = packU32(offset) + packU32(image.size) + packU32(0) + packU32(0)
        val resp = command(ESP_SPI_FLASH_MD5, payload, 0, timeout)
        // ROM loader returns the digest as 32 ASCII hex chars (stub: 16 raw).
        if (resp.data.size < 32 + STATUS_BYTES) throw IOException("MD5-Antwort zu kurz.")
        val actual = String(resp.data, 0, 32, Charsets.US_ASCII).lowercase()
        if (actual != expected) {
            throw IOException("MD5-Prüfung fehlgeschlagen (Flash: $actual, Datei: $expected).")
        }
    }

    // ---- Protocol plumbing ---------------------------------------------------

    private class Response(val value: Int, val data: ByteArray)

    private fun command(op: Int, payload: ByteArray, chk: Int, timeoutMs: Int): Response {
        val packet = ByteArray(8 + payload.size)
        packet[0] = 0x00
        packet[1] = op.toByte()
        packet[2] = (payload.size and 0xFF).toByte()
        packet[3] = ((payload.size shr 8) and 0xFF).toByte()
        packU32(chk).copyInto(packet, 4)
        payload.copyInto(packet, 8)
        pending.reset()                       // stale bytes belong to no one
        port.write(slipEncode(packet), 2000)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw IOException("Timeout — keine Antwort vom ROM-Loader (Kommando 0x%02X).".format(op))
            val frame = readFrame(minOf(remaining, 500)) ?: continue
            if (frame.size < 8 + 2 || frame[0].toInt() != 0x01) continue
            if ((frame[1].toInt() and 0xFF) != op) continue   // reply to an older command
            val value = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8) or
                ((frame[6].toInt() and 0xFF) shl 16) or ((frame[7].toInt() and 0xFF) shl 24)
            val data = frame.copyOfRange(8, frame.size)
            // ROM loader: 4 status bytes; tolerate 2-byte replies just in case.
            val statusIdx = data.size - if (data.size < STATUS_BYTES) 2 else STATUS_BYTES
            if (data[statusIdx].toInt() != 0) {
                val err = data[statusIdx + 1].toInt() and 0xFF
                throw IOException("ROM-Loader meldet Fehler 0x%02X bei Kommando 0x%02X.".format(err, op))
            }
            return Response(value, data)
        }
    }

    /** Read one complete SLIP frame, or null on timeout. */
    private fun readFrame(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            extractFrame()?.let { return it }
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) return null
            val n = port.read(readBuf, minOf(remaining, 200))
            if (n > 0) pending.write(readBuf, 0, n)
        }
    }

    /** Pull the first complete 0xC0...0xC0 frame out of the pending buffer. */
    private fun extractFrame(): ByteArray? {
        val bytes = pending.toByteArray()
        var start = -1
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == 0xC0.toByte()) {
                if (start < 0) start = i
                else if (i > start + 1) {   // non-empty frame complete
                    val frame = slipDecode(bytes, start + 1, i)
                    pending = ByteArrayOutputStream().also { it.write(bytes, i + 1, bytes.size - i - 1) }
                    return frame
                } else start = i            // empty frame / duplicate delimiter
            }
            i++
        }
        // Drop garbage before the first delimiter to keep the buffer bounded.
        if (start > 0) {
            pending = ByteArrayOutputStream().also { it.write(bytes, start, bytes.size - start) }
        }
        return null
    }

    private fun slipEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        out.write(0xC0)
        for (b in data) {
            when (b) {
                0xC0.toByte() -> { out.write(0xDB); out.write(0xDC) }
                0xDB.toByte() -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt())
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    private fun slipDecode(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(to - from)
        var i = from
        while (i < to) {
            val b = data[i]
            if (b == 0xDB.toByte() && i + 1 < to) {
                when (data[i + 1]) {
                    0xDC.toByte() -> { out.write(0xC0); i++ }
                    0xDD.toByte() -> { out.write(0xDB); i++ }
                    else -> out.write(b.toInt())
                }
            } else out.write(b.toInt())
            i++
        }
        return out.toByteArray()
    }

    /** esptool's checksum: XOR over the data bytes, seeded with 0xEF. */
    private fun checksum(data: ByteArray): Int {
        var chk = 0xEF
        for (b in data) chk = chk xor (b.toInt() and 0xFF)
        return chk
    }

    private fun packU32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** zlib-compress (the ROM inflates); level 9 like esptool. */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(9, /* nowrap = */ false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 3)
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }
}
