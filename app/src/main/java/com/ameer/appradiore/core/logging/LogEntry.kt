package com.ameer.appradiore.core.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogDirection {
    INCOMING,   // Received from stereo (Accessory to Phone)
    OUTGOING,   // Sent to stereo (Phone to Accessory)
    INTERNAL    // State machine or system events
}

enum class ProtocolType {
    SYSTEM,
    USB,
    PFORMAT,
    SAC,
    WEBLINK,
    MTP,
    RAW
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val direction: LogDirection,
    val protocol: ProtocolType,
    val summary: String,
    val rawHex: String = "",
    val details: String = "",
    val isError: Boolean = false
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestamp))
        }

    fun toExportString(): String {
        val dir = when (direction) {
            LogDirection.INCOMING -> "◄ [RX]"
            LogDirection.OUTGOING -> "► [TX]"
            LogDirection.INTERNAL -> "● [SYS]"
        }
        val sb = StringBuilder()
        sb.append("[$formattedTime] $dir [${protocol.name}] $summary\n")
        if (rawHex.isNotBlank()) {
            sb.append("  HEX: $rawHex\n")
        }
        if (details.isNotBlank()) {
            sb.append("  DETAILS: $details\n")
        }
        return sb.toString()
    }
}
