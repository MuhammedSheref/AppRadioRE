package com.ameer.appradiore.core.protocol.weblink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Encodes and decodes WebLink protocol commands with 8-byte 'WL' headers and Little-Endian byte order.
 */
object WebLinkCodec {

    /**
     * Serializes a WebLinkCommand into a framed byte array with the 8-byte 'WL' header.
     */
    fun encode(cmd: WebLinkCommand): ByteArray {
        val payload = when (cmd) {
            is WebLinkCommand.SetCurrentApp -> {
                val idBytes = cmd.appId.toByteArray(StandardCharsets.UTF_8)
                val paramBytes = cmd.appParams.toByteArray(StandardCharsets.UTF_8)
                val buf = ByteBuffer.allocate(8 + idBytes.size + paramBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(idBytes.size)
                if (idBytes.isNotEmpty()) {
                    buf.put(idBytes)
                }
                buf.putInt(paramBytes.size)
                if (paramBytes.isNotEmpty()) {
                    buf.put(paramBytes)
                }
                buf.array()
            }
            is WebLinkCommand.SetFps -> {
                ByteBuffer.allocate(1).put(cmd.fps.toByte()).array()
            }
            is WebLinkCommand.SyncSessionTime -> {
                ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(cmd.clientTime)
                    .putLong(cmd.serverTime)
                    .array()
            }
            is WebLinkCommand.FillRectangle -> {
                val buf = ByteBuffer.allocate(16 + cmd.frameData.size).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(cmd.width)
                buf.putInt(cmd.height)
                buf.putInt(cmd.encodingType)
                buf.putInt(cmd.appId)
                buf.put(cmd.frameData)
                buf.array()
            }
            is WebLinkCommand.VideoConfig -> {
                val paramBytes = cmd.encoderParams.toByteArray(StandardCharsets.UTF_8)
                val buf = ByteBuffer.allocate(20 + paramBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(cmd.sourceWidth)
                buf.putInt(cmd.sourceHeight)
                buf.putInt(cmd.clientWidth)
                buf.putInt(cmd.clientHeight)
                buf.putInt(cmd.frameEncoding)
                if (paramBytes.isNotEmpty()) {
                    buf.put(paramBytes)
                }
                buf.array()
            }
            is WebLinkCommand.DisplayMetrics -> {
                val strBytes = cmd.rawMetrics.toByteArray(StandardCharsets.UTF_8)
                val buf = ByteBuffer.allocate(8 + strBytes.size + 1).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0)
                buf.putInt(strBytes.size + 1)
                buf.put(strBytes)
                buf.put(0.toByte())
                buf.array()
            }
            is WebLinkCommand.BrowserAction -> {
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd.action).array()
            }
            is WebLinkCommand.UnknownWebLinkCommand -> cmd.payload
            else -> ByteArray(0)
        }

        val totalSize = WebLinkCommand.HEADER_SIZE + payload.size
        val headerBuf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put(WebLinkCommand.MAGIC_BYTE1)
        headerBuf.put(WebLinkCommand.MAGIC_BYTE2)
        headerBuf.putShort(cmd.commandId)
        headerBuf.putInt(payload.size)
        headerBuf.put(payload)
        return headerBuf.array()
    }

