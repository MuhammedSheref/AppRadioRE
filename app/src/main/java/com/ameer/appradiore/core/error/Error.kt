package com.ameer.appradiore.core.error

/**
 * Root marker interface for all application errors.
 */
interface Error

/**
 * Errors originating from the hardware or protocol communication layers.
 */
sealed interface DataError : Error {

    enum class Usb : DataError {
        NO_ACCESSORY_FOUND,
        PERMISSION_DENIED,
        DESCRIPTOR_OPEN_FAILED,
        STREAM_CLOSED,
        IO_ERROR,
        UNKNOWN
    }

    enum class Protocol : DataError {
        CHECKSUM_MISMATCH,
        UNSUPPORTED_VERSION,
        AUTH_REJECTED,
        MALFORMED_PACKET,
        TIMEOUT
    }
}
