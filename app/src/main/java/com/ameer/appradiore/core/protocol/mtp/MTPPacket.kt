package com.ameer.appradiore.core.protocol.mtp

/**
 * Representation of an address within an MTP packet.
 * Typically IPv4: 1 byte type (0x01) + 4 bytes IP + 2 bytes port (Big Endian).
 */
data class MTPAddress(
    val addressType: Byte = TYPE_IPV4,
    val ip: ByteArray = ByteArray(4) { 0 }, // 0.0.0.0
    val port: Int = 0
) {
    companion object {
        const val TYPE_NONE: Byte = 0
        const val TYPE_IPV4: Byte = 1
        const val TYPE_IPV6: Byte = 2

        val ANY_CONTROL = MTPAddress(TYPE_IPV4, ByteArray(4) { 0 }, MTPPacket.PORT_CONTROL_CHANNEL)
        val ANY_VIDEO = MTPAddress(TYPE_IPV4, ByteArray(4) { 0 }, MTPPacket.PORT_VIDEO_CHANNEL)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MTPAddress) return false
        return addressType == other.addressType && ip.contentEquals(other.ip) && port == other.port
    }

    override fun hashCode(): Int {
        var result = addressType.toInt()
        result = 31 * result + ip.contentHashCode()
        result = 31 * result + port
        return result
    }

    override fun toString(): String {
        val ipStr = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return "$ipStr:$port"
    }
}

/**
 * MTP (Multi-Transport Protocol) frame representing packets exchanged over USB AOA in Pioneer AppRadio Mode 2.
 */
data class MTPPacket(
    val messageType: Int = TYPE_DATA,
    val isLast: Boolean = true,
    val sourceProtocol: Int = PROTOCOL_TCP,
    val isCompressed: Boolean = false,
    val srcAddress: MTPAddress,
    val dstAddress: MTPAddress,
    val payload: ByteArray
) {
    companion object {
        const val FRAME_BEGIN: Byte = 0x1E  // 30
        const val FRAME_END: Byte = 0x03    // 3

        const val PORT_CONTROL_CHANNEL = 12347
        const val PORT_VIDEO_CHANNEL = 12346

        const val PROTOCOL_TCP = 0
        const val PROTOCOL_UDP = 1

        const val TYPE_DATA = 0
        const val TYPE_RESOLVE_ADDR = 1
        const val TYPE_OPEN_LISTEN_CONN = 2
        const val TYPE_CLOSE_LISTEN_CONN = 3
        const val TYPE_START_DGRAM_LISTEN = 4
        const val TYPE_STOP_DGRAM_LISTEN = 5

        /**
         * Creates a standard TCP data packet with the specified source, destination, and payload.
         */
        fun createDataPacket(
            srcAddress: MTPAddress,
            dstAddress: MTPAddress,
            payload: ByteArray,
            isLast: Boolean = true
        ): MTPPacket {
            return MTPPacket(
                messageType = TYPE_DATA,
                isLast = isLast,
                sourceProtocol = PROTOCOL_TCP,
                isCompressed = false,
                srcAddress = srcAddress,
                dstAddress = dstAddress,
                payload = payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MTPPacket) return false

        if (messageType != other.messageType) return false
        if (isLast != other.isLast) return false
        if (sourceProtocol != other.sourceProtocol) return false
        if (isCompressed != other.isCompressed) return false
        if (srcAddress != other.srcAddress) return false
        if (dstAddress != other.dstAddress) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageType
        result = 31 * result + isLast.hashCode()
        result = 31 * result + sourceProtocol
        result = 31 * result + isCompressed.hashCode()
        result = 31 * result + srcAddress.hashCode()
        result = 31 * result + dstAddress.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
