package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.NormalizedRect
import com.souxch.watermarkremover.model.WatermarkZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Recovered watermark layers of all zones, packed for the GPU (see `layerAt()` in
 * [WatermarkShader]). Pure Kotlin: the GL upload is done by [GlLayerTexture].
 *
 * Atlas layout (RGBA8, width = widest region, one block per zone):
 *   rows [offset, offset + h)      texel A = (a*W rgb, opacity a)   - display row order
 *   rows [offset + h, offset + 2h) texel B = (fill flag, dist left, dist right, dist above)
 * For fill pixels, texel A's alpha holds the distance below instead of the opacity (0 there).
 */
class WatermarkLayer(
    val frameWidth: Int,
    val frameHeight: Int,
    val regions: List<Region>,
    val atlasWidth: Int,
    val atlasHeight: Int,
    /** RGBA8, [atlasWidth] x [atlasHeight], row 0 first. */
    val atlas: ByteArray,
) {
    /** Analysed region of one zone, in display pixel coordinates (origin top-left). */
    class Region(
        val zoneId: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val atlasRow: Int,
        val stats: WatermarkAnalyzer.Stats,
        /** Logo signature, see [WatermarkAnalyzer.presenceScore]. */
        val signaturePairs: IntArray = IntArray(0),
        val signatureDelta: FloatArray = FloatArray(0),
    ) {
        /** Presence of the logo (about 1 when there, 0 when absent) in the region pixels given. */
        fun presence(rgba: ByteArray, flipY: Boolean): Float =
            WatermarkAnalyzer.presenceScore(signaturePairs, signatureDelta, width, height, rgba, flipY)
    }

    /** True if at least one region can tell whether the logo is present in a given frame. */
    val hasSignature: Boolean get() = regions.any { it.stats.maskPixels > 0 && it.signaturePairs.isNotEmpty() }

    /** True if at least one zone has a usable recovered layer. */
    val hasWatermark: Boolean get() = regions.any { it.stats.maskPixels > 0 }

    /**
     * Uniform values for the given zones (same order as the `uZones` uniform): for each zone
     * `(originX, originY, width, height)` where the origin is in texture space and the size in
     * pixels. Zones without a layer get width 0 (the shader falls back to the spatial
     * reconstruction); zones whose logo is absent from the frame ([present] false) get width -1
     * (the shader leaves them untouched).
     *
     * @param yUp true for the export path (texture row 0 = visual bottom), false for the preview.
     */
    fun rectUniforms(zones: List<WatermarkZone>, yUp: Boolean, present: (Region) -> Boolean = { true }): FloatArray {
        val out = FloatArray(WatermarkShader.MAX_ZONES * 4)
        zones.take(WatermarkShader.MAX_ZONES).forEachIndexed { i, zone ->
            val region = regions.firstOrNull { it.zoneId == zone.id && it.stats.maskPixels > 0 } ?: return@forEachIndexed
            if (!present(region)) {
                out[i * 4 + 2] = -1f
                return@forEachIndexed
            }
            out[i * 4 + 0] = region.left.toFloat() / frameWidth
            // Origin = display top-left of the region. With a y-up texture that row is at
            // 1 - top/height; the shader then multiplies by a negative y scale.
            out[i * 4 + 1] = if (yUp) 1f - region.top.toFloat() / frameHeight else region.top.toFloat() / frameHeight
            out[i * 4 + 2] = region.width.toFloat()
            out[i * 4 + 3] = region.height.toFloat()
        }
        return out
    }

    fun offsetUniforms(zones: List<WatermarkZone>): FloatArray {
        val out = FloatArray(WatermarkShader.MAX_ZONES)
        zones.take(WatermarkShader.MAX_ZONES).forEachIndexed { i, zone ->
            val region = regions.firstOrNull { it.zoneId == zone.id } ?: return@forEachIndexed
            out[i] = region.atlasRow.toFloat()
        }
        return out
    }

    /** Video pixels per texture unit along x and y (y negative for a y-up texture). */
    fun scaleUniform(yUp: Boolean): FloatArray =
        floatArrayOf(frameWidth.toFloat(), if (yUp) -frameHeight.toFloat() else frameHeight.toFloat())

    companion object {
        /** Extra pixels analysed around each zone (the logo may slightly overflow the frame). */
        const val MARGIN = 8
        /** Presence score below which a frame is considered free of the logo (no inversion). */
        const val PRESENCE_THRESHOLD = 0.5f

        /** Largest region side analysed, in pixels (bigger zones are analysed at this scale). */
        const val MAX_REGION = 320

        /** Pixel rectangle (left, top, width, height) analysed for [zone] in a frame of the given size. */
        fun regionOf(zone: WatermarkZone, frameWidth: Int, frameHeight: Int): IntArray {
            val r: NormalizedRect = zone.rect.sanitized()
            val left = ((r.left * frameWidth).roundToInt() - MARGIN).coerceAtLeast(0)
            val top = ((r.top * frameHeight).roundToInt() - MARGIN).coerceAtLeast(0)
            val right = ((r.right * frameWidth).roundToInt() + MARGIN).coerceAtMost(frameWidth)
            val bottom = ((r.bottom * frameHeight).roundToInt() + MARGIN).coerceAtMost(frameHeight)
            return intArrayOf(left, top, max(right - left, 1), max(bottom - top, 1))
        }

        /** Packs the analysed layers into an atlas. [layers] entries may be null (no result). */
        fun pack(
            frameWidth: Int,
            frameHeight: Int,
            zones: List<WatermarkZone>,
            layers: List<WatermarkAnalyzer.Layer?>,
        ): WatermarkLayer {
            val usable = zones.zip(layers).filter { it.second != null }
            val regions = ArrayList<Region>()
            var atlasWidth = 1
            var atlasHeight = 0
            val placed = ArrayList<Pair<WatermarkAnalyzer.Layer, Int>>()
            for ((zone, layer) in usable) {
                val l = layer!!
                val rect = regionOf(zone, frameWidth, frameHeight)
                if (rect[2] != l.width || rect[3] != l.height) continue
                regions.add(Region(zone.id, rect[0], rect[1], l.width, l.height, atlasHeight, l.stats, l.signaturePairs, l.signatureDelta))
                placed.add(l to atlasHeight)
                atlasWidth = max(atlasWidth, l.width)
                atlasHeight += 2 * l.height
            }
            atlasHeight = max(atlasHeight, 1)
            val atlas = ByteArray(atlasWidth * atlasHeight * 4)
            for ((l, row) in placed) {
                for (y in 0 until l.height) for (x in 0 until l.width) {
                    val p = y * l.width + x
                    val a = ((row + y) * atlasWidth + x) * 4
                    val b = ((row + l.height + y) * atlasWidth + x) * 4
                    val fill = l.fill[p]
                    atlas[a + 0] = toByte(l.colour[p * 3])
                    atlas[a + 1] = toByte(l.colour[p * 3 + 1])
                    atlas[a + 2] = toByte(l.colour[p * 3 + 2])
                    atlas[a + 3] = if (fill) l.distances[p * 4 + 3] else toByte(l.alpha[p])
                    atlas[b + 0] = if (fill) 0xFF.toByte() else 0
                    atlas[b + 1] = l.distances[p * 4 + 0]
                    atlas[b + 2] = l.distances[p * 4 + 1]
                    atlas[b + 3] = l.distances[p * 4 + 2]
                }
            }
            return WatermarkLayer(frameWidth, frameHeight, regions, atlasWidth, atlasHeight, atlas)
        }

        private fun toByte(v: Float): Byte = (min(max(v, 0f), 1f) * 255f + 0.5f).toInt().toByte()
    }
}
