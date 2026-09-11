package com.ameer.appradiore.core.protocol.sac

/**
 * Sealed hierarchy of all Pioneer SAC (Smartphone to Accessory Communication) commands.
 */
sealed class SACCommand {
    abstract val opcode: Byte

    // ==========================================
    // Smartphone to Accessory (S2A) Commands
    // ==========================================

    data object AuthBegin : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        const val SUBTYPE: Byte = 0
    }

    data class AuthEnd(
        val isSuccess: Boolean = true,
        val majorVersion: Short = 3,
        val minorVersion: Short = 1
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        companion object {
            const val SUBTYPE: Byte = 1
        }
    }

    data object StartAppAcc : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        const val SUBTYPE: Byte = 16
    }

    data object EndAppAcc : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        const val SUBTYPE: Byte = 17
    }

    data class StartAppInfoReply(val status: Byte = 1) : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        companion object {
            const val SUBTYPE: Byte = 18
        }
    }

    data class EndAppInfoReply(val status: Byte = 1) : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        companion object {
            const val SUBTYPE: Byte = 19
        }
    }

    data object StartAccessoryInfo : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        const val SUBTYPE: Byte = 20
    }

    data object EndAccessoryInfo : SACCommand() {
        override val opcode: Byte = OP_S2A_AUTH
        const val SUBTYPE: Byte = 21
    }

    data object RequestDisplayInfo : SACCommand() {
        override val opcode: Byte = OP_S2A_PROC_SPEC
        const val SUBTYPE: Byte = 0
    }

    data object RequestSpecInfo : SACCommand() {
        override val opcode: Byte = OP_S2A_PROC_SPEC
        const val SUBTYPE: Byte = 1
    }

    data object VideoOutputReply : SACCommand() {
        override val opcode: Byte = OP_S2A_VEDIO_OUTPUT_REPLY
        const val SUBTYPE: Byte = 6
        const val STATUS: Byte = 1
    }

    data object RequestAccessoryStatus : SACCommand() {
        override val opcode: Byte = OP_S2A_ACCESSORY_STATUS
        const val SUBTYPE: Byte = 32
    }

    data class AppNameReply(
        val appToken: Short,
        val appName: String
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_APPNINFO_RELY
        companion object {
            const val SUBTYPE: Byte = 1
        }
    }

    data class PackageNameReply(
        val appToken: Short,
        val packageName: String
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_APPNINFO_RELY
        companion object {
            const val SUBTYPE: Byte = 2
        }
    }

    data class AppImageAcquisitionReply(
        val appToken: Short,
        val result: Byte = 0,
        val totalPayloadSize: Int
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_APPIMAGE_TRANSFER
        companion object {
            const val SUBTYPE: Byte = 0
        }
    }

    data class AppImageChunk(
        val appToken: Short,
        val chunkIndex: Short,
        val chunkData: ByteArray
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_APPIMAGE_TRANSFER
        companion object {
            const val SUBTYPE: Byte = 2
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AppImageChunk
            if (appToken != other.appToken) return false
            if (chunkIndex != other.chunkIndex) return false
            return chunkData.contentEquals(other.chunkData)
        }

        override fun hashCode(): Int {
            var result = appToken.toInt()
            result = 31 * result + chunkIndex.toInt()
            result = 31 * result + chunkData.contentHashCode()
            return result
        }
    }

    data class AppImageEnd(
        val appToken: Short,
        val endType: Byte = 0 // 0 = OK, 1 = Fail
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_APPIMAGE_TRANSFER
        companion object {
            const val SUBTYPE: Byte = 3
        }
    }

    data class AudioFocusReply(
        val result: Byte = 1 // 1 = Success, 0 = Fail
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_AUDIOFOCUS_REPLY
        companion object {
            const val SUBTYPE: Byte = 1
        }
    }

    data class TerminateSession(
        val type: Byte = 0
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_TERMINATION
    }

    // ==========================================
    // Accessory to Smartphone (A2S) Commands
    // ==========================================

    data class AuthResponse(
        val result: Byte, // accessoryType: 2 = AppRadio, 3 = MediaDEH, 8 = Linkwith AAM2
        val majorVersion: Short,
        val minorVersion: Short
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
        val isSuccess: Boolean get() = result.toInt() == 2 || result.toInt() == 3 || result.toInt() == 8 || result.toInt() != 0 || majorVersion > 0
    }

    data class StartAppAccReply(val result: Byte) : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data class EndAppAccReply(val result: Byte) : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data object StartAppInfo : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data object EndAppInfo : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data class StartAccessoryInfoReply(val result: Byte) : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data class EndAccessoryInfoReply(val result: Byte) : SACCommand() {
        override val opcode: Byte = OP_A2S_AUTH
    }

    data class DisplaySpecInfo(
        val width: Int,
        val height: Int
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_PROC_SPEC
    }

    data class ProductSpecInfo(
        val modelId: Short,
        val pointerCount: Byte,
        val hasGps: Boolean,
        val hasRemoteControl: Boolean,
        val hasCanBus: Boolean,
        val isCalibrationTouch: Boolean
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_PROC_SPEC
    }

    data class AccessoryStatus(
        val isParkingBrakeOn: Boolean,
        val isHdmiConnected: Boolean,
        val isVrActive: Boolean,
        val rawFlags: Byte
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_PACKAGEINFO
    }

    data object VideoOutputRequest : SACCommand() {
        override val opcode: Byte = OP_A2S_VEDIO_OUTPUT
    }

    data class AppInfoRequest(
        val requestType: Byte, // 1 = App Name, 2 = Package Name
        val appToken: Short
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_APPINFO_REQUEST
    }

    data class AppImageRequest(
        val subType: Byte,
        val appToken: Short,
        val imageKind: Byte = 16, // 16 = APPICON
        val imageType: Byte = 18, // 18 = PNG
        val width: Short = 0,
        val height: Short = 0,
        val ackChunkIndex: Short = 0
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_APPIMAGE_TRANSFER_REQUEST
    }

    data class RemoteControlCommand(
        val commandId: Byte // 0=Toggle, 1=Play, 2=Pause, 3=TrackUp, 4=TrackDown, 5=FF, 6=RW
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_REMOTECTRL
    }

    data object AudioFocusRequest : SACCommand() {
        override val opcode: Byte = OP_A2S_REMOTECTRL
    }

    data class AppLaunchRequest(
        val launchType: Byte, // 0 = Home, 1 = Package, 2 = Token
        val packageName: String? = null,
        val appToken: Short? = null
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_APPS
    }

    data object ScreenTransitionHome : SACCommand() {
        override val opcode: Byte = OP_S2A_SCREEN
        const val SUBTYPE: Byte = 0
        const val KEYCODE: Byte = 0
    }

    data class StereoKeyEvent(
        val rawPayload: ByteArray
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_KEY

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StereoKeyEvent
            return rawPayload.contentEquals(other.rawPayload)
        }

        override fun hashCode(): Int {
            return rawPayload.contentHashCode()
        }
    }

    data class RequestPhoneStatus(
        val statusType: Byte = 0x20 // 0x20 = AccessoryStatus/Park, 0x21 = Media
    ) : SACCommand() {
        override val opcode: Byte = OP_A2S_NOTIFYREQUEST
    }

    data class SmartPhoneStatus(
        val statusType: Byte = 0x20,
        val hdmiPackage: Byte = 0x02, // 2 = AppRadio in foreground (FrontAplMonitor.java)
        val soundCategory: Byte = 0x01,
        val appToken: Short = 1
    ) : SACCommand() {
        override val opcode: Byte = OP_S2A_NOTIFICATION
    }

    data class UnknownSACCommand(
        override val opcode: Byte,
        val rawPayload: ByteArray
    ) : SACCommand() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as UnknownSACCommand
            if (opcode != other.opcode) return false
            return rawPayload.contentEquals(other.rawPayload)
        }

        override fun hashCode(): Int {
            var result = opcode.toInt()
            result = 31 * result + rawPayload.contentHashCode()
            return result
        }
    }

    companion object {
        const val OP_S2A_AUTH: Byte = 0
        const val OP_A2S_AUTH: Byte = 1
        const val OP_S2A_TERMINATION: Byte = 2
        const val OP_A2S_TERMINATION: Byte = 3
        const val OP_S2A_PROC_SPEC: Byte = 6
        const val OP_A2S_PROC_SPEC: Byte = 7
        const val OP_S2A_VEDIO_OUTPUT_REPLY: Byte = 16
        const val OP_A2S_VEDIO_OUTPUT: Byte = 17
        const val OP_A2S_KEY: Byte = 65
        const val OP_S2A_KEY: Byte = 66
        const val OP_A2S_TOUCH: Byte = 67
        const val OP_S2A_AUDIOFOCUS_REPLY: Byte = 80
        const val OP_A2S_REMOTECTRL: Byte = 81
        const val OP_S2A_SCREEN: Byte = 82
        const val OP_A2S_APPS: Byte = 83
        const val OP_S2A_ACCESSORY_STATUS: Byte = 96
        const val OP_A2S_PACKAGEINFO: Byte = 97
        const val OP_A2S_NOTIFYREQUEST: Byte = 98 // 0x62
        const val OP_S2A_NOTIFICATION: Byte = 99  // 0x63
        const val OP_S2A_APPNINFO_RELY: Byte = 112
        const val OP_A2S_APPINFO_REQUEST: Byte = 113
        const val OP_S2A_TRACKNFO_RELY: Byte = 114
        const val OP_A2S_TRACKNFO_REQUEST: Byte = 115
        const val OP_S2A_APPIMAGE_TRANSFER: Byte = 120
        const val OP_A2S_APPIMAGE_TRANSFER_REQUEST: Byte = 121
    }
}
