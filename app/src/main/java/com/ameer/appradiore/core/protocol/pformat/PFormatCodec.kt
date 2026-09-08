package com.ameer.appradiore.core.protocol.pformat

import java.io.ByteArrayOutputStream

/**
 * Encodes and decodes Pioneer PFormat frames.
 *
 * Wire Format:
 * [ 0x9F 0x02 ] [ Command ID (1 byte) ] [ Escaped Payload (N bytes) ] [ Escaped XOR Checksum (1 byte) ] [ 0x9F 0x03 ]
 *
 * Byte stuffing: 0x9F is escaped as 0x9F 0x9F.
 * Checksum: 1-byte rolling XOR sum of (Command ID ^ Payload[0] ^ Payload[1] ^ ...).
 */
object PFormatCodec {
    const val ESC: Byte = 0x9F.toByte()
    const val STX: Byte = 0x02.toByte()
    const val ETX: Byte = 0x03.toByte()

    /**
     * Encodes a command ID and raw payload into a complete PFormat framed byte array.
     */
    fun encode(commandId: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        // Write Start Delimiter: 0x9F 0x02
        out.write(byteArrayOf(ESC, STX))

        var xorSum = commandId.toInt() and 0xFF
        writeEscaped(out, commandId)

        for (b in payload) {
            xorSum = xorSum xor (b.toInt() and 0xFF)
            writeEscaped(out, b)
        }

        // Write XOR Checksum (also escaped if it equals 0x9F)
        writeEscaped(out, (xorSum and 0xFF).toByte())

        // Write End Delimiter: 0x9F 0x03
        out.write(byteArrayOf(ESC, ETX))
        return out.toByteArray()
    }

    /**
     * Scans a buffer for all valid PFormat frames and returns decoded packets.
     */
    fun decode(buffer: ByteArray, length: Int = buffer.size): List<PFormatPacket> {
        val packets = mutableListOf<PFormatPacket>()
        var i = 0
        val effectiveLen = minOf(buffer.size, length)

        while (i < effectiveLen - 3) {
            // Find Start Delimiter: 0x9F 0x02
            if (buffer[i] == ESC && buffer[i + 1] == STX) {
                val start = i + 2
                var end = -1
                var j = start

                // Scan for End Delimiter: 0x9F 0x03 (taking byte stuffing into account)
                while (j < effectiveLen - 1) {
                    if (buffer[j] == ESC && buffer[j + 1] == ETX) {
                        end = j
                        break
                    }
                    if (buffer[j] == ESC && buffer[j + 1] == ESC) {
                        j++ // Skip escaped 0x9F
                    }
                    j++
                }

                if (end != -1) {
                    // Unescape payload bytes
                    val unescaped = ByteArrayOutputStream()
                    var k = start
                    while (k < end) {
                        if (buffer[k] == ESC && k + 1 < end && buffer[k + 1] == ESC) {
                            unescaped.write(ESC.toInt())
                            k += 2
                        } else {
                            unescaped.write(buffer[k].toInt())
                            k++
                        }
                    }

                    val unescapedBytes = unescaped.toByteArray()
                    // Must have at least Command ID (1B) and XOR Checksum (1B) -> minimum 2 bytes
                    if (unescapedBytes.size >= 2) {
                        val cmdId = unescapedBytes[0]
                        val payload = if (unescapedBytes.size > 2) {
                            unescapedBytes.copyOfRange(1, unescapedBytes.size - 1)
                        } else {
                            ByteArray(0)
                        }
                        val receivedChecksum = unescapedBytes.last()

                        // Calculate expected XOR checksum
                        var expectedChecksum = cmdId.toInt() and 0xFF
                        for (b in payload) {
                            expectedChecksum = expectedChecksum xor (b.toInt() and 0xFF)
                        }

                        if ((expectedChecksum and 0xFF).toByte() == receivedChecksum) {
                            packets.add(PFormatPacket(cmdId, payload))
                        }
                    }
                    i = end + 2
                    continue
                }
            }
            i++
        }
        return packets
    }

    private fun writeEscaped(out: ByteArrayOutputStream, b: Byte) {
        if (b == ESC) {
            out.write(ESC.toInt())
            out.write(ESC.toInt())
        } else {
            out.write(b.toInt())
        }
    }
}

/**
 * Represents a decoded PFormat packet.
 */
data class PFormatPacket(
    val commandId: Byte,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PFormatPacket
        if (commandId != other.commandId) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = commandId.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    fun toHexString(): String {
        return payload.joinToString(" ") { String.format("%02X", it) }
    }
}
