package at.sturq.ccdcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Offscreen renderer that runs the CCD shader on a still bitmap.
 * Used in EDIT mode (apply filter to gallery photo) and PHOTO mode
 * (apply filter to ImageCapture output).
 *
 * Uses a sampler2D variant of the shader (not samplerExternalOES).
 */
object PhotoFilter {

    fun process(context: Context, uri: Uri): Bitmap? {
        val src = decodeOriented(context, uri) ?: return null
        return process(context, src)
    }

    fun process(context: Context, src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)

        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, IntArray(1), 0)
        val config = configs[0]

        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)

        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, pbAttrs, 0)

        EGL14.eglMakeCurrent(display, surface, surface, ctx)

        val program = makeProgram()
        val tex = uploadTexture(src)

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f) // flip Y (texture origin)
        val vBuf = directFloatBuffer(verts)
        val tBuf = directFloatBuffer(texCoords)

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTex = GLES20.glGetUniformLocation(program, "sTexture")
        val uRes = GLES20.glGetUniformLocation(program, "uResolution")
        val uTime = GLES20.glGetUniformLocation(program, "uTime")
        val uStretch = GLES20.glGetUniformLocation(program, "uStretch")
        val uDispA = GLES20.glGetUniformLocation(program, "uDisplayAspect")
        val uContA = GLES20.glGetUniformLocation(program, "uContentAspect")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
        GLES20.glUniform1f(uTime, 0f)
        GLES20.glUniform1f(uStretch, 1f)
        val aspect = w.toFloat() / h.toFloat()
        GLES20.glUniform1f(uDispA, aspect)
        GLES20.glUniform1f(uContA, aspect) // matches → no letterbox

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(buf)

        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)

        return out
    }

    private fun decodeOriented(context: Context, uri: Uri): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val orient = try {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        return if (m.isIdentity) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    private fun makeProgram(): Int {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        // sampler2D variant of the live shader, copied verbatim except for the sampler type
        val fs = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform vec2 uResolution;
            uniform float uTime;
            uniform float uStretch;
            uniform float uDisplayAspect;
            uniform float uContentAspect;

            const float LINES = 480.0;
            const float SMEAR_THRESHOLD = 0.88;
            const float SMEAR_STRENGTH = 1.6;
            const int   SMEAR_SAMPLES = 8;
            const float SMEAR_RANGE = 0.45;
            const float FLARE_THRESHOLD = 0.93;
            const float FLARE_RANGE = 0.15;
            const int   FLARE_SAMPLES = 5;
            const float FLARE_STRENGTH = 0.55;
            const float CHROMA_NOISE_AMP = 0.07;
            const float LUMA_GRAIN_AMP = 0.05;
            const float BLACK_LIFT = 0.06;
            const vec3  WARM_GRADE = vec3(1.07, 1.02, 0.94);
            const float DESAT = 0.9;
            const float SCANLINE_AMP = 0.025;
            const float VIGNETTE_STRENGTH = 0.45;

            float hash(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            float brightMask(vec2 uv, float t) {
                vec2 cl = clamp(uv, vec2(0.001), vec2(0.999));
                return smoothstep(t, 1.0, luma(texture2D(sTexture, cl).rgb));
            }

            void main() {
                vec2 uv = clamp(vec2((vTexCoord.x - 0.5) / max(uStretch, 0.1) + 0.5, vTexCoord.y), vec2(0.0), vec2(1.0));
                vec3 col = texture2D(sTexture, uv).rgb;
                float smear = 0.0;
                for (int i = 1; i <= SMEAR_SAMPLES; i++) {
                    float t = float(i) / float(SMEAR_SAMPLES);
                    float dy = t * SMEAR_RANGE;
                    float mu = brightMask(vec2(uv.x, uv.y - dy), SMEAR_THRESHOLD);
                    float md = brightMask(vec2(uv.x, uv.y + dy), SMEAR_THRESHOLD);
                    smear += max(mu, md) * (1.0 - t);
                }
                smear = smear / float(SMEAR_SAMPLES) * SMEAR_STRENGTH;
                col += vec3(smear * 0.9, smear * 0.95, smear * 1.1);
                float flare = 0.0;
                for (int i = 1; i <= FLARE_SAMPLES; i++) {
                    float t = float(i) / float(FLARE_SAMPLES);
                    float dx = t * FLARE_RANGE;
                    float ml = brightMask(vec2(uv.x - dx, uv.y), FLARE_THRESHOLD);
                    float mr = brightMask(vec2(uv.x + dx, uv.y), FLARE_THRESHOLD);
                    flare += max(ml, mr) * (1.0 - t) * (1.0 - t);
                }
                flare = flare / float(FLARE_SAMPLES) * FLARE_STRENGTH;
                col += vec3(flare, flare, flare * 1.05);
                float n = (hash(uv * uResolution + uTime) - 0.5) * CHROMA_NOISE_AMP;
                col.r += n * 0.6; col.b -= n * 0.6;
                float g = (hash(uv * uResolution * 2.0 + uTime * 1.3) - 0.5) * LUMA_GRAIN_AMP;
                col += g;
                col = col * (1.0 - BLACK_LIFT) + BLACK_LIFT;
                col *= WARM_GRADE;
                float l = luma(col);
                col = mix(vec3(l), col, DESAT);
                float scan = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * sin(uv.y * LINES * 3.14159);
                col *= scan;
                vec2 q = vTexCoord - 0.5;
                col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;
                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
        val vsId = compile(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vsId)
        GLES20.glAttachShader(p, fsId)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("compile failed: $log")
        }
        return s
    }

    private fun directFloatBuffer(arr: FloatArray): FloatBuffer {
        val b = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(arr); b.position(0); return b
    }
}
