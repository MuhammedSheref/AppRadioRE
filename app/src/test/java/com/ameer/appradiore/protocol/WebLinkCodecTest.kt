package com.ameer.appradiore.protocol

import com.ameer.appradiore.core.protocol.weblink.WebLinkCodec
import com.ameer.appradiore.core.protocol.weblink.WebLinkCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebLinkCodecTest {

    @Test
    fun testEncodeHeaderAndMagic() {
        val cmd = WebLinkCommand.SetFps(30)
        val encoded = WebLinkCodec.encode(cmd)

        // Header: 'W' (0x57), 'L' (0x4C)
        assertEquals(0x57.toByte(), encoded[0])
        assertEquals(0x4C.toByte(), encoded[1])

        val bb = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(2)
        val cmdId = bb.short
        val len = bb.int
        val fps = bb.int

        assertEquals(WebLinkCommand.ID_SET_FPS, cmdId)
        assertEquals(4, len)
        assertEquals(30, fps)
    }

    @Test
    fun testRoundTripSetCurrentApp() {
        val appUri = "weblink://com.ameer.appradiore"
        val cmd = WebLinkCommand.SetCurrentApp(appUri)
        val encoded = WebLinkCodec.encode(cmd)

        val decodedList = WebLinkCodec.decode(encoded)
        assertEquals(1, decodedList.size)
        assertTrue(decodedList[0] is WebLinkCommand.SetCurrentApp)
        val decodedCmd = decodedList[0] as WebLinkCommand.SetCurrentApp
        assertEquals(appUri, decodedCmd.appUri)
    }

    @Test
    fun testDecodeTouchCommand() {
        val payloadBuf = ByteBuffer.allocate(8 + 20).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuf.putInt(1) // eventType = 1 (DOWN)
        payloadBuf.putInt(1) // pointer count = 1
        // Touch point
        payloadBuf.putInt(0)     // pointerId = 0
        payloadBuf.putInt(400)   // x = 400
        payloadBuf.putInt(240)   // y = 240
        payloadBuf.putInt(1)     // state = 1
        payloadBuf.putFloat(1.0f)// pressure = 1.0f

        val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.put(WebLinkCommand.MAGIC_BYTE1)
        headerBuf.put(WebLinkCommand.MAGIC_BYTE2)
        headerBuf.putShort(WebLinkCommand.ID_TOUCH_COMMAND)
        headerBuf.putInt(payloadBuf.capacity())

        val packet = headerBuf.array() + payloadBuf.array()
        val decoded = WebLinkCodec.decode(packet)

        assertEquals(1, decoded.size)
        assertTrue(decoded[0] is WebLinkCommand.Touch)
        val touch = decoded[0] as WebLinkCommand.Touch
        assertEquals(1, touch.eventType)
        assertEquals(1, touch.points.size)
        assertEquals(400, touch.points[0].x)
        assertEquals(240, touch.points[0].y)
    }
}
