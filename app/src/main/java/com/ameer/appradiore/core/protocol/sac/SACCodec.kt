package com.ameer.appradiore.core.protocol.sac

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Encodes and decodes SAC (Pioneer proprietary) commands to and from byte buffers.
 * All primitive numbers in SAC payloads are Big-Endian.
 */
object SACCodec {

    /**
     * Serializes an outgoing (S2A) SACCommand into its raw binary payload.
     */
    fun encode(cmd: SACCommand): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        when (cmd) {
            is SACCommand.AuthBegin -> {
                dos.writeByte(SACCommand.AuthBegin.SUBTYPE.toInt())
            }
            is SACCommand.AuthEnd -> {
                dos.writeByte(SACCommand.AuthEnd.SUBTYPE.toInt())
                dos.writeByte(if (cmd.isSuccess) 1 else 0)
                dos.writeShort(cmd.majorVersion.toInt())
                dos.writeShort(cmd.minorVersion.toInt())
            }
            is SACCommand.StartAppAcc -> {
                dos.writeByte(SACCommand.StartAppAcc.SUBTYPE.toInt())
            }
            is SACCommand.EndAppAcc -> {
                dos.writeByte(SACCommand.EndAppAcc.SUBTYPE.toInt())
            }
            is SACCommand.StartAppInfoReply -> {
                dos.writeByte(SACCommand.StartAppInfoReply.SUBTYPE.toInt())
                dos.writeByte(cmd.status.toInt())
            }
            is SACCommand.EndAppInfoReply -> {
                dos.writeByte(SACCommand.EndAppInfoReply.SUBTYPE.toInt())
                dos.writeByte(cmd.status.toInt())
            }
            is SACCommand.StartAccessoryInfo -> {
                dos.writeByte(SACCommand.StartAccessoryInfo.SUBTYPE.toInt())
            }
            is SACCommand.EndAccessoryInfo -> {
                dos.writeByte(SACCommand.EndAccessoryInfo.SUBTYPE.toInt())
            }
            is SACCommand.RequestDisplayInfo -> {
                dos.writeByte(SACCommand.RequestDisplayInfo.SUBTYPE.toInt())
            }
            is SACCommand.RequestSpecInfo -> {
                dos.writeByte(SACCommand.RequestSpecInfo.SUBTYPE.toInt())
            }
            is SACCommand.VideoOutputReply -> {
                dos.writeByte(SACCommand.VideoOutputReply.SUBTYPE.toInt())
                dos.writeByte(SACCommand.VideoOutputReply.STATUS.toInt())
            }
            is SACCommand.RequestAccessoryStatus -> {
                dos.writeByte(SACCommand.RequestAccessoryStatus.SUBTYPE.toInt())
                dos.writeByte(0xFF)
            }
            is SACCommand.AppNameReply -> {
                dos.writeByte(SACCommand.AppNameReply.SUBTYPE.toInt())
                dos.writeShort(cmd.appToken.toInt())
                val nameBytes = (cmd.appName + "\u0000").toByteArray(StandardCharsets.UTF_8)
                dos.writeShort(nameBytes.size)
                dos.write(nameBytes)
            }
            is SACCommand.PackageNameReply -> {
                dos.writeByte(SACCommand.PackageNameReply.SUBTYPE.toInt())
                dos.writeShort(cmd.appToken.toInt())
                val pkgBytes = (cmd.packageName + "\u0000").toByteArray(StandardCharsets.UTF_8)
                dos.writeShort(pkgBytes.size)
                dos.write(pkgBytes)
            }
            is SACCommand.AppImageAcquisitionReply -> {
                dos.writeByte(SACCommand.AppImageAcquisitionReply.SUBTYPE.toInt())
                dos.writeShort(cmd.appToken.toInt())
                dos.writeByte(cmd.result.toInt())
                dos.writeInt(cmd.totalPayloadSize)
            }
            is SACCommand.AppImageChunk -> {
                dos.writeByte(SACCommand.AppImageChunk.SUBTYPE.toInt())
                dos.writeShort(cmd.appToken.toInt())
                dos.writeShort(cmd.chunkIndex.toInt())
                val len = minOf(cmd.chunkData.size, 512)
                dos.writeShort(len)
                dos.write(cmd.chunkData, 0, len)
            }
            is SACCommand.AppImageEnd -> {
                dos.writeByte(SACCommand.AppImageEnd.SUBTYPE.toInt())
                dos.writeShort(cmd.appToken.toInt())
                dos.writeByte(cmd.endType.toInt())
            }
            is SACCommand.AudioFocusReply -> {
                dos.writeByte(SACCommand.AudioFocusReply.SUBTYPE.toInt())
                dos.writeByte(cmd.result.toInt())
            }
            is SACCommand.TerminateSession -> {
                dos.writeByte(cmd.type.toInt())
            }
            is SACCommand.ScreenTransitionHome -> {
                dos.writeByte(SACCommand.ScreenTransitionHome.SUBTYPE.toInt())
                dos.writeByte(SACCommand.ScreenTransitionHome.KEYCODE.toInt())
            }
            is SACCommand.SmartPhoneStatus -> {
                dos.writeByte(cmd.statusType.toInt())
                dos.writeByte(cmd.hdmiPackage.toInt())
                dos.writeByte(cmd.soundCategory.toInt())
                dos.writeByte(0)
                dos.writeShort(cmd.appToken.toInt())
                dos.writeByte(0)
            }
            is SACCommand.UnknownSACCommand -> {
                dos.write(cmd.rawPayload)
            }
            else -> {
                // S2A command without custom fields
            }
        }
        return bos.toByteArray()
    }

    /**
     * Parses an incoming (A2S) SAC command based on its opcode and payload buffer.
     */
    fun decode(opcode: Byte, payload: ByteArray): SACCommand {
        if (opcode == SACCommand.OP_A2S_VEDIO_OUTPUT) {
            return SACCommand.VideoOutputRequest
        }

        if (payload.isEmpty()) {
            return SACCommand.UnknownSACCommand(opcode, payload)
        }

        val dis = DataInputStream(ByteArrayInputStream(payload))
        return try {
            when (opcode) {
                SACCommand.OP_A2S_AUTH -> decodeAuth(dis, payload)
                SACCommand.OP_A2S_PROC_SPEC -> decodeProcSpec(dis, payload)
                SACCommand.OP_A2S_PACKAGEINFO -> decodePackageInfo(dis, payload)
                SACCommand.OP_A2S_NOTIFYREQUEST -> decodeNotifyRequest(dis, payload)
                SACCommand.OP_A2S_VEDIO_OUTPUT -> SACCommand.VideoOutputRequest
                SACCommand.OP_A2S_APPINFO_REQUEST -> decodeAppInfoRequest(dis, payload)
                SACCommand.OP_A2S_APPIMAGE_TRANSFER_REQUEST -> decodeAppImageRequest(dis, payload)
                SACCommand.OP_A2S_REMOTECTRL -> decodeRemoteCtrl(dis, payload)
                SACCommand.OP_A2S_APPS -> decodeApps(dis, payload)
                SACCommand.OP_A2S_KEY -> SACCommand.StereoKeyEvent(payload)
                else -> SACCommand.UnknownSACCommand(opcode, payload)
            }
        } catch (e: Exception) {
            SACCommand.UnknownSACCommand(opcode, payload)
        }
    }

    private fun decodeNotifyRequest(dis: DataInputStream, raw: ByteArray): SACCommand {
        val statusType = if (dis.available() > 0) dis.readByte() else 0x20.toByte()
        return SACCommand.RequestPhoneStatus(statusType)
    }

    private fun decodeAuth(dis: DataInputStream, raw: ByteArray): SACCommand {
        val subtype = dis.readByte()
        return when (subtype.toInt()) {
            0 -> { // AUTH_RESPONSE
                val result = dis.readByte()
                val majorVer = if (dis.available() >= 2) dis.readShort() else 0.toShort()
                val minorVer = if (dis.available() >= 2) dis.readShort() else 0.toShort()
                SACCommand.AuthResponse(result, majorVer, minorVer)
            }
            16 -> SACCommand.StartAppAccReply(if (dis.available() > 0) dis.readByte() else 1)
            17 -> SACCommand.EndAppAccReply(if (dis.available() > 0) dis.readByte() else 1)
            18 -> SACCommand.StartAppInfo
            19 -> SACCommand.EndAppInfo
            20 -> SACCommand.StartAccessoryInfoReply(if (dis.available() > 0) dis.readByte() else 1)
            21 -> SACCommand.EndAccessoryInfoReply(if (dis.available() > 0) dis.readByte() else 1)
            else -> SACCommand.UnknownSACCommand(SACCommand.OP_A2S_AUTH, raw)
        }
    }

    private fun decodeProcSpec(dis: DataInputStream, raw: ByteArray): SACCommand {
        val subtype = dis.readByte()
        return when (subtype.toInt()) {
            0 -> { // DISPLAY_INFO: [subtype(1B), width(2B), height(2B), physicalWidth(2B), physicalHeight(2B), pad(4B)]
                val width = dis.readShort().toInt() and 0xFFFF
                val height = dis.readShort().toInt() and 0xFFFF
                val physicalWidth = if (dis.available() >= 2) dis.readShort().toInt() and 0xFFFF else width
                val physicalHeight = if (dis.available() >= 2) dis.readShort().toInt() and 0xFFFF else height
                SACCommand.DisplaySpecInfo(width, height)
            }
            1 -> { // SPEC_INFO
                val modelId = dis.readShort()
                val pointers = dis.readByte()
                val gps = dis.readByte()
                val remoteCtrl = dis.readByte()
                val canBus = dis.readByte()
                val flagByte = dis.readByte()
                val isCalib = ((flagByte.toInt() shr 7) and 1) != 0 || ((flagByte.toInt() shr 1) and 1) == 0
                SACCommand.ProductSpecInfo(
                    modelId = modelId,
                    pointerCount = pointers,
                    hasGps = gps.toInt() != 0,
                    hasRemoteControl = remoteCtrl.toInt() != 0,
                    hasCanBus = canBus.toInt() != 0,
                    isCalibrationTouch = isCalib
                )
            }
            else -> SACCommand.UnknownSACCommand(SACCommand.OP_A2S_PROC_SPEC, raw)
        }
    }

    private fun decodePackageInfo(dis: DataInputStream, raw: ByteArray): SACCommand {
        val subtype = dis.readByte()
        return if (subtype.toInt() == 32) {
            val flags = dis.readByte()
            SACCommand.AccessoryStatus(
                isParkingBrakeOn = (flags.toInt() and 1) != 0,
                isHdmiConnected = (flags.toInt() and 2) != 0,
                isVrActive = (flags.toInt() and 4) != 0,
                rawFlags = flags
            )
        } else {
            SACCommand.UnknownSACCommand(SACCommand.OP_A2S_PACKAGEINFO, raw)
        }
    }

    private fun decodeAppInfoRequest(dis: DataInputStream, raw: ByteArray): SACCommand {
        val reqType = dis.readByte()
        val token = dis.readShort()
        return SACCommand.AppInfoRequest(reqType, token)
    }

    private fun decodeAppImageRequest(dis: DataInputStream, raw: ByteArray): SACCommand {
        val subType = dis.readByte()
        val token = dis.readShort()
        return when (subType.toInt()) {
            0 -> {
                val kind = dis.readByte()
                val type = dis.readByte()
                val w = dis.readShort()
                val h = dis.readShort()
                SACCommand.AppImageRequest(subType, token, kind, type, w, h)
            }
            1 -> SACCommand.AppImageRequest(subType, token)
            2 -> {
                val ackIndex = dis.readShort()
                SACCommand.AppImageRequest(subType, token, ackChunkIndex = ackIndex)
            }
            3, 4 -> SACCommand.AppImageRequest(subType, token)
            else -> SACCommand.UnknownSACCommand(SACCommand.OP_A2S_APPIMAGE_TRANSFER_REQUEST, raw)
        }
    }

    private fun decodeRemoteCtrl(dis: DataInputStream, raw: ByteArray): SACCommand {
        val sub = dis.readByte()
        val value = dis.readByte()
        return when (sub.toInt()) {
            0 -> SACCommand.RemoteControlCommand(value)
            1 -> SACCommand.AudioFocusRequest
            else -> SACCommand.UnknownSACCommand(SACCommand.OP_A2S_REMOTECTRL, raw)
        }
    }

    private fun decodeApps(dis: DataInputStream, raw: ByteArray): SACCommand {
        val type = dis.readByte()
        return when (type.toInt()) {
            0 -> SACCommand.AppLaunchRequest(launchType = 0)
            1 -> {
                val avail = dis.available()
                val pkgBytes = ByteArray(avail)
                dis.read(pkgBytes)
                var pkgStr = String(pkgBytes, StandardCharsets.UTF_8)
                if (pkgStr.endsWith("\u0000")) pkgStr = pkgStr.dropLast(1)
                SACCommand.AppLaunchRequest(launchType = 1, packageName = pkgStr)
            }
            2 -> {
                val token = dis.readShort()
                SACCommand.AppLaunchRequest(launchType = 2, appToken = token)
            }
            else -> SACCommand.UnknownSACCommand(SACCommand.OP_A2S_APPS, raw)
        }
    }
}
