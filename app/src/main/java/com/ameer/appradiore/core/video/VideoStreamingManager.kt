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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val encoderFactory: (width: Int, height: Int, fps: Int) -> VideoEncoder = { w, h, f ->
        H264VideoEncoder(
            width = w,
            height = h,
            fps = f,
            scope = scope,
            onError = { errMsg ->
                logRepository.log(
                    direction = LogDirection.INTERNAL,
                    protocol = ProtocolType.SYSTEM,
                    summary = "H264 Encoder Error: $errMsg",
                    isError = true
                )
            }
        )
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

    private class EncodedFrame(val frameData: ByteArray, val width: Int, val height: Int)
    private var frameChannel: Channel<EncodedFrame>? = null
    private var frameSenderJob: Job? = null

    override fun startStreaming(width: Int, height: Int, fps: Int) {
        if (_isStreaming.value) return

        try {
            logRepository.log(
                direction = LogDirection.INTERNAL,
                protocol = ProtocolType.SYSTEM,
                summary = "Initializing Video Pipeline: ${width}x${height} @ ${fps}fps (H.264)"
            )

            val channel = Channel<EncodedFrame>(capacity = Channel.CONFLATED)
            frameChannel = channel

            frameSenderJob = scope.launch(ioDispatcher) {
                for (item in channel) {
                    if (!_isStreaming.value) break
                    try {
                        // 1. Wrap in WebLink FillRectangleCommand (ID 1)
                        val fillRectangle = WebLinkCommand.FillRectangle(
                            width = item.width,
                            height = item.height,
                            encodingType = 2, // H.264
                            appId = 0,
                            frameData = item.frameData
                        )
                        val webLinkBytes = WebLinkCodec.encode(fillRectangle)

                        // 2. Wrap in MTP Packet(s) targeting Video Channel (Port 12346), fragmented if > 16,284B
                        val mtpPackets = MTPCodec.wrapPayloadFragmented(
                            payload = webLinkBytes,
                            srcPort = MTPPacket.PORT_VIDEO_CHANNEL,
                            dstPort = MTPPacket.PORT_VIDEO_CHANNEL
                        )

                        // 3. Transmit via UsbAccessoryManager
                        var allSuccess = true
                        for (packet in mtpPackets) {
                            val success = usbAccessoryManager.send(packet)
                            if (!success) {
                                allSuccess = false
                                break
                            }
                        }
                        if (allSuccess) {
                            _framesSent.value++
                            _bytesSent.value += webLinkBytes.size
                            framesInCurrentSecond.incrementAndGet()
                        } else {
                            logRepository.log(
                                direction = LogDirection.INTERNAL,
                                protocol = ProtocolType.SYSTEM,
                                summary = "USB write failed during video streaming; stopping stream",
                                isError = true
                            )
                            stopStreaming()
                            break
                        }
                    } catch (e: Exception) {
                        if (_isStreaming.value) {
                            logRepository.log(
                                direction = LogDirection.INTERNAL,
                                protocol = ProtocolType.SYSTEM,
                                summary = "Video frame send error: ${e.message}",
                                isError = true
                            )
                        }
                    }
                }
            }

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
                        scope = scope,
                        onError = { errMsg ->
                            logRepository.log(
                                direction = LogDirection.INTERNAL,
                                protocol = ProtocolType.SYSTEM,
                                summary = "TestPatternRenderer Error: $errMsg",
                                isError = true
                            )
                        }
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
        frameChannel?.trySend(EncodedFrame(frameData, width, height))
    }

    private fun startFpsCounter() {
        fpsCounterJob?.cancel()
        fpsCounterJob = scope.launch {
            var seconds = 0
            while (isActive) {
                delay(1000)
                val currentCount = framesInCurrentSecond.getAndSet(0)
                _fps.value = currentCount.toInt()
                seconds++
                if (seconds % 5 == 0 && _isStreaming.value) {
                    val kbSent = _bytesSent.value / 1024
                    logRepository.log(
                        direction = LogDirection.INTERNAL,
                        protocol = ProtocolType.SYSTEM,
                        summary = "Video Stream Heartbeat: ${_fps.value} fps | ${_framesSent.value} total frames | ${kbSent} KB"
                    )
                }
            }
        }
    }

    override fun stopStreaming() {
        if (!_isStreaming.value && activeEncoder == null) return

        _isStreaming.value = false
        frameSenderJob?.cancel()
        frameSenderJob = null
        frameChannel?.close()
        frameChannel = null
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