    /**
     * Decodes all complete WebLink commands from a byte buffer.
     */
    fun decode(buffer: ByteArray, length: Int = buffer.size): List<WebLinkCommand> {
        val commands = mutableListOf<WebLinkCommand>()
        var offset = 0
        val effectiveLen = minOf(buffer.size, length)

        while (offset <= effectiveLen - WebLinkCommand.HEADER_SIZE) {
            if (buffer[offset] == WebLinkCommand.MAGIC_BYTE1 && buffer[offset + 1] == WebLinkCommand.MAGIC_BYTE2) {
                val headerWrap = ByteBuffer.wrap(buffer, offset, WebLinkCommand.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                headerWrap.position(2) // Skip magic bytes
                val cmdId = headerWrap.short
                val payloadSize = headerWrap.int

                if (payloadSize in 0..(10 * 1024 * 1024)) { // Max 10MB sanity check
                    val packetTotalSize = WebLinkCommand.HEADER_SIZE + payloadSize
                    if (offset + packetTotalSize <= effectiveLen) {
                        val payload = ByteArray(payloadSize)
                        System.arraycopy(buffer, offset + WebLinkCommand.HEADER_SIZE, payload, 0, payloadSize)
                        commands.add(parsePayload(cmdId, payload))
                        offset += packetTotalSize
                        continue
                    }
                }
            }
            offset++
        }
        return commands
    }

    private fun parsePayload(commandId: Short, payload: ByteArray): WebLinkCommand {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (commandId) {
                WebLinkCommand.ID_SYNC_SESSION_TIME -> {
                    val clientTime = buf.long
                    val serverTime = if (buf.remaining() >= 8) buf.long else 0L
                    WebLinkCommand.SyncSessionTime(clientTime, serverTime)
                }
                WebLinkCommand.ID_SET_FPS -> {
                    val fpsVal = if (buf.remaining() >= 4) {
                        buf.int
                    } else if (buf.remaining() >= 1) {
                        buf.get().toInt() and 0xFF
                    } else {
                        30
                    }
                    WebLinkCommand.SetFps(fpsVal)
                }
                WebLinkCommand.ID_VIDEO_CONFIG -> {
                    val srcW = buf.int
                    val srcH = buf.int
                    val clientW = if (buf.remaining() >= 4) buf.int else srcW
                    val clientH = if (buf.remaining() >= 4) buf.int else srcH
                    val enc = if (buf.remaining() >= 4) buf.int else 2
                    val params = if (buf.remaining() > 0) {
                        val pBytes = ByteArray(buf.remaining())
                        buf.get(pBytes)
                        String(pBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                    } else {
                        ""
                    }
                    WebLinkCommand.VideoConfig(
                        sourceWidth = srcW,
                        sourceHeight = srcH,
                        clientWidth = clientW,
                        clientHeight = clientH,
                        frameEncoding = enc,
                        encoderParams = params
                    )
                }
                WebLinkCommand.ID_DISPLAY_METRICS -> {
                    var xdpi = 240
                    var ydpi = 240
                    var rawMetrics = ""
                    if (buf.remaining() >= 8) {
                        buf.int // skip reserved int
                        val strLen = buf.int
                        if (buf.remaining() >= strLen && strLen > 0) {
                            val strBytes = ByteArray(strLen)
                            buf.get(strBytes)
                            rawMetrics = String(strBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                        }
                    } else if (buf.remaining() > 0) {
                        val strBytes = ByteArray(buf.remaining())
                        buf.get(strBytes)
                        rawMetrics = String(strBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                    }
                    if (rawMetrics.isNotEmpty()) {
                        val parts = rawMetrics.split("|")
                        for (part in parts) {
                            val kv = part.split("=")
                            if (kv.size == 2) {
                                when (kv[0].trim()) {
                                    "xdpi" -> xdpi = kv[1].trim().toIntOrNull() ?: xdpi
                                    "ydpi" -> ydpi = kv[1].trim().toIntOrNull() ?: ydpi
                                }
                            }
                        }
                    }
                    WebLinkCommand.DisplayMetrics(xdpi, ydpi, rawMetrics)
                }
                WebLinkCommand.ID_SET_CURRENT_APP -> {
                    if (buf.remaining() >= 4) {
                        val idLen = buf.int
                        val idStr = if (idLen > 0 && buf.remaining() >= idLen) {
                            val b = ByteArray(idLen)
                            buf.get(b)
                            String(b, StandardCharsets.UTF_8)
                        } else ""
                        val paramLen = if (buf.remaining() >= 4) buf.int else 0
                        val paramStr = if (paramLen > 0 && buf.remaining() >= paramLen) {
                            val b = ByteArray(paramLen)
                            buf.get(b)
                            String(b, StandardCharsets.UTF_8)
                        } else ""
                        WebLinkCommand.SetCurrentApp(idStr, paramStr)
                    } else {
                        WebLinkCommand.SetCurrentApp(String(payload, StandardCharsets.UTF_8), "")
                    }
                }
                WebLinkCommand.ID_BROWSER_COMMAND -> {
                    WebLinkCommand.BrowserAction(buf.int)
                }
                WebLinkCommand.ID_TOUCH_COMMAND -> {
                    val eventType = buf.int
                    val count = buf.int
                    val points = mutableListOf<WebLinkCommand.TouchPoint>()
                    for (i in 0 until count) {
                        if (buf.remaining() >= 20) {
                            val id = buf.int
                            val x = buf.int
                            val y = buf.int
                            val state = buf.int
                            val pressure = buf.float
                            points.add(WebLinkCommand.TouchPoint(id, x, y, state, pressure))
                        }
                    }
                    WebLinkCommand.Touch(eventType, points)
                }
                WebLinkCommand.ID_FILL_RECTANGLE -> {
                    val w = buf.int
                    val h = buf.int
                    val enc = buf.int
                    val appId = buf.int
                    val frame = ByteArray(buf.remaining())
                    buf.get(frame)
                    WebLinkCommand.FillRectangle(w, h, enc, appId, frame)
                }
                else -> WebLinkCommand.UnknownWebLinkCommand(commandId, payload)
            }
        } catch (e: Exception) {
            WebLinkCommand.UnknownWebLinkCommand(commandId, payload)
        }
    }
}
