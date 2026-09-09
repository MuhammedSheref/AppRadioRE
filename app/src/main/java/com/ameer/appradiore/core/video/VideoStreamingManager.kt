package com.ameer.appradiore.core.video

import android.view.Surface
import com.ameer.appradiore.core.logging.LogDirection
import com.ameer.appradiore.core.logging.LogRepository
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.core.protocol.mtp.MTPCodec
import com.ameer.appradiore.core.protocol.mtp.MTPPacket
import com.ameer.appradiore.core.protocol.weblink.WebLinkCodec
import com.ameer.appradiore.core.protocol.weblink.WebLinkCommand
import com.ameer.appradiore.core.usb.UsbAccessoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the video streaming pipeline:
 * - H.264 MediaCodec surface encoding
 * - 30 FPS animated test pattern rendering
 * - Packaging H.264 NAL units into WebLink FillRectangle commands
 * - Framing in MTP Video Channel (Port 12346) packets over USB
 * - Live performance metrics (FPS, frames, bytes)
 */
interface VideoStreamingManager {
    val isStreaming: StateFlow<Boolean>
    val fps: StateFlow<Int>
    val framesSent: StateFlow<Long>
    val bytesSent: StateFlow<Long>

    fun startStreaming(width: Int = 800, height: Int = 480, fps: Int = 30)
    fun stopStreaming()
}

class VideoStreamingManagerImpl(
    private val usbAccessoryManager: UsbAccessoryManager,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val encoderFactory: (width: Int, height: Int, fps: Int) -> VideoEncoder = { w, h, f ->
        H264VideoEncoder(width = w, height = h, fps = f, scope = scope)
    },
    private val rendererFactory: ((surface: Surface, width: Int, height: Int, fps: Int) -> TestPatternRenderer)? = null
) : VideoStreamingManager {

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _fps = MutableStateFlow(0)
    override val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    override val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    private val _bytesSent = MutableStateFlow(0L)
    override val bytesSent: StateFlow<Long> = _bytesSent.asStateFlow()

    private var activeEncoder: VideoEncoder? = null
    private var activeRenderer: TestPatternRenderer? = null
    private var fpsCounterJob: Job? = null
    private val framesInCurrentSecond = AtomicLong(0)
    private val isTransmittingFrame = AtomicBoolean(false)

    override fun startStreaming(width: Int, height: Int, fps: Int) {
        if (_isStreaming.value) return

        try {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "Initializing Video Pipeline: ${width}x${height} @ ${fps}fps (H.264)"
            )

            val encoder = encoderFactory(width, height, fps)
            activeEncoder = encoder

            encoder.start { frameData ->
                onH264FrameEncoded(frameData, width, height)
            }

            val surface = encoder.inputSurface
            if (surface != null) {
                val renderer = rendererFactory?.invoke(surface, width, height, fps)
                    ?: TestPatternRenderer(
                        surface = surface,
                        width = width,
                        height = height,
                        fps = fps,
                        scope = scope
                    )
                activeRenderer = renderer
                renderer.start()
            }

            _isStreaming.value = true
            startFpsCounter()

            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "Video Streaming ACTIVE: transmitting FillRectangle frames to MTP Port 12346"
            )
        } catch (e: Exception) {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "Failed to start video streaming: ${e.message}",
                isError = true
            )
            stopStreaming()
        }
    }

    private fun onH264FrameEncoded(frameData: ByteArray, width: Int, height: Int) {
        if (!_isStreaming.value) return

        // Drop incoming frame if previous frame write is still in flight (single-flight backpressure)
        if (!isTransmittingFrame.compareAndSet(false, true)) {
            return
        }

        // 1. Wrap in WebLink FillRectangleCommand (ID 1)
        val fillRectangle = WebLinkCommand.FillRectangle(
            width = width,
            height = height,
            encodingType = 2, // H.264
            appId = 0,
            frameData = frameData
        )
        val webLinkBytes = WebLinkCodec.encode(fillRectangle)

        // 2. Wrap in MTP Packet targeting Video Channel (Port 12346)
        val mtpBytes = MTPCodec.wrapPayload(
            payload = webLinkBytes,
            srcPort = MTPPacket.PORT_VIDEO_CHANNEL,
            dstPort = MTPPacket.PORT_VIDEO_CHANNEL
        )

        // 3. Transmit via UsbAccessoryManager
        scope.launch {
            try {
                if (_isStreaming.value) {
                    val success = usbAccessoryManager.send(mtpBytes)
                    if (success) {
                        _framesSent.value++
                        _bytesSent.value += mtpBytes.size
                        framesInCurrentSecond.incrementAndGet()
                    } else {
                        logRepository.log(
                            direction = LogDirection.INTERNAL,
                            protocol = ProtocolType.SYSTEM,
                            summary = "USB write failed during video streaming; stopping stream",
                            isError = true
                        )
                        stopStreaming()
                    }
                }
            } finally {
                isTransmittingFrame.set(false)
            }
        }
    }

    private fun startFpsCounter() {
        fpsCounterJob?.cancel()
        fpsCounterJob = scope.launch {
            while (isActive) {
                delay(1000)
                val currentCount = framesInCurrentSecond.getAndSet(0)
                _fps.value = currentCount.toInt()
            }
        }
    }

    override fun stopStreaming() {
        if (!_isStreaming.value && activeEncoder == null) return

        _isStreaming.value = false
        isTransmittingFrame.set(false)
        fpsCounterJob?.cancel()
        fpsCounterJob = null
        _fps.value = 0

        try {
            activeRenderer?.stop()
        } catch (e: Exception) {
            // Ignored
        }
        activeRenderer = null

        try {
            activeEncoder?.stop()
        } catch (e: Exception) {
            // Ignored
        }
        activeEncoder = null

        logRepository.log(
            direction = LogDirection.INTERNAL,
            protocol = ProtocolType.SYSTEM,
            summary = "Video Streaming Stopped. Total frames sent: ${_framesSent.value}, bytes: ${_bytesSent.value}"
        )
    }
}
