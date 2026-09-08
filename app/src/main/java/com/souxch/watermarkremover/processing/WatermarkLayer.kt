package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.NormalizedRect
import com.souxch.watermarkremover.model.WatermarkZone
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recovered watermark layers of all zones. Holds the per-zone analysis ([WatermarkAnalyzer.Layer])
 * and creates the [RegionRestorer]s that restore the picture frame after frame; the restored
 * regions are packed into an RGBA8 atlas (one block per zone, display row order) which the shader
 * pastes over the video (see `layerAt()` in [WatermarkShader]). Pure Kotlin: the GL upload is done
 * by [GlLayerTexture].
 */
class WatermarkLayer(
    val frameWidth: Int,
    val frameHeight: Int,
    val regions: List<Region>,
    val atlasWidth: Int,
    val atlasHeight: Int,
) {
    /** Analysed region of one zone, in display pixel coordinates (origin top-left). */
    class Region(
        val zoneId: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val atlasRow: Int,
        val analysis: WatermarkAnalyzer.Layer,
    ) {
        val stats: WatermarkAnalyzer.Stats get() = analysis.stats
    }

    /** True if at least one zone has a usable recovered layer. */
    val hasWatermark: Boolean get() = regions.any { it.stats.maskPixels > 0 }

    /** Per-frame restoration state, one per region (created lazily; call [reset] on seeks). */
    fun newRestorers(): Map<Int, RegionRestorer> =
        regions.filter { it.stats.maskPixels > 0 }.associate { it.zoneId to RegionRestorer(it.analysis) }

    /**
     * Uniform values for the given zones (same order as the `uZones` uniform): for each zone
     * `(originX, originY, width, height)` where the origin is in texture space and the size in
     * pixels; zones without a layer get width 0 (spatial reconstruction).
     *
     * @param yUp true for the export path (texture row 0 = visual bottom), false for the preview.
     */
    fun rectUniforms(zones: List<WatermarkZone>, yUp: Boolean): FloatArray {
        val out = FloatArray(WatermarkShader.MAX_ZONES * 4)
        zones.take(WatermarkShader.MAX_ZONES).forEachIndexed { i, zone ->
            val region = regions.firstOrNull { it.zoneId == zone.id && it.stats.maskPixels > 0 } ?: return@forEachIndexed
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

        /** Pixel rectangle (left, top, width, height) analysed for [zone] in a frame of the given size. */
        fun regionOf(zone: WatermarkZone, frameWidth: Int, frameHeight: Int): IntArray {
            val r: NormalizedRect = zone.rect.sanitized()
            val left = ((r.left * frameWidth).roundToInt() - MARGIN).coerceAtLeast(0)
            val top = ((r.top * frameHeight).roundToInt() - MARGIN).coerceAtLeast(0)
            val right = ((r.right * frameWidth).roundToInt() + MARGIN).coerceAtMost(frameWidth)
            val bottom = ((r.bottom * frameHeight).roundToInt() + MARGIN).coerceAtMost(frameHeight)
            return intArrayOf(left, top, max(right - left, 1), max(bottom - top, 1))
        }

        /** Packs the analysed layers. [layers] entries may be null (no result). */
        fun pack(
            frameWidth: Int,
            frameHeight: Int,
            zones: List<WatermarkZone>,
            layers: List<WatermarkAnalyzer.Layer?>,
        ): WatermarkLayer {
            val regions = ArrayList<Region>()
            var atlasWidth = 1
            var atlasHeight = 0
            for ((zone, layer) in zones.zip(layers)) {
                val l = layer ?: continue
                val rect = regionOf(zone, frameWidth, frameHeight)
                if (rect[2] != l.width || rect[3] != l.height) continue
                regions.add(Region(zone.id, rect[0], rect[1], l.width, l.height, atlasHeight, l))
                atlasWidth = max(atlasWidth, l.width)
                atlasHeight += l.height
            }
            return WatermarkLayer(frameWidth, frameHeight, regions, atlasWidth, max(atlasHeight, 1))
        }
    }
}
