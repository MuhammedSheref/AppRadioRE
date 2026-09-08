package com.ameer.appradiore.core.usb

import android.hardware.usb.UsbAccessory

sealed interface UsbConnectionState {
    data object Disconnected : UsbConnectionState
    data class PermissionRequired(val accessory: UsbAccessory) : UsbConnectionState
    data class Connecting(val accessory: UsbAccessory) : UsbConnectionState
    data class Connected(val accessory: UsbAccessory) : UsbConnectionState
    data class Error(val message: String) : UsbConnectionState
}
