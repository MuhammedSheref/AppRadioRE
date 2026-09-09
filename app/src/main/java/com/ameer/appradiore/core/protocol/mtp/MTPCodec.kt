package com.ameer.appradiore.core.protocol.mtp

import java.io.ByteArrayOutputStream

/**
 * Encoder and decoder for Abalta Multi-Transport Protocol (MTP) framing used over USB AOA in Pioneer AAM2.
 */
object MTPCodec {

    data class DecodeResult(
        val packets: List<MTPPacket>,
        val unconsumedBytes: ByteArray
    )

    /**
     * Decodes complete MTP packets from a byte stream buffer.
     * Retains any incomplete trailing bytes in [DecodeResult.unconsumedBytes].
     */
    fun decode(buffer: ByteArray): DecodeResult {
        val packets = mutableListOf<MTPPacket>()
        var offset = 0

        while (offset < buffer.size) {
            // 1. Scan for frame begin 0x1E
            if (buffer[offset] != MTPPacket.FRAME_BEGIN) {
                offset++
                continue
            }

            val remaining = buffer.size - offset
            if (remaining < 5) {
                // Not enough bytes to even read frame size and options
                break
            }

            // 2. Read frame size (Big Endian)
            val frameSize = ((buffer[offset + 1].toInt() and 0xFF) shl 8) or (buffer[offset + 2].toInt() and 0xFF)
            if (frameSize < 6 || frameSize > 16384) {
                // Corrupted size, skip frame begin byte and keep scanning
                offset++
                continue
            }

            if (remaining < frameSize) {
                // Incomplete packet in buffer; wait for next chunk
                break
            }

            // 3. Verify frame end 0x03
            if (buffer[offset + frameSize - 1] != MTPPacket.FRAME_END) {
                // Invalid frame boundary, skip this begin byte
                offset++
                continue
            }

            // 4. Parse frame options
            val frameOptions = ((buffer[offset + 3].toInt() and 0xFF) shl 8) or (buffer[offset + 4].toInt() and 0xFF)
            val isLast = (frameOptions and 0x01) != 0
            val sourceProtocol = (frameOptions ushr 1) and 0x07
            val messageType = (frameOptions ushr 4) and 0x0F
            val checksumPresent = ((frameOptions ushr 8) and 0x01) != 0
            val isCompressed = ((frameOptions ushr 9) and 0x01) != 0

            // 5. Parse source address
            val srcOffset = offset + 5
            val (srcAddr, srcSize) = parseAddress(buffer, srcOffset, buffer.size - srcOffset)
            if (srcSize <= 0) {
                offset++
                continue
            }

            // 6. Parse destination address
            val dstOffset = srcOffset + srcSize
            val (dstAddr, dstSize) = parseAddress(buffer, dstOffset, buffer.size - dstOffset)
            if (dstSize <= 0) {
                offset++
                continue
            }

            // 7. Calculate payload offset and length
            val payloadOffset = dstOffset + dstSize
            val checksumSize = if (checksumPresent) 2 else 0
            val payloadEnd = offset + frameSize - 1 - checksumSize
            val payloadSize = payloadEnd - payloadOffset

            if (payloadSize < 0 || payloadEnd > buffer.size) {
                offset++
                continue
            }

            val payload = if (payloadSize > 0) {
                buffer.copyOfRange(payloadOffset, payloadOffset + payloadSize)
            } else {
                ByteArray(0)
            }

            packets.add(
                MTPPacket(
                    messageType = messageType,
                    isLast = isLast,
                    sourceProtocol = sourceProtocol,
                    isCompressed = isCompressed,
                    srcAddress = srcAddr,
                    dstAddress = dstAddr,
                    payload = payload
                )
            )

            offset += frameSize
        }

        val unconsumed = if (offset < buffer.size) {
            buffer.copyOfRange(offset, buffer.size)
        } else {
            ByteArray(0)
        }

        return DecodeResult(packets, unconsumed)
    }

    /**
     * Encodes an MTP packet into raw wire bytes with 0x1E begin, headers, addresses, payload, and 0x03 end.
     */
    fun encode(packet: MTPPacket): ByteArray {
        val srcAddrBytes = encodeAddress(packet.srcAddress)
        val dstAddrBytes = encodeAddress(packet.dstAddress)

        val headerSize = 5 + srcAddrBytes.size + dstAddrBytes.size
        val frameSize = headerSize + packet.payload.size + 1 // +1 for FRAME_END (no checksum)

        var frameOptions = 0
        if (packet.isLast) frameOptions = frameOptions or 0x0001
        frameOptions = frameOptions or ((packet.sourceProtocol and 0x07) shl 1)
        frameOptions = frameOptions or ((packet.messageType and 0x0F) shl 4)
        if (packet.isCompressed) frameOptions = frameOptions or (1 shl 9)

        val out = ByteArrayOutputStream(frameSize)
        out.write(MTPPacket.FRAME_BEGIN.toInt())
        // Frame size (Big Endian)
        out.write((frameSize ushr 8) and 0xFF)
        out.write(frameSize and 0xFF)
        // Frame options (Big Endian)
        out.write((frameOptions ushr 8) and 0xFF)
        out.write(frameOptions and 0xFF)
        // Addresses
        out.write(srcAddrBytes)
        out.write(dstAddrBytes)
        // Payload
        if (packet.payload.isNotEmpty()) {
            out.write(packet.payload)
        }
        // Frame end
        out.write(MTPPacket.FRAME_END.toInt())

        return out.toByteArray()
    }

