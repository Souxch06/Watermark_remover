package com.souxch.watermarkremover.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.os.Build
import com.souxch.watermarkremover.model.VideoInfo
import com.souxch.watermarkremover.model.WatermarkZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Samples frames spread over the whole video, runs [WatermarkAnalyzer] on each zone and packs the
 * result into a [WatermarkLayer]. This is the "look through the video to find what the logo hides"
 * step; it runs once per video/zone layout, in the background, and takes a few seconds.
 */
class WatermarkLayerBuilder(private val context: Context) {

    /**
     * @param onProgress 0..100
     */
    suspend fun build(
        info: VideoInfo,
        zones: List<WatermarkZone>,
        onProgress: (Int) -> Unit = {},
    ): WatermarkLayer = withContext(Dispatchers.Default) {
        val width = info.displayWidth
        val height = info.displayHeight
        val frameCount = frameCountFor(info.durationMs)
        val regions = zones.map { WatermarkLayer.regionOf(it, width, height) }
        val samples = regions.map { ArrayList<ByteArray>(frameCount) }
        if (regions.any { it[2] * it[3] > WatermarkAnalyzer.MAX_REGION_PIXELS }) {
            // Too big to analyse in reasonable time/memory: spatial reconstruction only.
            return@withContext WatermarkLayer.pack(width, height, zones, zones.map { null })
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, info.uri)
            val durationUs = info.durationMs * 1000L
            // Avoid the very first / last frames (fade-ins, end cards) and spread the samples.
            val startUs = durationUs / 20
            val endUs = durationUs - durationUs / 20
            val seen = HashSet<Long>()
            var done = 0
            fun sample(k: Int, exact: Boolean) {
                val t = if (frameCount == 1) durationUs / 2 else startUs + (endUs - startUs) * k / (frameCount - 1)
                val bitmap = getFrame(retriever, t, width, height, exact) ?: return
                try {
                    // Skip duplicated frames (still videos, repeated sync frames).
                    if (!seen.add(quickHash(bitmap))) return
                    regions.forEachIndexed { i, r -> samples[i].add(extract(bitmap, r)) }
                } finally {
                    bitmap.recycle()
                }
            }
            // Pass 1: sync frames (fast). Pass 2, only if too few distinct frames: exact frames.
            for (k in 0 until frameCount) {
                coroutineContext.ensureActive()
                sample(k, exact = false)
                onProgress(((++done) * 40) / frameCount)
            }
            if (samples.first().size < MIN_DISTINCT_FRAMES) {
                for (k in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    sample(k, exact = true)
                    onProgress(40 + ((k + 1) * 40) / frameCount)
                }
            }
            onProgress(80)
        } finally {
            retriever.release()
        }

        val layers = regions.mapIndexed { i, r ->
            coroutineContext.ensureActive()
            val result = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(r[2], r[3], samples[i]))
            onProgress(80 + ((i + 1) * 20) / regions.size)
            result
        }
        WatermarkLayer.pack(width, height, zones, layers)
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeUs: Long, width: Int, height: Int, exact: Boolean): Bitmap? {
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, option, width, height)
        } else {
            retriever.getFrameAtTime(timeUs, option)
        } ?: return null
        if (frame.width == width && frame.height == height) return frame
        // Exact size matters: the layer is texel aligned with the video.
        val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
        if (scaled !== frame) frame.recycle()
        return scaled
    }

    /** Region pixels as packed RGB bytes (row-major). */
    private fun extract(bitmap: Bitmap, r: IntArray): ByteArray {
        val w = r[2]
        val h = r[3]
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, r[0], r[1], w, h)
        val out = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = (p shr 16).toByte()
            out[i * 3 + 1] = (p shr 8).toByte()
            out[i * 3 + 2] = p.toByte()
        }
        return out
    }

    private fun quickHash(bitmap: Bitmap): Long {
        var h = 1125899906842597L
        val step = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                h = 31 * h + bitmap.getPixel(x, y)
                x += step
            }
            y += stepY
        }
        return h
    }

    companion object {
        /** Below this many distinct sync frames, exact (slower) frame decoding is used as well. */
        const val MIN_DISTINCT_FRAMES = 8

        /** Number of frames sampled: enough for a robust median, bounded for speed. */
        fun frameCountFor(durationMs: Long): Int = when {
            durationMs <= 0 -> 16
            durationMs < 4_000 -> 10
            durationMs < 15_000 -> 16
            durationMs < 60_000 -> 20
            else -> 24
        }
    }
}

