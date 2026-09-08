package com.ameer.appradiore.core.video

import android.view.Surface

/**
 * Common interface for video encoders (e.g. Android MediaCodec H.264).
 */
interface VideoEncoder {
    val inputSurface: Surface?
    fun start(onFrameEncoded: (ByteArray) -> Unit)
    fun stop()
}
