package de.pflueger.servotester.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException

/**
 * Minimal CDC-ACM serial port on top of the Android USB host API.
 *
 * Deliberately NOT a general-purpose driver: it only needs to talk to the
 * ESP32-C3's built-in USB-Serial/JTAG bridge (VID 0x303A / PID 0x1001),
 * which is a bog-standard CDC-ACM device — one control interface (for
 * DTR/RTS line state) and one data interface with a bulk IN/OUT pair.
 *
 * DTR/RTS matter here: the ESP32-C3 hardware watches those virtual lines
 * and uses them to reset the chip into (or out of) the ROM bootloader —
 * that is how esptool's "automatic reset" works over USB-Serial/JTAG.
 */
class CdcAcmPort(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    companion object {
        const val ESPRESSIF_VID = 0x303A
        const val USB_SERIAL_JTAG_PID = 0x1001

        fun isEspUsbSerialJtag(device: UsbDevice): Boolean =
            device.vendorId == ESPRESSIF_VID && device.productId == USB_SERIAL_JTAG_PID

        // CDC class requests (USB CDC spec 1.1, §6.2)
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val REQTYPE_HOST_TO_INTERFACE = 0x21
    }

    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    fun open() {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> controlInterface = intf
                UsbConstants.USB_CLASS_CDC_DATA -> {
                    dataInterface = intf
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                            else bulkOut = ep
                        }
                    }
                }
            }
        }
        val ctrl = controlInterface ?: throw IOException("CDC-Control-Interface fehlt.")
        val data = dataInterface ?: throw IOException("CDC-Daten-Interface fehlt.")
        if (bulkIn == null || bulkOut == null) throw IOException("Bulk-Endpunkte fehlen.")
        if (!connection.claimInterface(ctrl, true) || !connection.claimInterface(data, true)) {
            throw IOException("USB-Interface belegt (anderer Prozess?).")
        }
        // 115200 8N1 — the virtual UART ignores the baud rate, but a defined
        // line coding keeps picky CDC stacks happy.
        val coding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)
        connection.controlTransfer(
            REQTYPE_HOST_TO_INTERFACE, SET_LINE_CODING, 0, ctrl.id, coding, coding.size, 1000
        )
    }

    /** DTR/RTS drive the ESP32-C3 auto-reset circuit (see esptool reset logic). */
    fun setDtrRts(dtr: Boolean, rts: Boolean) {
        val ctrl = controlInterface ?: return
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        connection.controlTransfer(
            REQTYPE_HOST_TO_INTERFACE, SET_CONTROL_LINE_STATE, value, ctrl.id, null, 0, 1000
        )
    }

    fun write(data: ByteArray, timeoutMs: Int) {
        var off = 0
        while (off < data.size) {
            val len = minOf(data.size - off, 4096)
            val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
            val sent = connection.bulkTransfer(bulkOut, chunk, len, timeoutMs)
            if (sent < 0) throw IOException("USB-Schreibfehler.")
            off += sent
        }
    }

    /** @return bytes read into [buf] (0 on timeout). */
    fun read(buf: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(bulkIn, buf, buf.size, timeoutMs)
        return if (n < 0) 0 else n
    }

    fun close() {
        runCatching { controlInterface?.let { connection.releaseInterface(it) } }
        runCatching { dataInterface?.let { connection.releaseInterface(it) } }
        runCatching { connection.close() }
    }
}
