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
    private var layerTexture = 0
    private var width = 0
    private var height = 0

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
            layerTexture = try {
                if (layer != null && layer.frameWidth == inputWidth && layer.frameHeight == inputHeight) {
                    GlLayerTexture.upload(layer)
                } else {
                    GlLayerTexture.uploadEmpty()
                }
            } catch (e: GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            GLES20.glUniform1i(uTexSampler, 0)
            GLES20.glUniformMatrix4fv(uTransform, 1, false, GlHelpers.IDENTITY_MATRIX, 0)
            GLES20.glUniformMatrix4fv(uTexTransform, 1, false, GlHelpers.IDENTITY_MATRIX, 0)
            GLES20.glUniform2f(uTexelSize, 1f / width, 1f / height)
            GLES20.glUniform4fv(uZones, WatermarkShader.MAX_ZONES, WatermarkShader.zoneUniforms(zones, yUp = true), 0)
            GLES20.glUniform1i(uZoneCount, WatermarkShader.zoneCount(zones))
            val usable = layer != null && layer.frameWidth == width && layer.frameHeight == height
            GLES20.glUniform1i(uMethod, WatermarkShader.methodId(settings, if (usable) layer else null))
            GLES20.glUniform1f(uStrength, settings.strength)
            GLES20.glUniform1f(uFeather, WatermarkShader.featherTextureUnits(settings, width, height))
            GlLayerTexture.bind(program, layerTexture, if (usable) layer else null, zones, yUp = true)

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
        GlHelpers.deleteTexture(layerTexture)
        layerTexture = 0
    }
}
