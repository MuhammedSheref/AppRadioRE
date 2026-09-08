package com.ameer.appradiore.core.error

import com.ameer.appradiore.core.presentation.UiText

fun DataError.toUiText(): UiText {
    val message = when (this) {
        DataError.Usb.NO_ACCESSORY_FOUND -> "No Pioneer stereo accessory found. Check USB connection."
        DataError.Usb.PERMISSION_DENIED -> "USB connection permission was denied."
        DataError.Usb.DESCRIPTOR_OPEN_FAILED -> "Failed to open USB accessory descriptor."
        DataError.Usb.STREAM_CLOSED -> "USB accessory stream closed."
        DataError.Usb.IO_ERROR -> "I/O error communicating with Pioneer stereo."
        DataError.Usb.UNKNOWN -> "Unknown USB communication error occurred."

        DataError.Protocol.CHECKSUM_MISMATCH -> "PFormat packet checksum validation failed."
        DataError.Protocol.UNSUPPORTED_VERSION -> "Unsupported Pioneer stereo protocol version."
        DataError.Protocol.AUTH_REJECTED -> "Pioneer stereo rejected authentication handshake."
        DataError.Protocol.MALFORMED_PACKET -> "Received malformed or corrupted protocol packet."
        DataError.Protocol.TIMEOUT -> "Protocol response timed out."
    }
    return UiText.DynamicString(message)
}
