package com.ameer.appradiore.video

import android.hardware.usb.UsbAccessory
import android.view.Surface
import com.ameer.appradiore.core.logging.LogRepositoryImpl
import com.ameer.appradiore.core.protocol.mtp.MTPCodec
import com.ameer.appradiore.core.protocol.mtp.MTPPacket
import com.ameer.appradiore.core.protocol.weblink.WebLinkCodec
import com.ameer.appradiore.core.protocol.weblink.WebLinkCommand
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import com.ameer.appradiore.core.usb.UsbConnectionState
import com.ameer.appradiore.core.video.VideoEncoder
import com.ameer.appradiore.core.video.VideoStreamingManagerImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoStreamingManagerTest {

    private class FakeUsbAccessoryManager : UsbAccessoryManager {
        override val connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
        override val incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
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

    private class FakeVideoEncoder : VideoEncoder {
        override val inputSurface: Surface? = null
        var frameCallback: ((ByteArray) -> Unit)? = null
        var isStarted = false
        var isStopped = false

        override fun start(onFrameEncoded: (ByteArray) -> Unit) {
            isStarted = true
            frameCallback = onFrameEncoded
        }

        override fun stop() {
            isStopped = true
            isStarted = false
            frameCallback = null
        }

        fun emitFrame(frameData: ByteArray) {
            frameCallback?.invoke(frameData)
        }
    }

    @Test
    fun testStartAndStopStreaming() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val fakeEncoder = FakeVideoEncoder()

        val manager = VideoStreamingManagerImpl(
            usbAccessoryManager = fakeUsb,
            logRepository = logRepo,
            scope = testScope,
            encoderFactory = { _, _, _ -> fakeEncoder }
        )

        assertFalse(manager.isStreaming.value)
        assertEquals(0L, manager.framesSent.value)
        assertEquals(0L, manager.bytesSent.value)

        manager.startStreaming(800, 480, 30)
        testScheduler.runCurrent()

        assertTrue(manager.isStreaming.value)
        assertTrue(fakeEncoder.isStarted)

        manager.stopStreaming()
        testScheduler.runCurrent()

        assertFalse(manager.isStreaming.value)
        assertTrue(fakeEncoder.isStopped)
    }

    @Test
    fun testFrameEncodingAndPackaging() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val fakeUsb = FakeUsbAccessoryManager()
        val logRepo = LogRepositoryImpl()
        val fakeEncoder = FakeVideoEncoder()

        val manager = VideoStreamingManagerImpl(
            usbAccessoryManager = fakeUsb,
            logRepository = logRepo,
            scope = testScope,
            encoderFactory = { _, _, _ -> fakeEncoder }
        )

        manager.startStreaming(800, 480, 30)
        testScheduler.runCurrent()

        // Emit an H.264 frame
        val sampleH264 = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02, 0x03)
        fakeEncoder.emitFrame(sampleH264)
        testScheduler.runCurrent()

        assertEquals(1L, manager.framesSent.value)
        assertTrue(manager.bytesSent.value > 0)
        assertEquals(1, fakeUsb.sentBytes.size)

        // Verify sent wire bytes are valid MTP packet targeting Video Channel (Port 12346)
        val sentWireBytes = fakeUsb.sentBytes[0]
        val mtpResult = MTPCodec.decode(sentWireBytes)
        assertEquals(1, mtpResult.packets.size)
        val mtpPacket = mtpResult.packets[0]
        assertEquals(MTPPacket.PORT_VIDEO_CHANNEL, mtpPacket.dstAddress.port)

        // Verify payload inside MTP is WebLink FillRectangleCommand
        val wlCommands = WebLinkCodec.decode(mtpPacket.payload)
        assertEquals(1, wlCommands.size)
        assertTrue(wlCommands[0] is WebLinkCommand.FillRectangle)
        val fillRect = wlCommands[0] as WebLinkCommand.FillRectangle
        assertEquals(800, fillRect.width)
        assertEquals(480, fillRect.height)
        assertEquals(2, fillRect.encodingType)
        assertEquals(0, fillRect.appId)
        assertTrue(sampleH264.contentEquals(fillRect.frameData))

        manager.stopStreaming()
        testScheduler.runCurrent()
    }
}
