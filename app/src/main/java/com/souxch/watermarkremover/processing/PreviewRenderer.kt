package com.souxch.watermarkremover.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the editor's "after" preview of a single frame: zones with a recovered watermark layer
 * are restored on the CPU with the very same [RegionRestorer] the export uses; the other zones go
 * through the export shader, off-screen, on a short-lived pbuffer EGL context. Work is dispatched
 * on [Dispatchers.Default] so it never blocks the UI, and any GL failure falls back to the input
 * frame (the video must always stay visible while the zones are being placed).
 */
class PreviewRenderer {

    suspend fun render(
        source: Bitmap,
        zones: List<WatermarkZone>,
        settings: RemovalSettings,
        layer: WatermarkLayer? = null,
    ): Bitmap =
        withContext(Dispatchers.Default) {
            if (zones.isEmpty() || !settings.method.usesShader) return@withContext source
            // 1. Zones with a recovered watermark layer are restored on the CPU, exactly like the
            //    export does frame after frame (RegionRestorer), straight into a copy of the
            //    bitmap: no GL state involved, so this part can never come out black.
            val usable = layer?.takeIf {
                settings.method == RemovalMethod.INPAINT && it.hasWatermark &&
                    it.frameWidth == source.width && it.frameHeight == source.height
            }
            val restored = if (usable != null) BitmapRestorer(usable).restore(source) else null
            val remaining = if (usable == null) zones else zones.filter { z -> usable.regions.none { it.zoneId == z.id && it.stats.maskPixels > 0 } }
            if (remaining.isEmpty()) return@withContext restored ?: source
            // 2. The other zones go through the shader used by the export (spatial methods).
            val input = restored ?: source
            // GLUtils.texImage2D needs a software ARGB_8888 bitmap.
            val upload = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
            val result = try {
                val session = EglSession(upload.width, upload.height)
                try {
                    session.draw(upload, remaining, settings)
                } finally {
                    session.release()
                }
            } catch (e: Exception) {
                null
            } finally {
                if (upload !== input) upload.recycle()
            }
            // A GL failure (or a driver handing back an empty frame) must never hide the video:
            // fall back to what we have rather than to a black box.
            if (result == null || looksBlank(result, input)) input else result
        }

    /** True when [rendered] is uniformly black while [reference] is not (a broken GL draw). */
    private fun looksBlank(rendered: Bitmap, reference: Bitmap): Boolean {
        val w = rendered.width
        val h = rendered.height
        if (w == 0 || h == 0) return true
        var renderedBlack = true
        var referenceBlack = true
        val steps = 8
        for (j in 0 until steps) for (i in 0 until steps) {
            val x = (w * (2 * i + 1)) / (2 * steps)
            val y = (h * (2 * j + 1)) / (2 * steps)
            if ((rendered.getPixel(x, y) and 0xFFFFFF) != 0) renderedBlack = false
            if (x < reference.width && y < reference.height && (reference.getPixel(x, y) and 0xFFFFFF) != 0) referenceBlack = false
            if (!renderedBlack) return false
        }
        return renderedBlack && !referenceBlack
    }

    /** Short-lived pbuffer context; creating one per render keeps the code simple and leak-free. */
    private class EglSession(private val width: Int, private val height: Int) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw GlException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw GlException("eglInitialize failed")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw GlException("eglChooseConfig failed")
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw GlException("eglCreateContext failed")
            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) throw GlException("eglCreatePbufferSurface failed")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw GlException("eglMakeCurrent failed")
        }

        /** Spatial methods only (zones with a recovered layer are restored on the CPU, see [render]). */
        fun draw(source: Bitmap, zones: List<WatermarkZone>, settings: RemovalSettings): Bitmap {
            val program = GlHelpers.linkProgram(WatermarkShader.VERTEX_SHADER, WatermarkShader.FRAGMENT_SHADER_2D)
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            var layerTex = 0
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
                GlHelpers.checkGlError("texImage2D")

                GLES20.glViewport(0, 0, width, height)
                GLES20.glUseProgram(program)
                // Placeholder layer texture first (it lives on unit 1), then the video on unit 0:
                // nothing may rebind unit 0 afterwards or the shader samples nothing (black frame).
                layerTex = GlLayerTexture.uploadEmpty()
                GlLayerTexture.bind(program, layerTex, null, zones, yUp = false)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glUniform1i(loc(program, WatermarkShader.U_TEX_SAMPLER), 0)
                // The Bitmap is uploaded as-is (texture row 0 = visual top), so we keep identity
                // matrices and hand the shader zones expressed in that same y-down space. The
                // shader is symmetric in y, so the result is identical to the export path.
                GLES20.glUniformMatrix4fv(loc(program, WatermarkShader.U_TRANSFORMATION_MATRIX), 1, false, GlHelpers.IDENTITY_MATRIX, 0)
                GLES20.glUniformMatrix4fv(loc(program, WatermarkShader.U_TEX_TRANSFORMATION_MATRIX), 1, false, GlHelpers.IDENTITY_MATRIX, 0)
                GLES20.glUniform2f(loc(program, WatermarkShader.U_TEXEL_SIZE), 1f / width, 1f / height)
                GLES20.glUniform4fv(loc(program, WatermarkShader.U_ZONES), WatermarkShader.MAX_ZONES, WatermarkShader.zoneUniforms(zones, yUp = false), 0)
                GLES20.glUniform1i(loc(program, WatermarkShader.U_ZONE_COUNT), WatermarkShader.zoneCount(zones))
                GLES20.glUniform1i(loc(program, WatermarkShader.U_METHOD), WatermarkShader.methodId(settings, null))
                GLES20.glUniform1f(loc(program, WatermarkShader.U_STRENGTH), settings.strength)
                GLES20.glUniform1f(loc(program, WatermarkShader.U_FEATHER), WatermarkShader.featherTextureUnits(settings, width, height))

                val aPos = GLES20.glGetAttribLocation(program, WatermarkShader.A_FRAME_POSITION)
                val quad = GlHelpers.createQuadBuffer()
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
                GlHelpers.checkGlError("draw")

                val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                GlHelpers.checkGlError("glReadPixels")
                buffer.rewind()
                // Framebuffer row 0 (NDC y = -1) sampled texture row 0 = visual top, and
                // glReadPixels returns rows starting at framebuffer row 0: the result is upright.
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
            } finally {
                GLES20.glDeleteTextures(1, tex, 0)
                GlHelpers.deleteTexture(layerTex)
                GLES20.glDeleteProgram(program)
            }
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }

        private fun loc(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    }
}
