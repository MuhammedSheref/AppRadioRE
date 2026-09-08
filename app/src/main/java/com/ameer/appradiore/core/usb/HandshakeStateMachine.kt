package com.ameer.appradiore.core.usb

import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.protocol.mtp.MTPAddress
import com.ameer.appradiore.core.protocol.mtp.MTPCodec
import com.ameer.appradiore.core.protocol.mtp.MTPPacket
import com.ameer.appradiore.core.protocol.pformat.PFormatCodec
import com.ameer.appradiore.core.protocol.pformat.PFormatPacket
import com.ameer.appradiore.core.protocol.sac.SACCodec
import com.ameer.appradiore.core.protocol.sac.SACCommand
import com.ameer.appradiore.core.protocol.weblink.WebLinkCodec
import com.ameer.appradiore.core.protocol.weblink.WebLinkCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

enum class HandshakeStep(val description: String) {
    DISCONNECTED("Disconnected"),
    STEP_0_AUTH_BEGIN("Step 0: Sending Auth Begin"),
    STEP_0_AUTH_END("Step 0: Auth Verified & Acknowledged"),
    STEP_1_START_APP_ACC("Kind 4: Starting App Accessory"),
    STEP_2_START_ACC_INFO("Kind 5: Starting Accessory Info Exchange"),
    STEP_3_REQUEST_SPEC("Kind 2: Requesting Product Spec"),
    STEP_4_REQUEST_DISPLAY("Kind 1: Requesting Display Specs"),
    STEP_5_REQUEST_STATUS("Kind 3: Requesting Accessory Status"),
    STEP_6_END_ACC_INFO("Kind 6: Ending Accessory Info"),
    CONNECTED_READY("Handshake Complete (Ready for Mirroring)"),
    FAILED("Handshake Failed")
}

interface HandshakeStateMachine {
    val currentStep: StateFlow<HandshakeStep>
    val stereoSpecs: StateFlow<StereoSpecs>

    fun startHandshake()
    fun reset()
    fun simulateHandshake()
}

