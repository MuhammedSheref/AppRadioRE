package com.ameer.appradiore.core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android MediaCodec-based hardware H.264 video encoder.
 * Configured for 800x480 @ 30 FPS and 8 Mbps matching Pioneer AppRadio / WebLink specifications.
 */
class H264VideoEncoder(
    private val width: Int = 800,
    private val height: Int = 480,
    private val bitrate: Int = 8_388_608, // 8 Mbps (matching Pioneer AppRadio / WebLink VideoConfig request)
    private val fps: Int = 30,
    private val iFrameInterval: Int = 1,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onError: ((String) -> Unit)? = null
) : VideoEncoder {

    companion object {
        private const val TAG = "H264VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 10_000L // 10ms
    }

    private var codec: MediaCodec? = null
    override var inputSurface: Surface? = null
        private set

    private var drainJob: Job? = null
    private var spsPpsBytes: ByteArray? = null

    @Volatile
    private var isRunning = false

    override fun start(onFrameEncoded: (ByteArray) -> Unit) {
        if (isRunning) return

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                } catch (e: Exception) {
                    Log.w(TAG, "Baseline profile configuration omitted: ${e.message}")
                }
                // Repeat previous frame after 33ms if surface is idle
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
            }

            val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec.createInputSurface()
            mediaCodec.start()
            codec = mediaCodec
            isRunning = true

            drainJob = scope.launch(Dispatchers.IO) {
                drainEncoder(mediaCodec, onFrameEncoded)
            }
            Log.i(TAG, "H264 Video Encoder started ($width x $height @ ${fps}fps, ${bitrate}bps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264 video encoder", e)
            stop()
            throw e
        }
    }

    private fun drainEncoder(mediaCodec: MediaCodec, onFrameEncoded: (ByteArray) -> Unit) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // SPS / PPS parameter sets
                            spsPpsBytes = chunk
                            Log.d(TAG, "Captured H264 SPS/PPS header (${chunk.size} bytes)")
                        } else {
                            val payload = if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                val spsPps = spsPpsBytes
                                if (spsPps != null && !containsSpsPps(chunk)) {
                                    spsPps + chunk
                                } else {
                                    chunk
                                }
                            } else {
                                chunk
                            }
                            try {
                                onFrameEncoded(payload)
                            } catch (dispatchEx: Exception) {
                                Log.w(TAG, "Frame dispatch callback threw exception", dispatchEx)
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = mediaCodec.outputFormat
                    Log.i(TAG, "MediaCodec format changed: $newFormat")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Exception during MediaCodec drain", e)
                    onError?.invoke("MediaCodec drain error: ${e.message}")
                }
                break
            }
        }
    }

    private fun containsSpsPps(data: ByteArray): Boolean {
        if (data.size < 5) return false
        // Search for NAL unit type 7 (SPS) in the first 64 bytes
        for (i in 0 until minOf(data.size - 4, 64)) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = if (data[i + 2] == 1.toByte()) {
                    i + 3
                } else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    i + 4
                } else {
                    -1
                }
                if (nalStart > 0 && nalStart < data.size) {
                    val nalType = (data[nalStart].toInt() and 0x1F)
                    if (nalType == 7) return true
                }
            }
        }
        return false
    }

    override fun stop() {
        isRunning = false
        drainJob?.cancel()
        drainJob = null

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaCodec", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaCodec", e)
        }
        codec = null

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing input surface", e)
        }
        inputSurface = null
        spsPpsBytes = null
        Log.i(TAG, "H264 Video Encoder stopped.")
    }
}
