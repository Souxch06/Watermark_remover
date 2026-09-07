package com.souxch.watermarkremover.processing

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Thrown when an OpenGL call fails; carries the GL error code(s) in the message. */
class GlException(message: String) : RuntimeException(message)

/**
 * Minimal OpenGL ES 2.0 helpers. We intentionally do not depend on Media3's internal `GlUtil`
 * / `GlProgram` classes: their uniform handling does not support array uniforms (needed for the
 * list of zones) and their signatures are unstable across releases.
 */
object GlHelpers {

    /** Full-screen quad in normalized device coordinates, drawn as a triangle strip. */
    private val QUAD_VERTICES = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    val IDENTITY_MATRIX: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun createQuadBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VERTICES)
                position(0)
            }

    fun checkGlError(operation: String) {
        var error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return
        val sb = StringBuilder("$operation failed:")
        while (error != GLES20.GL_NO_ERROR) {
            sb.append(" 0x").append(Integer.toHexString(error))
            error = GLES20.glGetError()
        }
        throw GlException(sb.toString())
    }

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw GlException("glCreateShader($type) returned 0")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw GlException("Could not compile $kind shader: $log")
        }
        return shader
    }

    fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (e: GlException) {
            GLES20.glDeleteShader(vertexShader)
            throw e
        }
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            throw GlException("glCreateProgram returned 0")
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        // Shaders are no longer needed once linked; flag them for deletion.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw GlException("Could not link program: $log")
        }
        checkGlError("linkProgram")
        return program
    }

    fun createTexture2d(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        checkGlError("glGenTextures")
        val texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        checkGlError("glTexImage2D")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    fun createFramebuffer(texId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        checkGlError("glGenFramebuffers")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw GlException("Framebuffer incomplete: 0x${Integer.toHexString(status)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    fun deleteTexture(texId: Int) {
        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }

    fun deleteFramebuffer(fboId: Int) {
        if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
    }
}