class HandshakeStateMachineImpl(
    private val usbAccessoryManager: UsbAccessoryManager,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope
) : HandshakeStateMachine {

    private val _currentStep = MutableStateFlow(HandshakeStep.DISCONNECTED)
    override val currentStep: StateFlow<HandshakeStep> = _currentStep.asStateFlow()

    private val _stereoSpecs = MutableStateFlow(StereoSpecs())
    override val stereoSpecs: StateFlow<StereoSpecs> = _stereoSpecs.asStateFlow()

    private var incomingCollectorJob: Job? = null
    private var heartbeatJob: Job? = null
    private var authJob: Job? = null
    private var isMtpMode: Boolean = true
    private var stereoControlPort: Int = MTPPacket.PORT_CONTROL_CHANNEL
    private var stereoAddress: MTPAddress = MTPAddress.ANY_CONTROL
    private val packetBuffer = ByteArrayOutputStream()

    init {
        scope.launch {
            usbAccessoryManager.connectionState.collect { state ->
                when (state) {
                    is UsbConnectionState.Connected -> {
                        startHandshake()
                    }
                    is UsbConnectionState.Disconnected, is UsbConnectionState.Error -> {
                        reset()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun startHandshake() {
        reset()
        isMtpMode = true
        incomingCollectorJob = scope.launch {
            usbAccessoryManager.incomingBytes.collect { rawBytes ->
                processIncomingBytes(rawBytes)
            }
        }

        authJob = scope.launch {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "Pioneer AAM2 Transport Initialized. Waiting 1000ms for stereo readiness..."
            )
            delay(1000)

            _currentStep.value = HandshakeStep.STEP_0_AUTH_BEGIN

            // Retry loop matching Pioneer's AccessoryAuthor: up to 3 attempts, 3000ms apart
            var attempt = 1
            while (isActive && attempt <= 3 && _currentStep.value == HandshakeStep.STEP_0_AUTH_BEGIN) {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SYSTEM,
                    summary = "Sending AuthBegin (Attempt $attempt of 3)..."
                )
                sendSacCommand(SACCommand.AuthBegin)
                attempt++
                delay(3000)
            }

            if (_currentStep.value == HandshakeStep.STEP_0_AUTH_BEGIN) {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SYSTEM,
                    summary = "No AuthResponse received after 3 attempts. Stereo may require user confirmation or manual display mode switch.",
                    isError = true
                )
            }
        }
    }

    override fun reset() {
        incomingCollectorJob?.cancel()
        incomingCollectorJob = null
        authJob?.cancel()
        authJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        packetBuffer.reset()
        isMtpMode = true
        stereoControlPort = MTPPacket.PORT_CONTROL_CHANNEL
        stereoAddress = MTPAddress.ANY_CONTROL
        _currentStep.value = HandshakeStep.DISCONNECTED
        _stereoSpecs.value = StereoSpecs()
    }

    private suspend fun sendSacCommand(cmd: SACCommand) {
        val payload = SACCodec.encode(cmd)
        val pFormatFramed = PFormatCodec.encode(cmd.opcode, payload)
        val pFormatHex = pFormatFramed.joinToString(" ") { String.format("%02X", it) }

        logRepository.log(
            direction = LogDirection.OUTGOING,
            protocol = ProtocolType.SAC,
            summary = "TX SAC [Opcode 0x${String.format("%02X", cmd.opcode)}]: ${cmd::class.simpleName}",
            rawHex = pFormatHex,
            details = cmd.toString()
        )

        val wireBytes = if (isMtpMode) {
            val mtpBytes = MTPCodec.wrapControlChannelPayload(
                payload = pFormatFramed,
                srcPort = MTPPacket.PORT_CONTROL_CHANNEL,
                dstPort = stereoControlPort
            )
            val mtpHex = mtpBytes.take(64).joinToString(" ") { String.format("%02X", it) }
            val suffix = if (mtpBytes.size > 64) " ... (${mtpBytes.size}B total)" else ""
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.MTP,
                summary = "TX MTP Control Channel Packet (Port $stereoControlPort, ${mtpBytes.size}B)",
                rawHex = mtpHex + suffix
            )
            mtpBytes
        } else {
            pFormatFramed
        }

        usbAccessoryManager.send(wireBytes)
    }

    private suspend fun processIncomingBytes(bytes: ByteArray) {
        packetBuffer.write(bytes)
        val currentBuffer = packetBuffer.toByteArray()

        // 1. Try MTP framing (0x1E ... 0x03)
        val mtpResult = MTPCodec.decode(currentBuffer)
        if (mtpResult.packets.isNotEmpty()) {
            packetBuffer.reset()
            if (mtpResult.unconsumedBytes.isNotEmpty()) {
                packetBuffer.write(mtpResult.unconsumedBytes)
            }
            for (packet in mtpResult.packets) {
                handleMtpPacket(packet)
            }
            return
        }

        // 2. Try bare PFormat framing (0x9F ... 0x9F 0x03)
        val pFormatPackets = PFormatCodec.decode(currentBuffer)
        if (pFormatPackets.isNotEmpty()) {
            packetBuffer.reset()
            for (packet in pFormatPackets) {
                handlePFormatPacket(packet)
            }
            return
        }

        // 3. Try WebLink framing
        val webLinkCommands = WebLinkCodec.decode(currentBuffer)
        if (webLinkCommands.isNotEmpty()) {
            packetBuffer.reset()
            for (cmd in webLinkCommands) {
                handleWebLinkCommand(cmd)
            }
            return
        }

        // Prevent buffer from growing unbounded if corrupted
        if (packetBuffer.size() > 65536) {
            val trimmed = currentBuffer.takeLast(4096).toByteArray()
            packetBuffer.reset()
            packetBuffer.write(trimmed)
        }
    }

    private suspend fun handleMtpPacket(packet: MTPPacket) {
        val proto = if (packet.sourceProtocol == MTPPacket.PROTOCOL_TCP) "TCP" else "UDP"
        val mtpHex = packet.payload.take(64).joinToString(" ") { String.format("%02X", it) }
        val suffix = if (packet.payload.size > 64) " ... (${packet.payload.size}B total)" else ""
        logRepository.log(
            direction = LogDirection.INCOMING,
            protocol = ProtocolType.MTP,
            summary = "RX MTP $proto (Src: ${packet.srcAddress}, Dst: ${packet.dstAddress}, Payload: ${packet.payload.size}B)",
            rawHex = mtpHex + suffix
        )

        isMtpMode = true
        // Keep stereoControlPort locked to PORT_CONTROL_CHANNEL (12347)
        stereoControlPort = MTPPacket.PORT_CONTROL_CHANNEL
        if (packet.srcAddress.port == MTPPacket.PORT_CONTROL_CHANNEL) {
            stereoAddress = packet.srcAddress
        }

        val isControl = packet.dstAddress.port == MTPPacket.PORT_CONTROL_CHANNEL || packet.srcAddress.port == MTPPacket.PORT_CONTROL_CHANNEL
        val isVideo = packet.dstAddress.port == MTPPacket.PORT_VIDEO_CHANNEL || packet.srcAddress.port == MTPPacket.PORT_VIDEO_CHANNEL

        if (packet.payload.isEmpty()) {
            // Channel SYN / Connection request from stereo
            val channelName = if (isVideo) "Video Channel (Port 12346)" else "Control Channel (Port 12347)"
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.MTP,
                summary = "$channelName connection requested by stereo. Sending ACK..."
            )
            val ackPort = if (isVideo) MTPPacket.PORT_VIDEO_CHANNEL else MTPPacket.PORT_CONTROL_CHANNEL
            val ack = MTPCodec.createConnectionAck(
                srcAddress = MTPAddress(MTPAddress.TYPE_IPV4, ByteArray(4) { 0 }, ackPort),
                dstAddress = packet.srcAddress
            )
            usbAccessoryManager.send(ack)
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.MTP,
                summary = "TX MTP Connection ACK sent to ${packet.srcAddress}"
            )
            return
        }

        // Non-empty payload: could be WebLink ('WL' = 0x57, 0x4C) or PFormat (0x9F)
        val activeChannelPort = if (isVideo) MTPPacket.PORT_VIDEO_CHANNEL else MTPPacket.PORT_CONTROL_CHANNEL

        if (packet.payload.size >= 2 && packet.payload[0] == WebLinkCommand.MAGIC_BYTE1 && packet.payload[1] == WebLinkCommand.MAGIC_BYTE2) {
            val webLinkCommands = WebLinkCodec.decode(packet.payload)
            if (webLinkCommands.isNotEmpty()) {
                for (cmd in webLinkCommands) {
                    handleWebLinkCommand(cmd, channelPort = activeChannelPort, srcAddr = packet.srcAddress)
                }
            } else {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.WEBLINK,
                    summary = "Failed to parse WebLink command payload (${packet.payload.size} bytes)",
                    rawHex = packet.payload.take(64).joinToString(" ") { String.format("%02X", it) }
                )
            }
        } else if (packet.payload.isNotEmpty() && packet.payload[0] == PFormatCodec.ESC) {
            val pFormatPackets = PFormatCodec.decode(packet.payload)
            if (pFormatPackets.isNotEmpty()) {
                for (pPacket in pFormatPackets) {
                    handlePFormatPacket(pPacket)
                }
            } else {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SAC,
                    summary = "Incomplete/corrupted PFormat frame inside MTP (${packet.payload.size} bytes)",
                    rawHex = packet.payload.take(64).joinToString(" ") { String.format("%02X", it) }
                )
            }
        } else {
            val channelName = if (isVideo) "Video Channel" else "Control Channel"
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.MTP,
                summary = "Unrecognized payload in $channelName (${packet.payload.size} bytes)",
                rawHex = packet.payload.take(64).joinToString(" ") { String.format("%02X", it) }
            )
        }
    }

    private suspend fun handlePFormatPacket(packet: PFormatPacket) {
        val hex = packet.toHexString()
        val sacCmd = SACCodec.decode(packet.commandId, packet.payload)

        logRepository.log(
            direction = LogDirection.INCOMING,
            protocol = ProtocolType.SAC,
            summary = "RX SAC [Opcode 0x${String.format("%02X", packet.commandId)}]: ${sacCmd::class.simpleName}",
            rawHex = hex,
            details = sacCmd.toString()
        )

        when (sacCmd) {
            is SACCommand.AuthResponse -> {
                authJob?.cancel()
                authJob = null
                if (sacCmd.result == 0.toByte()) {
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.SAC,
                        summary = "Auth Succeeded! Stereo Version: ${sacCmd.majorVersion}.${sacCmd.minorVersion}"
                    )
                    _currentStep.value = HandshakeStep.STEP_0_AUTH_END
                    // Send AuthEnd confirmation
                    sendSacCommand(SACCommand.AuthEnd(isSuccess = true, majorVersion = sacCmd.majorVersion, minorVersion = sacCmd.minorVersion))

                    // Step 1: StartAppAcc
                    _currentStep.value = HandshakeStep.STEP_1_START_APP_ACC
                    sendSacCommand(SACCommand.StartAppAcc)
                } else {
                    _currentStep.value = HandshakeStep.FAILED
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.SAC,
                        summary = "Auth Failed with code ${sacCmd.result}",
                        isError = true
                    )
                }
            }
            is SACCommand.StartAppAccReply -> {
                _currentStep.value = HandshakeStep.STEP_2_START_ACC_INFO
                sendSacCommand(SACCommand.StartAccessoryInfo)
            }
            is SACCommand.StartAccessoryInfoReply -> {
                _currentStep.value = HandshakeStep.STEP_3_REQUEST_SPEC
                sendSacCommand(SACCommand.RequestSpecInfo)
            }
            is SACCommand.ProductSpecInfo -> {
                _stereoSpecs.value = _stereoSpecs.value.copy(
                    modelId = sacCmd.modelId,
                    pointerCount = sacCmd.pointerCount,
                    hasGps = sacCmd.hasGps,
                    hasRemoteControl = sacCmd.hasRemoteControl
                )
                _currentStep.value = HandshakeStep.STEP_4_REQUEST_DISPLAY
                sendSacCommand(SACCommand.RequestDisplayInfo)
            }
            is SACCommand.DisplaySpecInfo -> {
                _stereoSpecs.value = _stereoSpecs.value.copy(
                    width = sacCmd.width,
                    height = sacCmd.height
                )
                _currentStep.value = HandshakeStep.STEP_5_REQUEST_STATUS
                sendSacCommand(SACCommand.RequestAccessoryStatus)
            }
            is SACCommand.AccessoryStatus -> {
                _stereoSpecs.value = _stereoSpecs.value.copy(
                    isParkingBrakeOn = sacCmd.isParkingBrakeOn,
                    isHdmiConnected = sacCmd.isHdmiConnected
                )
                _currentStep.value = HandshakeStep.STEP_6_END_ACC_INFO
                sendSacCommand(SACCommand.EndAccessoryInfo)
            }
            is SACCommand.EndAccessoryInfoReply -> {
                _currentStep.value = HandshakeStep.CONNECTED_READY
                _stereoSpecs.value = _stereoSpecs.value.copy(isReadyForVideo = true)
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SYSTEM,
                    summary = "Handshake successfully completed! Stereo is ready for mirroring."
                )
                startHeartbeat()
            }
            is SACCommand.VideoOutputRequest -> {
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SAC,
                    summary = "Received VideoOutputRequest from stereo -> Replying OK"
                )
                sendSacCommand(SACCommand.VideoOutputReply)
            }
            else -> Unit
        }
    }

    private suspend fun handleWebLinkCommand(
        cmd: WebLinkCommand,
        channelPort: Int = MTPPacket.PORT_VIDEO_CHANNEL,
        srcAddr: MTPAddress? = null
    ) {
        logRepository.log(
            direction = LogDirection.INCOMING,
            protocol = ProtocolType.WEBLINK,
            summary = "RX WebLink: ${cmd::class.simpleName} (ID: 0x${String.format("%04X", cmd.commandId)})",
            details = cmd.toString()
        )

        when (cmd) {
            is WebLinkCommand.DisplayMetrics -> {
                _stereoSpecs.value = _stereoSpecs.value.copy(dpi = cmd.xdpi)
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.WEBLINK,
                    summary = "Stereo Display Metrics: ${cmd.xdpi}x${cmd.ydpi} DPI"
                )
            }
            is WebLinkCommand.SyncSessionTime -> {
                val serverTime = System.currentTimeMillis()
                val reply = WebLinkCommand.SyncSessionTime(
                    clientTime = cmd.clientTime,
                    serverTime = serverTime
                )
                val replyBytes = WebLinkCodec.encode(reply)
                val replyHex = replyBytes.joinToString(" ") { String.format("%02X", it) }
                logRepository.log(
                    direction = LogDirection.OUTGOING,
                    protocol = ProtocolType.WEBLINK,
                    summary = "TX WebLink: SyncSessionTime Reply (clientTime=${cmd.clientTime}, serverTime=$serverTime)",
                    rawHex = replyHex
                )
                if (isMtpMode) {
                    val wireBytes = MTPCodec.wrapPayload(
                        payload = replyBytes,
                        srcPort = channelPort,
                        dstPort = channelPort
                    )
                    usbAccessoryManager.send(wireBytes)
                } else {
                    usbAccessoryManager.send(replyBytes)
                }
            }
            is WebLinkCommand.VideoConfig -> {
                val w = cmd.clientWidth.takeIf { it > 0 } ?: cmd.sourceWidth
                val h = cmd.clientHeight.takeIf { it > 0 } ?: cmd.sourceHeight
                _stereoSpecs.value = _stereoSpecs.value.copy(
                    width = w,
                    height = h,
                    isReadyForVideo = true
                )

                // Cancel auth retry loop since stereo established WebLink session
                authJob?.cancel()
                authJob = null

                // Reply confirming video config: 800x480 H.264
                val reply = WebLinkCommand.VideoConfig(
                    sourceWidth = cmd.sourceWidth,
                    sourceHeight = cmd.sourceHeight,
                    clientWidth = cmd.clientWidth,
                    clientHeight = cmd.clientHeight,
                    frameEncoding = 2, // H.264
                    encoderParams = "maxKeyFrameInterval=60,bitrate=8388608"
                )
                val replyBytes = WebLinkCodec.encode(reply)
                val replyHex = replyBytes.take(64).joinToString(" ") { String.format("%02X", it) }
                logRepository.log(
                    direction = LogDirection.OUTGOING,
                    protocol = ProtocolType.WEBLINK,
                    summary = "TX WebLink: VideoConfig Confirm (${w}x${h}, H.264 @ 8Mbps)",
                    rawHex = replyHex
                )
                if (isMtpMode) {
                    val wireBytes = MTPCodec.wrapPayload(
                        payload = replyBytes,
                        srcPort = channelPort,
                        dstPort = channelPort
                    )
                    usbAccessoryManager.send(wireBytes)
                } else {
                    usbAccessoryManager.send(replyBytes)
                }

                _currentStep.value = HandshakeStep.CONNECTED_READY
                val dpiText = if (_stereoSpecs.value.dpi > 0) " @ ${_stereoSpecs.value.dpi} DPI" else ""
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SYSTEM,
                    summary = "=== HANDSHAKE COMPLETE: Stereo Display Unlocked (${w}x${h}$dpiText) ==="
                )
                startHeartbeat()
            }
            is WebLinkCommand.Touch -> {
                val pt = cmd.points.firstOrNull()
                val ptStr = if (pt != null) " at (${pt.x}, ${pt.y})" else ""
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.WEBLINK,
                    summary = "Stereo Touch Event: type=${cmd.eventType}$ptStr"
                )
            }
            else -> Unit
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5000)
                // Heartbeat packet / status query (SAC only)
                if (_currentStep.value == HandshakeStep.CONNECTED_READY && !isMtpMode) {
                    sendSacCommand(SACCommand.RequestAccessoryStatus)
                }
            }
        }
    }

    override fun simulateHandshake() {
        reset()
        isMtpMode = false
        scope.launch {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "=== SIMULATION STARTED: Pioneer SPH-DA120 / AVH Handshake ==="
            )

            // Step 0: Auth Begin
            _currentStep.value = HandshakeStep.STEP_0_AUTH_BEGIN
            val authBeginPacket = PFormatCodec.encode(SACCommand.OP_S2A_AUTH, SACCodec.encode(SACCommand.AuthBegin))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: AuthBegin (Opcode 0, subtype 0)",
                rawHex = authBeginPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(400)

            // Stereo replies AuthResponse
            val authRespPayload = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x00, 0x01)
            val authRespFrame = PFormatCodec.encode(SACCommand.OP_A2S_AUTH, authRespPayload)
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: AuthResponse (result=OK, version=3.1)",
                rawHex = authRespFrame.joinToString(" ") { String.format("%02X", it) },
                details = "AuthResponse(result=0, majorVersion=3, minorVersion=1)"
            )
            delay(300)

            // Send AuthEnd
            _currentStep.value = HandshakeStep.STEP_0_AUTH_END
            val authEndPacket = PFormatCodec.encode(SACCommand.OP_S2A_AUTH, SACCodec.encode(SACCommand.AuthEnd(isSuccess = true, majorVersion = 3, minorVersion = 1)))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: AuthEnd (isSuccess=true, v3.1)",
                rawHex = authEndPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Step 1: Kind 4 StartAppAcc
            _currentStep.value = HandshakeStep.STEP_1_START_APP_ACC
            val startAppAccPacket = PFormatCodec.encode(SACCommand.OP_S2A_AUTH, SACCodec.encode(SACCommand.StartAppAcc))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: StartAppAcc (subtype 16)",
                rawHex = startAppAccPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies StartAppAccReply
            val startAppReply = PFormatCodec.encode(SACCommand.OP_A2S_AUTH, byteArrayOf(16, 1))
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: StartAppAccReply (status=1)",
                rawHex = startAppReply.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Step 2: Kind 5 StartAccessoryInfo
            _currentStep.value = HandshakeStep.STEP_2_START_ACC_INFO
            val startAccInfoPacket = PFormatCodec.encode(SACCommand.OP_S2A_AUTH, SACCodec.encode(SACCommand.StartAccessoryInfo))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: StartAccessoryInfo (subtype 20)",
                rawHex = startAccInfoPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies StartAccessoryInfoReply
            val startAccReply = PFormatCodec.encode(SACCommand.OP_A2S_AUTH, byteArrayOf(20, 1))
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: StartAccessoryInfoReply (status=1)",
                rawHex = startAccReply.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Step 3: Kind 2 RequestSpecInfo
            _currentStep.value = HandshakeStep.STEP_3_REQUEST_SPEC
            val reqSpecPacket = PFormatCodec.encode(SACCommand.OP_S2A_PROC_SPEC, SACCodec.encode(SACCommand.RequestSpecInfo))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: RequestSpecInfo (opcode 1, subtype 1)",
                rawHex = reqSpecPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies ProductSpecInfo
            _stereoSpecs.value = _stereoSpecs.value.copy(
                modelId = 0x0112,
                pointerCount = 2,
                hasGps = true,
                hasRemoteControl = true
            )
            val specBytes = byteArrayOf(1, 0x01, 0x12, 2, 1, 1, 0, 0x80.toByte())
            val specFrame = PFormatCodec.encode(SACCommand.OP_A2S_PROC_SPEC, specBytes)
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: ProductSpecInfo (Model: 0x0112, MultiTouch: 2 pointers, GPS: Yes, RemoteCtrl: Yes)",
                rawHex = specFrame.joinToString(" ") { String.format("%02X", it) },
                details = "ProductSpecInfo(modelId=0x0112, pointerCount=2, hasGps=true, hasRemoteControl=true, isCalibrationTouch=true)"
            )
            delay(300)

            // Step 4: Kind 1 RequestDisplayInfo
            _currentStep.value = HandshakeStep.STEP_4_REQUEST_DISPLAY
            val reqDispPacket = PFormatCodec.encode(SACCommand.OP_S2A_PROC_SPEC, SACCodec.encode(SACCommand.RequestDisplayInfo))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: RequestDisplayInfo (opcode 1, subtype 0)",
                rawHex = reqDispPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies DisplaySpecInfo (800x480)
            _stereoSpecs.value = _stereoSpecs.value.copy(
                width = 800,
                height = 480
            )
            val dispBytes = byteArrayOf(0, 0, 0, 0, 0, 0x03, 0x20, 0x01, 0xE0.toByte())
            val dispFrame = PFormatCodec.encode(SACCommand.OP_A2S_PROC_SPEC, dispBytes)
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: DisplaySpecInfo (800 x 480)",
                rawHex = dispFrame.joinToString(" ") { String.format("%02X", it) },
                details = "DisplaySpecInfo(width=800, height=480)"
            )
            delay(300)

            // Step 5: Kind 3 RequestAccessoryStatus
            _currentStep.value = HandshakeStep.STEP_5_REQUEST_STATUS
            val reqStatPacket = PFormatCodec.encode(SACCommand.OP_S2A_ACCESSORY_STATUS, SACCodec.encode(SACCommand.RequestAccessoryStatus))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: RequestAccessoryStatus (opcode 32, subtype 32)",
                rawHex = reqStatPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies AccessoryStatus (Parking brake engaged, HDMI connected)
            _stereoSpecs.value = _stereoSpecs.value.copy(
                isParkingBrakeOn = true,
                isHdmiConnected = true
            )
            val statBytes = byteArrayOf(32, 0x03)
            val statFrame = PFormatCodec.encode(SACCommand.OP_A2S_PACKAGEINFO, statBytes)
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: AccessoryStatus (Parking Brake: ON, HDMI: Connected)",
                rawHex = statFrame.joinToString(" ") { String.format("%02X", it) },
                details = "AccessoryStatus(isParkingBrakeOn=true, isHdmiConnected=true)"
            )
            delay(300)

            // Step 6: Kind 6 EndAccessoryInfo
            _currentStep.value = HandshakeStep.STEP_6_END_ACC_INFO
            val endAccInfoPacket = PFormatCodec.encode(SACCommand.OP_S2A_AUTH, SACCodec.encode(SACCommand.EndAccessoryInfo))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: EndAccessoryInfo (subtype 21)",
                rawHex = endAccInfoPacket.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo replies EndAccessoryInfoReply
            val endAccReply = PFormatCodec.encode(SACCommand.OP_A2S_AUTH, byteArrayOf(21, 1))
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: EndAccessoryInfoReply (status=1)",
                rawHex = endAccReply.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Stereo requests VideoOutput
            val vidReqFrame = PFormatCodec.encode(SACCommand.OP_A2S_VEDIO_OUTPUT, byteArrayOf())
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.SAC,
                summary = "RX SAC: VideoOutputRequest (Opcode 6)",
                rawHex = vidReqFrame.joinToString(" ") { String.format("%02X", it) }
            )
            delay(200)

            // Phone replies VideoOutputReply [0x06, 0x01]
            val vidReplyFrame = PFormatCodec.encode(SACCommand.OP_S2A_VEDIO_OUTPUT_REPLY, byteArrayOf(6, 1))
            logRepository.log(
                direction = LogDirection.OUTGOING,
                protocol = ProtocolType.SAC,
                summary = "TX SAC: VideoOutputReply [0x06, 0x01]",
                rawHex = vidReplyFrame.joinToString(" ") { String.format("%02X", it) }
            )
            delay(300)

            // Ready!
            _currentStep.value = HandshakeStep.CONNECTED_READY
            _stereoSpecs.value = _stereoSpecs.value.copy(isReadyForVideo = true)
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "=== HANDSHAKE COMPLETE: Stereo Display Unlocked (800x480) ==="
            )

            // Simulate incoming WebLink touch event
            delay(1000)
            val touchCmd = WebLinkCommand.Touch(
                eventType = 1,
                points = listOf(WebLinkCommand.TouchPoint(pointerId = 0, x = 400, y = 240, state = 1, pressure = 1.0f))
            )
            val touchBytes = WebLinkCodec.encode(touchCmd)
            logRepository.log(
                direction = LogDirection.INCOMING,
                protocol = ProtocolType.WEBLINK,
                summary = "RX WebLink: TouchEvent (Down at X=400, Y=240)",
                rawHex = touchBytes.take(32).joinToString(" ") { String.format("%02X", it) },
                details = touchCmd.toString()
            )
        }
    }
}
