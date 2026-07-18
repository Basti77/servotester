package de.pflueger.servotester

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import de.pflueger.servotester.control.ServoViewModel
import de.pflueger.servotester.ui.ServoScreen
import de.pflueger.servotester.ui.theme.ServoTesterTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ServoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ServoTesterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ServoScreen(viewModel)
                }
            }
        }
    }

    // Re-attaching the ESP re-delivers the USB-attach intent here (singleTop).
    // The VM's runtime receiver usually catches it too; this makes the wired
    // auto-connect fire reliably when the app was brought to front by the plug-in.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) viewModel.autoConnectUsb()
    }
}
