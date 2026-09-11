package com.ameer.appradiore.protocol

import com.ameer.appradiore.core.protocol.mtp.MTPAddress
import com.ameer.appradiore.core.protocol.mtp.MTPCodec
import com.ameer.appradiore.core.protocol.mtp.MTPPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MTPCodecTest {

    @Test
    fun testEncodeAndDecodeDataPacket() {
        val src = MTPAddress(MTPAddress.TYPE_IPV4, byteArrayOf(127, 0, 0, 1), 12347)
        val dst = MTPAddress(MTPAddress.TYPE_IPV4, byteArrayOf(127, 0, 0, 1), 12347)
        val samplePayload = byteArrayOf(0x9F.toByte(), 0x02, 0x00, 0x00, 0x00, 0x9F.toByte(), 0x03)

        val packet = MTPPacket.createDataPacket(src, dst, samplePayload)
        val encoded = MTPCodec.encode(packet)

        // Verify framing boundaries
        assertEquals(0x1E.toByte(), encoded[0])
        assertEquals(0x03.toByte(), encoded[encoded.size - 1])

        // Verify total size: 1 (0x1E) + 2 (size) + 2 (opt) + 7 (src) + 7 (dst) + 7 (payload) + 1 (0x03) = 27
        assertEquals(27, encoded.size)

        // Decode
        val result = MTPCodec.decode(encoded)
        assertEquals(1, result.packets.size)
        assertEquals(0, result.unconsumedBytes.size)

        val decoded = result.packets[0]
        assertEquals(MTPPacket.TYPE_DATA, decoded.messageType)
        assertEquals(MTPPacket.PROTOCOL_TCP, decoded.sourceProtocol)
        org.junit.Assert.assertFalse(decoded.isLast)
        assertEquals(12347, decoded.srcAddress.port)
        assertEquals(12347, decoded.dstAddress.port)
        assertArrayEquals(samplePayload, decoded.payload)
    }

    @Test
    fun testExplicitIsLastPacket() {
        val src = MTPAddress(MTPAddress.TYPE_IPV4, byteArrayOf(127, 0, 0, 1), 12347)
        val dst = MTPAddress(MTPAddress.TYPE_IPV4, byteArrayOf(127, 0, 0, 1), 12347)
        val packet = MTPPacket.createDataPacket(src, dst, ByteArray(0), isLast = true)
        val encoded = MTPCodec.encode(packet)
        val result = MTPCodec.decode(encoded)

        assertEquals(1, result.packets.size)
        assertTrue(result.packets[0].isLast)
    }

    @Test
    fun testWrapControlChannelPayload() {
        val samplePayload = byteArrayOf(0x01, 0x02, 0x03)
        val wrapped = MTPCodec.wrapControlChannelPayload(samplePayload)

        assertEquals(0x1E.toByte(), wrapped[0])
        assertEquals(0x03.toByte(), wrapped[wrapped.size - 1])

        val result = MTPCodec.decode(wrapped)
        assertEquals(1, result.packets.size)
        assertEquals(12347, result.packets[0].srcAddress.port)
        assertEquals(12347, result.packets[0].dstAddress.port)
        assertArrayEquals(samplePayload, result.packets[0].payload)
    }

    @Test
    fun testAuthBeginEndToEndMtpWireBytes() {
        // SAC AuthBegin payload: [0x00]
        // PFormat encoded: [0x9F, 0x02, 0x00, 0x00, 0x00, 0x9F, 0x03] (7 bytes)
        val pFormatAuthBegin = byteArrayOf(0x9F.toByte(), 0x02, 0x00, 0x00, 0x00, 0x9F.toByte(), 0x03)
        val mtpBytes = MTPCodec.wrapControlChannelPayload(
            payload = pFormatAuthBegin,
            srcPort = MTPPacket.PORT_CONTROL_CHANNEL,
            dstPort = MTPPacket.PORT_CONTROL_CHANNEL
        )

        // Exact wire packet: 27 bytes matching Pioneer live protocol log
        val expectedWire = byteArrayOf(
            0x1E, 0x00, 0x1B, 0x00, 0x00,                         // MTP header (length = 27 = 0x001B)
            0x01, 0x7F, 0x00, 0x00, 0x01, 0x30, 0x3B,             // Src: 127.0.0.1:12347
            0x01, 0x7F, 0x00, 0x00, 0x01, 0x30, 0x3B,             // Dst: 127.0.0.1:12347
            0x9F.toByte(), 0x02, 0x00, 0x00, 0x00, 0x9F.toByte(), 0x03, // PFormat frame
            0x03                                                  // MTP end delimiter
        )
        assertEquals(27, mtpBytes.size)
        assertArrayEquals(expectedWire, mtpBytes)
    }

    @Test
    fun testPartialFrameBufferPreservation() {
        val samplePayload = byteArrayOf(0x10, 0x20)
        val encoded = MTPCodec.wrapControlChannelPayload(samplePayload)

        // Split encoded buffer into two chunks
        val chunk1 = encoded.copyOfRange(0, 10)
        val chunk2 = encoded.copyOfRange(10, encoded.size)

        // First decode with chunk1 should yield 0 packets and retain unconsumed bytes
        val result1 = MTPCodec.decode(chunk1)
        assertEquals(0, result1.packets.size)
        assertEquals(10, result1.unconsumedBytes.size)

        // Combine unconsumed bytes with chunk2
        val combined = result1.unconsumedBytes + chunk2
        val result2 = MTPCodec.decode(combined)
        assertEquals(1, result2.packets.size)
        assertEquals(0, result2.unconsumedBytes.size)
        assertArrayEquals(samplePayload, result2.packets[0].payload)
    }

    @Test
    fun testMultiplePacketsInSingleStream() {
        val p1 = MTPCodec.wrapControlChannelPayload(byteArrayOf(0xAA.toByte()))
        val p2 = MTPCodec.wrapControlChannelPayload(byteArrayOf(0xBB.toByte(), 0xCC.toByte()))
        val stream = p1 + p2

        val result = MTPCodec.decode(stream)
        assertEquals(2, result.packets.size)
        assertEquals(0, result.unconsumedBytes.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), result.packets[0].payload)
        assertArrayEquals(byteArrayOf(0xBB.toByte(), 0xCC.toByte()), result.packets[1].payload)
    }

    @Test
    fun testCorruptedBytesSkipped() {
        val garbage = byteArrayOf(0x55, 0x66, 0x77)
        val valid = MTPCodec.wrapControlChannelPayload(byteArrayOf(0x01))
        val stream = garbage + valid

        val result = MTPCodec.decode(stream)
        assertEquals(1, result.packets.size)
        assertArrayEquals(byteArrayOf(0x01), result.packets[0].payload)
    }
}
