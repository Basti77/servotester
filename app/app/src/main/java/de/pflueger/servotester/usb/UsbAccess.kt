package de.pflueger.servotester.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Shared plumbing for both USB features (flasher + control link):
 * finding the ESP32-C3's USB-Serial/JTAG device and asking the user for
 * USB permission (with the system dialog) in coroutine style.
 */
object UsbAccess {

    private const val ACTION_USB_PERMISSION = "de.pflueger.servotester.USB_PERMISSION"

    fun manager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findEsp(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { CdcAcmPort.isEspUsbSerialJtag(it) }

    suspend fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice): Boolean {
        if (usb.hasPermission(device)) return true
        val granted = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != ACTION_USB_PERMISSION) return
                        runCatching { context.unregisterReceiver(this) }
                        cont.resume(
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        )
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                val pi = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                usb.requestPermission(device, pi)
            }
        }
        return granted == true
    }
}
