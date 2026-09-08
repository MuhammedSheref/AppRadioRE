package com.ameer.appradiore.protocol

import com.ameer.appradiore.core.protocol.pformat.PFormatCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PFormatCodecTest {

    @Test
    fun testEncodeBasicPacket() {
        // Command ID: 0x01, Payload: [0x02, 0x03]
        // Checksum: 0x01 ^ 0x02 ^ 0x03 = 0x00
        val encoded = PFormatCodec.encode(0x01.toByte(), byteArrayOf(0x02, 0x03))

        // Expected wire: [0x9F, 0x02, 0x01, 0x02, 0x03, 0x00, 0x9F, 0x03]
        val expected = byteArrayOf(
            0x9F.toByte(), 0x02,
            0x01, 0x02, 0x03,
            0x00,
            0x9F.toByte(), 0x03
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodeWithByteEscape() {
        // Command ID: 0x9F -> should be escaped as 0x9F 0x9F
        // Payload: [0x9F] -> should be escaped as 0x9F 0x9F
        // Checksum: 0x9F ^ 0x9F = 0x00
        val encoded = PFormatCodec.encode(0x9F.toByte(), byteArrayOf(0x9F.toByte()))

        val expected = byteArrayOf(
            0x9F.toByte(), 0x02,
            0x9F.toByte(), 0x9F.toByte(), // escaped cmdId
            0x9F.toByte(), 0x9F.toByte(), // escaped payload byte
            0x00,                         // checksum
            0x9F.toByte(), 0x03
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodeChecksumEscaped() {
        // Command ID: 0x9F, Payload: empty
        // Checksum: 0x9F -> must also be escaped as 0x9F 0x9F
        val encoded = PFormatCodec.encode(0x9F.toByte(), byteArrayOf())

        val expected = byteArrayOf(
            0x9F.toByte(), 0x02,
            0x9F.toByte(), 0x9F.toByte(), // escaped cmdId
            0x9F.toByte(), 0x9F.toByte(), // escaped checksum
            0x9F.toByte(), 0x03
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testRoundTripDecode() {
        val originalCmd: Byte = 0x0A
        val originalPayload = byteArrayOf(0x10, 0x20, 0x9F.toByte(), 0x30, 0x40)

        val encoded = PFormatCodec.encode(originalCmd, originalPayload)
        val decodedPackets = PFormatCodec.decode(encoded)

        assertEquals(1, decodedPackets.size)
        val packet = decodedPackets[0]
        assertEquals(originalCmd, packet.commandId)
        assertArrayEquals(originalPayload, packet.payload)
    }

    @Test
    fun testDecodeMultiplePacketsInOneBuffer() {
        val p1 = PFormatCodec.encode(0x01, byteArrayOf(0x11, 0x12))
        val p2 = PFormatCodec.encode(0x02, byteArrayOf(0x21, 0x22, 0x23))

        val combined = p1 + p2
        val decoded = PFormatCodec.decode(combined)

        assertEquals(2, decoded.size)
        assertEquals(0x01.toByte(), decoded[0].commandId)
        assertArrayEquals(byteArrayOf(0x11, 0x12), decoded[0].payload)

        assertEquals(0x02.toByte(), decoded[1].commandId)
        assertArrayEquals(byteArrayOf(0x21, 0x22, 0x23), decoded[1].payload)
    }

    @Test
    fun testDecodeCorruptChecksumRejected() {
        val valid = PFormatCodec.encode(0x01, byteArrayOf(0x02, 0x03))
        // Corrupt checksum byte (index 5)
        valid[5] = (valid[5] + 1).toByte()

        val decoded = PFormatCodec.decode(valid)
        assertTrue(decoded.isEmpty())
    }
}
