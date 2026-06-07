package at.sturq.ccdcam

import android.content.Context
import android.opengl.GLES20
import java.io.IOException

object GlUtil {
    fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log\n--src--\n$src")
        }
        return shader
    }

    fun makeProgram(vsrc: String, fsrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("program link failed: $log")
        }
        return prog
    }

    fun loadAsset(ctx: Context, name: String): String {
        return try {
            ctx.assets.open(name).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw RuntimeException("failed to read asset $name", e)
        }
    }

    fun checkGl(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw RuntimeException("$op: glError $e")
    }
}