/**
 * Reads back the analysed regions of the frame currently bound to a framebuffer and tells, for
 * each of them, whether the logo is there. Some apps alternate the position of their watermark
 * during the video: frames without the logo must not be "un-blended" or a negative ghost appears.
 */
class LayerPresence(private val layer: WatermarkLayer, private val frameHeight: Int) {
    private val buffers = HashMap<Int, ByteBuffer>()
    private val bytes = HashMap<Int, ByteArray>()

    /**
     * Must be called with the source frame bound as the READ framebuffer (GL row 0 = bottom).
     * Returns the regions holding the logo; every region when the layer has no signature.
     */
    fun present(): Set<Int> {
        if (!layer.hasSignature) return layer.regions.map { it.zoneId }.toSet()
        val result = HashSet<Int>()
        for (region in layer.regions) {
            if (region.stats.maskPixels == 0) continue
            val size = region.width * region.height * 4
            val buffer = buffers.getOrPut(region.zoneId) { ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()) }
            val array = bytes.getOrPut(region.zoneId) { ByteArray(size) }
            buffer.rewind()
            // GL rows start at the bottom: region top row `top` is GL row frameHeight - top - height.
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(
                region.left, frameHeight - region.top - region.height, region.width, region.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
            )
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4)
            buffer.rewind()
            buffer.get(array)
            if (region.presence(array, flipY = true) >= WatermarkLayer.PRESENCE_THRESHOLD) result.add(region.zoneId)
        }
        return result
    }

    companion object {
        /** Same test on a decoded frame (row 0 = top), for the preview. */
        fun fromBitmap(layer: WatermarkLayer, frame: Bitmap): Set<Int> {
            if (!layer.hasSignature) return layer.regions.map { it.zoneId }.toSet()
            val result = HashSet<Int>()
            for (region in layer.regions) {
                if (region.stats.maskPixels == 0) continue
                if (region.left + region.width > frame.width || region.top + region.height > frame.height) continue
                val pixels = IntArray(region.width * region.height)
                frame.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
                val rgba = ByteArray(pixels.size * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgba[i * 4] = (p shr 16).toByte()
                    rgba[i * 4 + 1] = (p shr 8).toByte()
                    rgba[i * 4 + 2] = p.toByte()
                    rgba[i * 4 + 3] = -1
                }
                if (region.presence(rgba, flipY = false) >= WatermarkLayer.PRESENCE_THRESHOLD) result.add(region.zoneId)
            }
            return result
        }
    }
}

/** GL side of [WatermarkLayer]: uploads the atlas as an RGBA8 texture (nearest filtering). */
object GlLayerTexture {

    fun upload(layer: WatermarkLayer): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val buffer = ByteBuffer.allocateDirect(layer.atlas.size).order(ByteOrder.nativeOrder())
        buffer.put(layer.atlas).rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, layer.atlasWidth, layer.atlasHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GlHelpers.checkGlError("layer texImage2D")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /**
     * Sets every layer uniform of [program]; binds the texture on unit 1.
     * @param present which regions hold the logo in the frame being drawn (others fall back to
     *   the spatial reconstruction)
     */
    fun bind(
        program: Int,
        textureId: Int,
        layer: WatermarkLayer?,
        zones: List<WatermarkZone>,
        yUp: Boolean,
        present: (WatermarkLayer.Region) -> Boolean = { true },
    ) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, WatermarkShader.U_LAYER_SAMPLER), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val rects = layer?.rectUniforms(zones, yUp, present) ?: FloatArray(WatermarkShader.MAX_ZONES * 4)
        val offsets = layer?.offsetUniforms(zones) ?: FloatArray(WatermarkShader.MAX_ZONES)
        val scale = layer?.scaleUniform(yUp) ?: floatArrayOf(1f, 1f)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, WatermarkShader.U_LAYER_RECT), WatermarkShader.MAX_ZONES, rects, 0)
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(program, WatermarkShader.U_LAYER_OFFSET), WatermarkShader.MAX_ZONES, offsets, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, WatermarkShader.U_LAYER_SCALE), scale[0], scale[1])
        val tw = layer?.atlasWidth ?: 1
        val th = layer?.atlasHeight ?: 1
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, WatermarkShader.U_LAYER_TEXEL), 1f / tw, 1f / th)
    }

    /** A 1x1 placeholder so the sampler is always bound to something valid. */
    fun uploadEmpty(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }
}
