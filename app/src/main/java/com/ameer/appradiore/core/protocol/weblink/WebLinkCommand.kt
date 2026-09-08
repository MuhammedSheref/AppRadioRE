package com.ameer.appradiore.core.protocol.weblink

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WebLink commands hierarchy.
 * All primitive numbers in WebLink headers and payloads are Little-Endian.
 */
sealed class WebLinkCommand {
    abstract val commandId: Short

    data class SetCurrentApp(
        val appId: String = "wlhome_1.0://",
        val appParams: String = ""
    ) : WebLinkCommand() {
        override val commandId: Short = ID_SET_CURRENT_APP
    }

    data class SetFps(val fps: Int) : WebLinkCommand() {
        override val commandId: Short = ID_SET_FPS
    }

    data class SyncSessionTime(
        val clientTime: Long,
        val serverTime: Long
    ) : WebLinkCommand() {
        override val commandId: Short = ID_SYNC_SESSION_TIME
    }

    data class VideoConfig(
        val sourceWidth: Int = 800,
        val sourceHeight: Int = 480,
        val clientWidth: Int = 800,
        val clientHeight: Int = 480,
        val frameEncoding: Int = 2, // 2 = H.264
        val encoderParams: String = ""
    ) : WebLinkCommand() {
        override val commandId: Short = ID_VIDEO_CONFIG
    }

    data class DisplayMetrics(
        val xdpi: Int = 240,
        val ydpi: Int = 240,
        val rawMetrics: String = "xdpi=240|ydpi=240"
    ) : WebLinkCommand() {
        override val commandId: Short = ID_DISPLAY_METRICS
    }

    data class FillRectangle(
        val width: Int,
        val height: Int,
        val encodingType: Int, // 2 = H.264
        val appId: Int,
        val frameData: ByteArray
    ) : WebLinkCommand() {
        override val commandId: Short = ID_FILL_RECTANGLE

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FillRectangle
            if (width != other.width) return false
            if (height != other.height) return false
            if (encodingType != other.encodingType) return false
            if (appId != other.appId) return false
            return frameData.contentEquals(other.frameData)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + encodingType
            result = 31 * result + appId
            result = 31 * result + frameData.contentHashCode()
            return result
        }
    }

    data class TouchPoint(
        val pointerId: Int,
        val x: Int,
        val y: Int,
        val state: Int, // 1=Pressed, 2=Moved, 4=Stationary, 8=Released
        val pressure: Float
    )

    data class Touch(
        val eventType: Int, // 0=Begin, 1=Update, 2=End
        val points: List<TouchPoint>
    ) : WebLinkCommand() {
        override val commandId: Short = ID_TOUCH_COMMAND
    }

    data class BrowserAction(
        val action: Int // 0 = Back Key
    ) : WebLinkCommand() {
        override val commandId: Short = ID_BROWSER_COMMAND
    }

    data class UnknownWebLinkCommand(
        override val commandId: Short,
        val payload: ByteArray
    ) : WebLinkCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as UnknownWebLinkCommand
            if (commandId != other.commandId) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = commandId.toInt()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    companion object {
        const val MAGIC_BYTE1: Byte = 0x57 // 'W'
        const val MAGIC_BYTE2: Byte = 0x4C // 'L'
        const val HEADER_SIZE = 8

        const val ID_FILL_RECTANGLE: Short = 1
        const val ID_MOUSE_COMMAND: Short = 16
        const val ID_KEYBOARD_COMMAND: Short = 17
        const val ID_BROWSER_COMMAND: Short = 18
        const val ID_VIDEO_CONFIG: Short = 32
        const val ID_SET_CURRENT_APP: Short = 66
        const val ID_SET_FPS: Short = 71
        const val ID_TOUCH_COMMAND: Short = 72
        const val ID_SYNC_SESSION_TIME: Short = 73
        const val ID_DISPLAY_METRICS: Short = 75
    }
}
