package com.ameer.appradiore.usb

import android.hardware.usb.UsbAccessory
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.usb.HandshakeStateMachineImpl
import com.ameer.appradiore.core.usb.HandshakeStep
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeStateMachineTest {

    private class FakeUsbAccessoryManager : UsbAccessoryManager {
        override val connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        override val incomingBytes = MutableSharedFlow<ByteArray>(replay = 10, extraBufferCapacity = 64)

        val sentBytes = mutableListOf<ByteArray>()

        override fun startListening() {}
        override fun stopListening() {}
        override fun scanForAccessory() {}
        override fun requestPermission(accessory: UsbAccessory) {}
        override fun connect(accessory: UsbAccessory) {}
        override fun disconnect() {}
        override suspend fun send(data: ByteArray): Boolean {
            sentBytes.add(data)
            return true
        }
        override fun simulateConnect() {}
    }

    @Test
    fun testInitialStateDisconnected() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)

        assertEquals(HandshakeStep.DISCONNECTED, stateMachine.currentStep.value)
        assertEquals(0, stateMachine.stereoSpecs.value.width)
    }

    @Test
    fun testSimulateHandshakeCompletes() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)

        stateMachine.simulateHandshake()
        testScope.advanceUntilIdle()

        assertEquals(HandshakeStep.CONNECTED_READY, stateMachine.currentStep.value)
        assertTrue(stateMachine.stereoSpecs.value.isReadyForVideo)
        assertEquals(800, stateMachine.stereoSpecs.value.width)
        assertEquals(480, stateMachine.stereoSpecs.value.height)
        assertEquals(0x0112.toShort(), stateMachine.stereoSpecs.value.modelId)
        assertTrue(stateMachine.stereoSpecs.value.isParkingBrakeOn)

        val logs = logRepo.logs.first()
        assertTrue(logs.size > 10)
    }

    @Test
    fun testMtpStreamNegotiationAndAuthResponse() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)
        testScope.advanceUntilIdle()

        stateMachine.startHandshake()
        testScope.testScheduler.runCurrent()

        // Simulate stereo requesting Port 12347 (Control Channel) connection
        val synControl = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = ByteArray(0),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_CONTROL_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_CONTROL_CHANNEL
        )
        fakeUsb.incomingBytes.emit(synControl)
        testScope.testScheduler.runCurrent()
        testScope.testScheduler.advanceTimeBy(1100)

        // Verify that AuthBegin was wrapped in MTP framing (starts with 0x1E, ends with 0x03)
        assertTrue(fakeUsb.sentBytes.isNotEmpty())
        val sentMtp = fakeUsb.sentBytes.last()
        assertEquals(0x1E.toByte(), sentMtp[0])
        assertEquals(0x03.toByte(), sentMtp[sentMtp.size - 1])

        // Simulate stereo replying with AuthResponse inside an MTP packet
        val authRespPFormat = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x00, 0x01)
        )
        val authRespMtp = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(authRespPFormat)

        fakeUsb.incomingBytes.emit(authRespMtp)
        testScope.advanceUntilIdle()

        // Verify state advanced to STEP_1_START_APP_ACC (as AuthResponse triggers AuthEnd and StartAppAcc)
        assertEquals(HandshakeStep.STEP_1_START_APP_ACC, stateMachine.currentStep.value)
    }

    @Test
    fun testWebLinkOverMtpHandshakeUnlocksScreen() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)
        testScope.advanceUntilIdle()

        stateMachine.startHandshake()

        // 1. Stereo sends MTP SYN probe on Video port (12346)
        val synPacket = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = ByteArray(0),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL
        )
        fakeUsb.incomingBytes.emit(synPacket)
        testScope.testScheduler.runCurrent()

        // 2. Stereo sends DisplayMetrics (xdpi=240|ydpi=240)
        val dpiCmd = com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.DisplayMetrics(240, 240)
        val dpiMtp = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.encode(dpiCmd),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL
        )
        fakeUsb.incomingBytes.emit(dpiMtp)
        testScope.testScheduler.runCurrent()
        assertEquals(240, stateMachine.stereoSpecs.value.dpi)

        // 3. Stereo sends SyncSessionTime
        val syncCmd = com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.SyncSessionTime(
            clientTime = 123456789L,
            serverTime = 0L
        )
        val syncMtp = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.encode(syncCmd),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL
        )
        fakeUsb.incomingBytes.emit(syncMtp)
        testScope.testScheduler.runCurrent()

        // 4. Stereo sends VideoConfig (800x480 H.264)
        val videoCmd = com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.VideoConfig(
            sourceWidth = 800,
            sourceHeight = 480,
            clientWidth = 800,
            clientHeight = 480,
            frameEncoding = 2,
            encoderParams = "2:maxKeyFrameInterval=60,bitrate=8388608"
        )
        val videoMtp = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.encode(videoCmd),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL
        )
        fakeUsb.incomingBytes.emit(videoMtp)
        testScope.testScheduler.runCurrent()

        // Video metrics negotiated
        assertEquals(800, stateMachine.stereoSpecs.value.width)
        assertEquals(480, stateMachine.stereoSpecs.value.height)
        assertEquals(240, stateMachine.stereoSpecs.value.dpi)
        // Video is NOT ready yet - requires SAC Auth to complete first
        org.junit.Assert.assertFalse(stateMachine.stereoSpecs.value.isReadyForVideo)

        stateMachine.reset()
    }

    @Test
    fun testFullSacHandshakeWithAppInfoAndVideoOutput() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)
        testScope.advanceUntilIdle()

        stateMachine.startHandshake()
        testScope.testScheduler.runCurrent()

        // 0. Stereo requests Port 12347 (Control Channel) connection
        val synControl = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = ByteArray(0),
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_CONTROL_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_CONTROL_CHANNEL
        )
        fakeUsb.incomingBytes.emit(synControl)
        testScope.testScheduler.runCurrent()
        testScope.testScheduler.advanceTimeBy(1100)

        // 1. Auth Response with AAM2 code 8
        val authRespPFormat = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(0x00, 0x08, 0x00, 0x03, 0x00, 0x01) // result=8, major=3, minor=1
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(authRespPFormat))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_1_START_APP_ACC, stateMachine.currentStep.value)

        // 2. StartAppAccReply (subtype 16, status 1)
        val startAppAccReply = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(16, 1)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(startAppAccReply))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_2_START_ACC_INFO, stateMachine.currentStep.value)

        // 3. StartAccessoryInfoReply (subtype 20, status 1)
        val startAccReply = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(20, 1)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(startAccReply))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_3_REQUEST_SPEC, stateMachine.currentStep.value)

        // 4. ProductSpecInfo (Model: 0x0112, 2 pointers, GPS, RemoteCtrl)
        val specBytes = byteArrayOf(1, 0x01, 0x12, 2, 1, 1, 0, 0x80.toByte())
        val specFrame = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_PROC_SPEC,
            specBytes
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(specFrame))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_4_REQUEST_DISPLAY, stateMachine.currentStep.value)
        assertEquals(0x0112.toShort(), stateMachine.stereoSpecs.value.modelId)

        // 5. DisplaySpecInfo (800x480)
        val dispBytes = byteArrayOf(0, 0x03, 0x20, 0x01, 0xE0.toByte(), 0x06, 0x0E, 0x03, 0x66)
        val dispFrame = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_PROC_SPEC,
            dispBytes
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(dispFrame))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_5_REQUEST_STATUS, stateMachine.currentStep.value)
        assertEquals(800, stateMachine.stereoSpecs.value.width)
        assertEquals(480, stateMachine.stereoSpecs.value.height)

        // 6. AccessoryStatus (Parking brake ON, HDMI connected)
        val statBytes = byteArrayOf(32, 0x03)
        val statFrame = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_PACKAGEINFO,
            statBytes
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(statFrame))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_6_END_ACC_INFO, stateMachine.currentStep.value)
        assertTrue(stateMachine.stereoSpecs.value.isParkingBrakeOn)

        // 7. EndAccessoryInfoReply
        val endAccReply = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(21, 1)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(endAccReply))
        testScope.testScheduler.runCurrent()

        // 8. Phase 6: StartAppInfo
        val startAppInfo = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(18)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(startAppInfo))
        testScope.testScheduler.runCurrent()
        assertEquals(HandshakeStep.STEP_7_APP_INFO, stateMachine.currentStep.value)

        // 9. AppInfoRequest (Type 1: App Name)
        val reqAppName = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_APPINFO_REQUEST,
            byteArrayOf(1, 0x00, 0x01)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(reqAppName))
        testScope.testScheduler.runCurrent()

        // 10. AppInfoRequest (Type 2: Package Name)
        val reqPkgName = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_APPINFO_REQUEST,
            byteArrayOf(2, 0x00, 0x01)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(reqPkgName))
        testScope.testScheduler.runCurrent()

        // 11. AppImageRequest (Type 0: Query acquisition)
        val reqIconAcq = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_APPIMAGE_TRANSFER_REQUEST,
            byteArrayOf(0, 0x00, 0x01, 16, 18, 0x00, 0x40, 0x00, 0x40)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(reqIconAcq))
        testScope.testScheduler.runCurrent()

        // 12. AppImageRequest (Type 1: Start transfer)
        val reqIconStart = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_APPIMAGE_TRANSFER_REQUEST,
            byteArrayOf(1, 0x00, 0x01)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(reqIconStart))
        testScope.testScheduler.runCurrent()

        // 13. AppImageRequest (Type 2: ACK chunk 0)
        val reqIconAck = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_APPIMAGE_TRANSFER_REQUEST,
            byteArrayOf(2, 0x00, 0x01, 0x00, 0x00) // ackChunkIndex = 0
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(reqIconAck))
        testScope.testScheduler.runCurrent()

        // 14. EndAppInfo
        val endAppInfo = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(19)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(endAppInfo))
        testScope.testScheduler.runCurrent()

        // 15. EndAppAccReply
        val endAppAccReply = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(17, 1)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(endAppAccReply))
        testScope.testScheduler.runCurrent()

        // 16. Phase 7: VideoOutputRequest
        val vidReqFrame = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_VEDIO_OUTPUT,
            byteArrayOf()
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(vidReqFrame))
        testScope.testScheduler.runCurrent()

        // Handshake MUST reach CONNECTED_READY with isReadyForVideo = true!
        assertEquals(HandshakeStep.CONNECTED_READY, stateMachine.currentStep.value)
        assertTrue(stateMachine.stereoSpecs.value.isReadyForVideo)

        stateMachine.reset()
    }

    @Test
    fun testWebLinkVideoConfigEncoderParamsResolution() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val stateMachine = HandshakeStateMachineImpl(fakeUsb, logRepo, testScope)
        testScope.advanceUntilIdle()

        stateMachine.startHandshake()
        testScope.testScheduler.runCurrent()

        // Stereo requests VideoConfig on Port 12346 with Pioneer parameters
        val videoConfigCmd = com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.VideoConfig(
            sourceWidth = 800,
            sourceHeight = 480,
            clientWidth = 800,
            clientHeight = 480,
            frameEncoding = 2,
            encoderParams = "2:maxKeyFrameInterval=60,bitrate=8388608,fps=30"
        )
        val videoConfigPayload = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.encode(videoConfigCmd)
        val videoConfigPacket = com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapPayload(
            payload = videoConfigPayload,
            srcPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = com.ameer.appradiore.core.protocol.mtp.MTPPacket.PORT_VIDEO_CHANNEL
        )

        fakeUsb.incomingBytes.emit(videoConfigPacket)
        testScope.testScheduler.runCurrent()

        // 1. Verify that VideoConfig reply is deferred prior to SAC authentication
        val replyBeforeAuth = fakeUsb.sentBytes.firstOrNull { bytes ->
            val decoded = com.ameer.appradiore.core.protocol.mtp.MTPCodec.decode(bytes)
            decoded.packets.any { packet ->
                val wlCmds = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.decode(packet.payload)
                wlCmds.any { it is com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.VideoConfig }
            }
        }
        org.junit.Assert.assertNull("VideoConfig reply should be deferred before SAC auth", replyBeforeAuth)

        // 2. Complete SAC Auth to trigger deferred video setup
        val authRespPFormat = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(0x00, 0x08, 0x00, 0x03, 0x00, 0x01)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(authRespPFormat))
        testScope.testScheduler.runCurrent()

        val endAccReply = com.ameer.appradiore.core.protocol.pformat.PFormatCodec.encode(
            com.ameer.appradiore.core.protocol.sac.SACCommand.OP_A2S_AUTH,
            byteArrayOf(17, 1)
        )
        fakeUsb.incomingBytes.emit(com.ameer.appradiore.core.protocol.mtp.MTPCodec.wrapControlChannelPayload(endAccReply))
        testScope.testScheduler.runCurrent()

        // 3. Verify that the deferred reply confirms VideoConfig and extracted parameters correctly
        val replyMtp = fakeUsb.sentBytes.firstOrNull { bytes ->
            val decoded = com.ameer.appradiore.core.protocol.mtp.MTPCodec.decode(bytes)
            decoded.packets.any { packet ->
                val wlCmds = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.decode(packet.payload)
                wlCmds.any { it is com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.VideoConfig }
            }
        }
        org.junit.Assert.assertNotNull("VideoConfig reply should be sent after SAC auth", replyMtp)

        val mtpPackets = com.ameer.appradiore.core.protocol.mtp.MTPCodec.decode(replyMtp!!).packets
        val confirmedCmd = com.ameer.appradiore.core.protocol.weblink.WebLinkCodec.decode(mtpPackets[0].payload)[0]
                as com.ameer.appradiore.core.protocol.weblink.WebLinkCommand.VideoConfig

        assertEquals(800, confirmedCmd.clientWidth)
        assertEquals(480, confirmedCmd.clientHeight)
        assertEquals(2, confirmedCmd.frameEncoding)
        assertEquals("maxKeyFrameInterval=60,bitrate=8388608,fps=30", confirmedCmd.encoderParams)

        stateMachine.reset()
    }
}
