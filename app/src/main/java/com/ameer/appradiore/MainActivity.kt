package com.ameer.appradiore

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.feature.livelog.LiveLogRoot
import com.ameer.appradiore.ui.theme.AppRadioRETheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val usbAccessoryManager: UsbAccessoryManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            AppRadioRETheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiveLogRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            if (accessory != null) {
                usbAccessoryManager.connect(accessory)
            }
        }
    }
}