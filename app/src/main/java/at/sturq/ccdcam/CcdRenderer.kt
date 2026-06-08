package at.sturq.ccdcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig as JEGLConfig
import javax.microedition.khronos.opengles.GL10

class CcdRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var program = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    private val vertexCoords = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    private val texCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )
    private lateinit var vertexBuf: FloatBuffer
    private lateinit var texBuf: FloatBuffer

    private val texMatrix = FloatArray(16)
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexMatrixLoc = 0
    private var uResLoc = 0
    private var uTimeLoc = 0
    private var uTextureLoc = 0
    private var uStretchLoc = 0

    @Volatile var stretch: Float = 0.72f

    private var width = 1
    private var height = 1
    private val startNs = System.nanoTime()

    @Volatile private var frameAvailable = false
    @Volatile private var pendingSnapshot: ((Bitmap?) -> Unit)? = null

    // ---- dual-surface (encoder) state ----
    @Volatile private var pendingEncoderSurface: Surface? = null
    @Volatile private var pendingEncoderWidth: Int = 0
    @Volatile private var pendingEncoderHeight: Int = 0
    @Volatile private var teardownEncoderSurface: Boolean = false
    private var encoderEgl: EGLSurface? = null
    private var encoderW = 0
    private var encoderH = 0
    private var encoderStartNs: Long = 0L
    private var lastEncoderFrameNs: Long = 0L
    private val encoderFrameIntervalNs: Long = 1_000_000_000L / 30  // cap encoder to 30 fps
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    /**
     * Attach an encoder input Surface; renderer will dual-render to it until null is passed.
     * [ptsBaseNs] is the monotonic clock value the encoder considers t=0 — pass the same
     * reference the audio pipeline uses so A/V share a zero-based timeline.
     */
    fun setEncoderSurface(s: Surface?, w: Int, h: Int, ptsBaseNs: Long = System.nanoTime()) {
        if (s == null) {
            teardownEncoderSurface = true
        } else {
            pendingEncoderSurface = s
            pendingEncoderWidth = w
            pendingEncoderHeight = h
            encoderStartNs = ptsBaseNs
            lastEncoderFrameNs = 0L
        }
    }

    /** Capture next rendered display frame as an upright Bitmap. */
    fun requestFrameSnapshot(cb: (Bitmap?) -> Unit) { pendingSnapshot = cb }

    override fun onSurfaceCreated(gl: GL10?, config: JEGLConfig?) {
        val vsrc = GlUtil.loadAsset(context, "shaders/passthrough.vert")
        val fsrc = GlUtil.loadAsset(context, "shaders/ccd.frag")
        program = GlUtil.makeProgram(vsrc, fsrc)

        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uResLoc = GLES20.glGetUniformLocation(program, "uResolution")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")
        uStretchLoc = GLES20.glGetUniformLocation(program, "uStretch")

        vertexBuf = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertexCoords); position(0)
            }
        texBuf = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texCoords); position(0)
            }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener(this)
        onSurfaceTextureReady(surfaceTexture)

        eglDisplay = EGL14.eglGetCurrentDisplay()
        eglContext = EGL14.eglGetCurrentContext()
        eglConfig = chooseRecordableConfig() ?: chooseFallbackConfig()
    }

    private fun chooseRecordableConfig(): EGLConfig? = chooseConfigWith(
        intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_ANDROID_RECORDABLE, 1,
            EGL14.EGL_NONE
        )
    )

    private fun chooseFallbackConfig(): EGLConfig? = chooseConfigWith(
        intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
    )

    private fun chooseConfigWith(attribs: IntArray): EGLConfig? {
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        return if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, cfgs, 0, 1, n, 0) && n[0] > 0)
            cfgs[0] else null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
    }

    private fun drawTo(viewportW: Int, viewportH: Int) {
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uResLoc, viewportW.toFloat(), viewportH.toFloat())
        val t = (System.nanoTime() - startNs) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uStretchLoc, stretch)

        vertexBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        // draw to display surface
        drawTo(width, height)

        // photo snapshot: read display framebuffer back to a portrait bitmap
        val snap = pendingSnapshot
        if (snap != null) {
            pendingSnapshot = null
            try { snap(readBitmap()) } catch (_: Throwable) { snap(null) }
        }

        // dual-render to encoder surface if attached — throttled to 30fps so the
        // encoder gets evenly-spaced frames and motion looks fluid instead of mushy.
        handleEncoderSurfaceLifecycle()
        encoderEgl?.let { encSurf ->
            val now = System.nanoTime()
            if (now - lastEncoderFrameNs < encoderFrameIntervalNs) return@let
            lastEncoderFrameNs = now
            val savedDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val savedRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
            if (EGL14.eglMakeCurrent(eglDisplay, encSurf, encSurf, eglContext)) {
                drawTo(encoderW, encoderH)
                // PTS must be relative to encoderStartNs, otherwise the muxer records
                // a track that starts at the absolute monotonic clock (~hours since boot)
                // and the file's reported duration becomes nonsense.
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encSurf, now - encoderStartNs)
                EGL14.eglSwapBuffers(eglDisplay, encSurf)
                EGL14.eglMakeCurrent(eglDisplay, savedDraw, savedRead, eglContext)
            }
        }
    }

    private fun readBitmap(): Bitmap {
        val w = width; val h = height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        // glReadPixels gives bottom-up; flip Y so the bitmap matches what's on screen
        val m = Matrix().apply { postScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
    }

    private fun handleEncoderSurfaceLifecycle() {
        if (teardownEncoderSurface) {
            encoderEgl?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            encoderEgl = null
            pendingEncoderSurface = null
            teardownEncoderSurface = false
        }
        val ps = pendingEncoderSurface
        if (encoderEgl == null && ps != null && eglConfig != null) {
            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            encoderEgl = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, ps, surfAttribs, 0)
            encoderW = pendingEncoderWidth
            encoderH = pendingEncoderHeight
            pendingEncoderSurface = null
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(this) { frameAvailable = true }
    }

    companion object {
        private const val EGL_ANDROID_RECORDABLE = 0x3142
    }
}