    /**
     * Convenience method to wrap an arbitrary payload (e.g. PFormat framed SAC command)
     * into an MTP TCP Data Packet targeting port 12347.
     */
    fun wrapControlChannelPayload(
        payload: ByteArray,
        srcPort: Int = MTPPacket.PORT_CONTROL_CHANNEL,
        dstPort: Int = MTPPacket.PORT_CONTROL_CHANNEL,
        isLast: Boolean = false
    ): ByteArray {
        return wrapPayload(payload, srcPort, dstPort, isLast)
    }

    /**
     * Wraps an arbitrary payload into an MTP TCP Data Packet targeting a specified source and destination port.
     */
    fun wrapPayload(
        payload: ByteArray,
        srcPort: Int,
        dstPort: Int,
        isLast: Boolean = false
    ): ByteArray {
        val packet = MTPPacket.createDataPacket(
            srcAddress = MTPAddress(MTPAddress.TYPE_IPV4, MTPAddress.LOOPBACK_IP, srcPort),
            dstAddress = MTPAddress(MTPAddress.TYPE_IPV4, MTPAddress.LOOPBACK_IP, dstPort),
            payload = payload,
            isLast = isLast
        )
        return encode(packet)
    }

    /**
     * Wraps an arbitrary payload into one or more MTP TCP Data Packets targeting a specified port.
     * If the payload exceeds [MTPPacket.MAX_DATA_SIZE] (16,284 bytes), it is fragmented across multiple MTP packets
     * matching ConnectionPointMTP.writeDataInternal from the Pioneer/WebLink specification.
     */
    fun wrapPayloadFragmented(
        payload: ByteArray,
        srcPort: Int,
        dstPort: Int,
        isLast: Boolean = false
    ): List<ByteArray> {
        if (payload.size <= MTPPacket.MAX_DATA_SIZE) {
            return listOf(wrapPayload(payload, srcPort, dstPort, isLast))
        }

        val fragments = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val chunkLen = minOf(payload.size - offset, MTPPacket.MAX_DATA_SIZE)
            val chunk = payload.copyOfRange(offset, offset + chunkLen)
            val isFinalFragment = (offset + chunkLen >= payload.size) && isLast
            fragments.add(wrapPayload(chunk, srcPort, dstPort, isFinalFragment))
            offset += chunkLen
        }
        return fragments
    }

    /**
     * Creates an MTP connection acknowledgment packet (empty payload) to confirm channel establishment.
     * Note: [isLast] MUST be false. In MTP, an empty payload with isLast=true signals SendCloseMtpMessage (connection teardown).
     */
    fun createConnectionAck(
        srcAddress: MTPAddress,
        dstAddress: MTPAddress
    ): ByteArray {
        val packet = MTPPacket.createDataPacket(
            srcAddress = srcAddress,
            dstAddress = dstAddress,
            payload = ByteArray(0),
            isLast = false
        )
        return encode(packet)
    }

    private fun parseAddress(buffer: ByteArray, offset: Int, maxLen: Int): Pair<MTPAddress, Int> {
        if (maxLen < 1) return Pair(MTPAddress(), 0)
        val type = buffer[offset]
        return when (type) {
            MTPAddress.TYPE_NONE -> {
                Pair(MTPAddress(addressType = type), 1)
            }
            MTPAddress.TYPE_IPV4 -> {
                if (maxLen < 7) return Pair(MTPAddress(), 0)
                val ip = buffer.copyOfRange(offset + 1, offset + 5)
                val port = ((buffer[offset + 5].toInt() and 0xFF) shl 8) or (buffer[offset + 6].toInt() and 0xFF)
                Pair(MTPAddress(addressType = type, ip = ip, port = port), 7)
            }
            MTPAddress.TYPE_IPV6 -> {
                if (maxLen < 19) return Pair(MTPAddress(), 0)
                val ip = buffer.copyOfRange(offset + 1, offset + 17)
                val port = ((buffer[offset + 17].toInt() and 0xFF) shl 8) or (buffer[offset + 18].toInt() and 0xFF)
                Pair(MTPAddress(addressType = type, ip = ip, port = port), 19)
            }
            else -> Pair(MTPAddress(), 0)
        }
    }

    private fun encodeAddress(addr: MTPAddress): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(addr.addressType.toInt())
        when (addr.addressType) {
            MTPAddress.TYPE_IPV4 -> {
                val ip = if (addr.ip.size == 4) addr.ip else ByteArray(4) { 0 }
                out.write(ip)
                out.write((addr.port ushr 8) and 0xFF)
                out.write(addr.port and 0xFF)
            }
            MTPAddress.TYPE_IPV6 -> {
                val ip = if (addr.ip.size == 16) addr.ip else ByteArray(16) { 0 }
                out.write(ip)
                out.write((addr.port ushr 8) and 0xFF)
                out.write(addr.port and 0xFF)
            }
            MTPAddress.TYPE_NONE -> {
                // No IP or port
            }
        }
        return out.toByteArray()
    }
}
