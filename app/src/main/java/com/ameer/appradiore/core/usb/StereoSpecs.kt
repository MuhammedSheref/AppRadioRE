package com.ameer.appradiore.core.usb

data class StereoSpecs(
    val width: Int = 0,
    val height: Int = 0,
    val modelId: Short = 0,
    val pointerCount: Byte = 1,
    val hasGps: Boolean = false,
    val hasRemoteControl: Boolean = false,
    val isParkingBrakeOn: Boolean = false,
    val isHdmiConnected: Boolean = false,
    val isReadyForVideo: Boolean = false,
    val dpi: Int = 0
) {
    val isIdentified: Boolean
        get() = width > 0 && height > 0
}
