package com.ameer.appradiore.core.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a high-contrast automotive test pattern at 30 FPS directly onto the
 * MediaCodec input Surface via OpenGL ES 2.0 / EGL 1.4 for visual validation
 * on the Pioneer car stereo display.
 */
class TestPatternRenderer(
    private val surface: Surface,
    private val width: Int = 800,
    private val height: Int = 480,
    private val fps: Int = 30,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        private const val TAG = "TestPatternRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // Full-screen quad covering NDC [-1, 1]
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f,  1.0f,  // Top-left
            -1.0f, -1.0f,  // Bottom-left
             1.0f,  1.0f,  // Top-right
             1.0f, -1.0f   // Bottom-right
        )

        // Texture coordinates mapping Canvas (0,0 at top-left) to OpenGL
        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,    // Top-left
            0.0f, 1.0f,    // Bottom-left
            1.0f, 0.0f,    // Top-right
            1.0f, 1.0f     // Bottom-right
        )
    }

    @Volatile
    private var isRunning = false
    private var renderThread: Thread? = null
    private var frameCount: Long = 0

    // EGL & OpenGL handles
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programId: Int = 0
    private var textureId: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var samplerHandle: Int = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var bitmap: Bitmap? = null
    private var offscreenCanvas: Canvas? = null

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

        renderThread = Thread({
            runRenderLoop()
        }, "TestPatternRendererThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun runRenderLoop() {
        try {
            if (!initEgl()) {
                Log.e(TAG, "Failed to initialize EGL for test pattern renderer")
                return
            }

            val framePeriodNs = 1_000_000_000L / fps
            var nextFrameNs = System.nanoTime()

            while (isRunning && !Thread.currentThread().isInterrupted && surface.isValid) {
                val canvas = offscreenCanvas ?: break
                val bmp = bitmap ?: break

                drawFrame(canvas, frameCount)
                renderToGl(bmp, isFirstFrame = (frameCount == 0L))
                frameCount++

                nextFrameNs += framePeriodNs
                val sleepNs = nextFrameNs - System.nanoTime()
                if (sleepNs > 0) {
                    val sleepMs = sleepNs / 1_000_000L
                    val sleepRemainderNs = (sleepNs % 1_000_000L).toInt()
                    Thread.sleep(sleepMs, sleepRemainderNs)
                } else {
                    nextFrameNs = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } catch (e: Exception) {
            Log.e(TAG, "Error in TestPatternRenderer loop", e)
        } finally {
            releaseEgl()
        }
    }

    private fun initEgl(): Boolean {
        if (!surface.isValid) {
            Log.e(TAG, "Cannot init EGL: Surface is not valid")
            return false
        }

        // 1. Get and initialize EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return false
        }

        // 2. Choose EGL config: try with EGL_RECORDABLE_ANDROID first, then fallback
        val attribListRecordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        var success = EGL14.eglChooseConfig(eglDisplay, attribListRecordable, 0, configs, 0, configs.size, numConfigs, 0)
        if (!success || numConfigs[0] <= 0) {
            val attribListBasic = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            success = EGL14.eglChooseConfig(eglDisplay, attribListBasic, 0, configs, 0, configs.size, numConfigs, 0)
            if (!success || numConfigs[0] <= 0) {
                Log.e(TAG, "eglChooseConfig failed to find a valid config")
                return false
            }
        }
        val eglConfig = configs[0] ?: return false

        // 3. Create EGL context (client version 2)
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            return false
        }

        // 4. Create window surface bound to MediaCodec input Surface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed")
            return false
        }

        // 5. Make context current on this render thread
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed")
            return false
        }

        // 6. Build GLES20 shader program
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "glLinkProgram failed: ${GLES20.glGetProgramInfoLog(programId)}")
            return false
        }

        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        samplerHandle = GLES20.glGetUniformLocation(programId, "uTexture")

        // 7. Setup vertex and texture coordinate buffers
        vertexBuffer = createFloatBuffer(VERTEX_COORDS)
        texCoordBuffer = createFloatBuffer(TEX_COORDS)

        // 8. Generate 2D Texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 9. Allocate offscreen bitmap and canvas
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        offscreenCanvas = Canvas(bmp)

        Log.i(TAG, "OpenGL ES 2.0 / EGL initialized successfully for MediaCodec surface ($width x $height @ ${fps}fps)")
        return true
    }

    private fun renderToGl(bmp: Bitmap, isFirstFrame: Boolean) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (isFirstFrame) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)

        vertexBuffer?.let { vb ->
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vb)
        }

        texCoordBuffer?.let { tb ->
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tb)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        // Set presentation timestamp on Android MediaCodec input surface
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        bitmap?.recycle()
        bitmap = null
        offscreenCanvas = null
        Log.i(TAG, "EGL and OpenGL resources released.")
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $info")
        }
        return shader
    }

    private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
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
        renderThread?.interrupt()
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {}
        renderThread = null
    }
}
