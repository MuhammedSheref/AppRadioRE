package com.ameer.appradiore.core.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a high-contrast automotive test pattern at 30 FPS directly onto the
 * MediaCodec input Surface for visual validation on the car stereo display.
 */
class TestPatternRenderer(
    private val surface: Surface,
    private val width: Int = 800,
    private val height: Int = 480,
    private val fps: Int = 30,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    @Volatile
    private var isRunning = false
    private var renderJob: Job? = null
    private var frameCount: Long = 0

    // Reusable paints to avoid GC in 30 FPS loop
    private val bgPaint = Paint().apply { color = Color.parseColor("#0B132B") }
    private val headerBgPaint = Paint().apply { color = Color.parseColor("#1C2541") }
    private val footerBgPaint = Paint().apply { color = Color.parseColor("#1C2541") }
    private val cardBgPaint = Paint().apply { color = Color.parseColor("#1E2A4A") }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#48CAE4")
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0AEC0")
        textSize = 18f
        typeface = Typeface.DEFAULT
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 26f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53E3E")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#48BB78")
        style = Paint.Style.FILL
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#48BB78")
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val footerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#718096")
        textSize = 14f
        typeface = Typeface.DEFAULT
    }

    private val scanlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5000E5FF")
        strokeWidth = 2f
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val centerCardRect = RectF(160f, 130f, 640f, 350f)

    fun start() {
        if (isRunning) return
        isRunning = true
        frameCount = 0

        renderJob = scope.launch(Dispatchers.Default) {
            val framePeriodNs = 1_000_000_000L / fps
            var nextFrameNs = System.nanoTime()

            while (isRunning && isActive && surface.isValid) {
                try {
                    val canvas: Canvas? = surface.lockCanvas(null)
                    if (canvas != null) {
                        try {
                            drawFrame(canvas, frameCount)
                            frameCount++
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        // Surface might have been destroyed or busy
                        delay(10)
                    }
                    continue
                }

                nextFrameNs += framePeriodNs
                val sleepMs = (nextFrameNs - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0) {
                    delay(sleepMs)
                } else {
                    nextFrameNs = System.nanoTime()
                }
            }
        }
    }

    private fun drawFrame(canvas: Canvas, frame: Long) {
        // 1. Clear background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Top Header Bar
        canvas.drawRect(0f, 0f, width.toFloat(), 56f, headerBgPaint)
        canvas.drawText("AppRadioRE - AAM2 Video Mirroring Test", 24f, 36f, titlePaint)
        canvas.drawText("${width}x${height} @ ${fps}fps (8Mbps)", width - 240f, 36f, specPaint)

        // 3. Center Dashboard Card
        canvas.drawRoundRect(centerCardRect, 16f, 16f, cardBgPaint)

        val timeString = timeFormat.format(Date())
        val frameString = "FRAME: #${String.format(Locale.US, "%06d", frame)}"

        val timeWidth = timePaint.measureText(timeString)
        canvas.drawText(timeString, (width - timeWidth) / 2f, 205f, timePaint)

        val frameWidth = framePaint.measureText(frameString)
        canvas.drawText(frameString, (width - frameWidth) / 2f, 260f, framePaint)

        // 4. Moving animated element: Bouncing Neon Orb
        val t = frame * 0.06
        val orbX = (sin(t) * 310f + width / 2f).toFloat()
        val orbY = (cos(t * 1.5) * 45f + 305f).toFloat()

        canvas.drawCircle(orbX, orbY, 26f, orbGlowPaint)
        canvas.drawCircle(orbX, orbY, 14f, orbPaint)

        // 5. Horizontal scanning indicator bar
        val scanX = (frame * 6 % width).toFloat()
        canvas.drawLine(scanX, 58f, scanX, height - 42f, scanlinePaint)

        // 6. Corner Calibration Crosshairs (for screen overscan validation)
        drawCrosshair(canvas, 20f, 76f)
        drawCrosshair(canvas, width - 20f, 76f)
        drawCrosshair(canvas, 20f, height - 60f)
        drawCrosshair(canvas, width - 20f, height - 60f)

        // 7. Bottom Footer Bar
        canvas.drawRect(0f, height - 40f, width.toFloat(), height.toFloat(), footerBgPaint)
        canvas.drawCircle(30f, height - 20f, 6f, statusDotPaint)
        canvas.drawText("LIVE H.264 USB STREAM", 46f, height - 15f, statusTextPaint)
        canvas.drawText("Pioneer SPH-DA120 / AVH Mode 2", width - 260f, height - 15f, footerTextPaint)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawLine(cx - 12f, cy, cx + 12f, cy, cornerPaint)
        canvas.drawLine(cx, cy - 12f, cx, cy + 12f, cornerPaint)
    }

    fun stop() {
        isRunning = false
        renderJob?.cancel()
        renderJob = null
    }
}
