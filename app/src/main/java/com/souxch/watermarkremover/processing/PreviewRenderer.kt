package com.souxch.watermarkremover.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a single frame through the exact same shader used for the export, off-screen, and
 * returns the result as a Bitmap. This gives the editor a faithful "after" preview.
 *
 * Runs on a dedicated pbuffer EGL context created on demand; work is dispatched on
 * [Dispatchers.Default] so it never blocks the UI. A whole preview at 720p takes a few ms.
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
            // GLUtils.texImage2D needs a software ARGB_8888 bitmap.
            val upload = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
            val session = EglSession(upload.width, upload.height)
            try {
                // The layer is texel aligned with the video: only usable at the video's own size.
                val usable = layer?.takeIf { it.frameWidth == upload.width && it.frameHeight == upload.height }
                session.draw(upload, zones, settings, usable)
            } finally {
                session.release()
                if (upload !== source) upload.recycle()
            }
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

        fun draw(source: Bitmap, zones: List<WatermarkZone>, settings: RemovalSettings, layer: WatermarkLayer?): Bitmap {
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
                GLES20.glUniform1i(loc(program, WatermarkShader.U_METHOD), WatermarkShader.methodId(settings, layer))
                GLES20.glUniform1f(loc(program, WatermarkShader.U_STRENGTH), settings.strength)
                GLES20.glUniform1f(loc(program, WatermarkShader.U_FEATHER), WatermarkShader.featherTextureUnits(settings, width, height))
                layerTex = if (layer != null) GlLayerTexture.upload(layer) else GlLayerTexture.uploadEmpty()
                // Zones whose logo is not in this frame are left untouched (see LayerPresence).
                val present = layer?.let { LayerPresence.fromBitmap(it, source) }
                GlLayerTexture.bind(program, layerTex, layer, zones, yUp = false) { present == null || it.zoneId in present }

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
