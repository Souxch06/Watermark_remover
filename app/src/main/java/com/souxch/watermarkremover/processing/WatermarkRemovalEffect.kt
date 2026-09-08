package com.souxch.watermarkremover.processing

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone
import java.nio.FloatBuffer

/**
 * Media3 [GlEffect] that removes the watermark zones from every frame using [WatermarkShader].
 * Plugged into the Transformer export pipeline (decoder -> this effect -> encoder), so the whole
 * process stays on the GPU.
 */
class WatermarkRemovalEffect(
    private val zones: List<WatermarkZone>,
    private val settings: RemovalSettings,
    /** Recovered watermark layer (method [WatermarkShader.METHOD_LAYER]); null = spatial only. */
    private val layer: WatermarkLayer? = null,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        WatermarkShaderProgram(zones, settings, layer, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = zones.isEmpty()
}

/** One-input / one-output shader program: the output frame has the same size as the input. */
private class WatermarkShaderProgram(
    private val zones: List<WatermarkZone>,
    private val settings: RemovalSettings,
    private val layer: WatermarkLayer?,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val quad: FloatBuffer = GlHelpers.createQuadBuffer()
    private var program = 0
    private var aPosition = 0
    private var uTexSampler = 0
    private var uTransform = 0
    private var uTexTransform = 0
    private var uTexelSize = 0
    private var uZones = 0
    private var uZoneCount = 0
    private var uMethod = 0
    private var uStrength = 0
    private var uFeather = 0
    private var emptyTexture = 0
    private var width = 0
    private var height = 0
    /** Per-frame restoration of the analysed regions (null = layer unusable at this size). */
    private var patcher: LayerPatcher? = null
    private var readFbo = 0
    private var lastPresentationTimeUs = Long.MIN_VALUE

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        if (program == 0) {
            try {
                program = GlHelpers.linkProgram(WatermarkShader.VERTEX_SHADER, WatermarkShader.FRAGMENT_SHADER_2D)
            } catch (e: GlException) {
                throw VideoFrameProcessingException(e)
            }
            aPosition = GLES20.glGetAttribLocation(program, WatermarkShader.A_FRAME_POSITION)
            uTexSampler = GLES20.glGetUniformLocation(program, WatermarkShader.U_TEX_SAMPLER)
            uTransform = GLES20.glGetUniformLocation(program, WatermarkShader.U_TRANSFORMATION_MATRIX)
            uTexTransform = GLES20.glGetUniformLocation(program, WatermarkShader.U_TEX_TRANSFORMATION_MATRIX)
            uTexelSize = GLES20.glGetUniformLocation(program, WatermarkShader.U_TEXEL_SIZE)
            uZones = GLES20.glGetUniformLocation(program, WatermarkShader.U_ZONES)
            uZoneCount = GLES20.glGetUniformLocation(program, WatermarkShader.U_ZONE_COUNT)
            uMethod = GLES20.glGetUniformLocation(program, WatermarkShader.U_METHOD)
            uStrength = GLES20.glGetUniformLocation(program, WatermarkShader.U_STRENGTH)
            uFeather = GLES20.glGetUniformLocation(program, WatermarkShader.U_FEATHER)
            try {
                emptyTexture = GlLayerTexture.uploadEmpty()
                if (layer != null && layer.hasWatermark && layer.frameWidth == inputWidth && layer.frameHeight == inputHeight) {
                    val ids = IntArray(1)
                    GLES20.glGenFramebuffers(1, ids, 0)
                    readFbo = ids[0]
                    patcher = LayerPatcher(layer).also { it.createTexture() }
                }
            } catch (e: GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    /**
     * Restores the analysed regions of the input frame (CPU) and uploads them for the shader.
     * Returns false when the layer cannot be applied to this frame (the shader then uses the
     * spatial reconstruction instead of failing the export).
     */
    private fun restoreRegions(inputTexId: Int, presentationTimeUs: Long): Boolean {
        val p = patcher ?: return false
        // A jump back in time = new pass / seek: forget the temporal state.
        if (presentationTimeUs < lastPresentationTimeUs) p.reset()
        lastPresentationTimeUs = presentationTimeUs
        val previous = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previous, 0)
        var ok = false
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                p.update(height, flipY = true)
                ok = true
            }
        } catch (e: GlException) {
            ok = false
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous[0])
        }
        var failed = false
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) failed = true
        return ok && !failed
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val restored = restoreRegions(inputTexId, presentationTimeUs)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(uTexSampler, 0)
            GLES20.glUniformMatrix4fv(uTransform, 1, false, GlHelpers.IDENTITY_MATRIX, 0)
            GLES20.glUniformMatrix4fv(uTexTransform, 1, false, GlHelpers.IDENTITY_MATRIX, 0)
            GLES20.glUniform2f(uTexelSize, 1f / width, 1f / height)
            GLES20.glUniform4fv(uZones, WatermarkShader.MAX_ZONES, WatermarkShader.zoneUniforms(zones, yUp = true), 0)
            GLES20.glUniform1i(uZoneCount, WatermarkShader.zoneCount(zones))
            GLES20.glUniform1i(uMethod, WatermarkShader.methodId(settings, if (restored) layer else null))
            GLES20.glUniform1f(uStrength, settings.strength)
            GLES20.glUniform1f(uFeather, WatermarkShader.featherTextureUnits(settings, width, height))
            if (restored) patcher!!.bind(program, zones, yUp = true)
            else GlLayerTexture.bind(program, emptyTexture, null, zones, yUp = true)

            // Client-side vertex data: make sure no VBO is bound or the pointer would be read as an offset.
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GlHelpers.checkGlError("drawFrame")
        } catch (e: GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        GlHelpers.deleteTexture(emptyTexture)
        emptyTexture = 0
        patcher?.release()
        patcher = null
        if (readFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
            readFbo = 0
        }
    }
}
