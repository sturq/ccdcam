package at.sturq.ccdcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
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

    private var width = 1
    private var height = 1
    private val startNs = System.nanoTime()

    @Volatile var stretch: Float = 1.0f
    @Volatile private var frameAvailable = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uResLoc, width.toFloat(), height.toFloat())
        val t = (System.nanoTime() - startNs) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, t)
        GLES20.glUniform1f(uStretchLoc, stretch)

        vertexBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)

        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        // texCoords are 2D, but shader expects vec4 — pad in vertex shader. We pass as vec2 via attribute
        // by using size=2 and the shader treats z=0,w=1 by default for attributes.
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(this) { frameAvailable = true }
    }
}
