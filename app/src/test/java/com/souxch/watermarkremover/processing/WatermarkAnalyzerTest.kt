package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.NormalizedRect
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic "video": a moving textured background with a static semi-transparent logo composited
 * on top. The analyser must find the logo and the inversion must give the background back.
 */
class WatermarkAnalyzerTest {

    private val w = 96
    private val h = 48
    private val logoAlpha = 0.6f

    /** Logo: a filled rectangle + a bar, white, alpha [logoAlpha]. */
    private fun logoMask(x: Int, y: Int): Boolean =
        (x in 20..40 && y in 14..34) || (x in 50..80 && y in 22..28)

    private fun background(x: Int, y: Int, t: Int): FloatArray {
        // Moving stripes + gradient texture; shifts by 3 px per frame.
        val xx = x + 3 * t
        val yy = y + 2 * t
        val r = 0.35f + 0.25f * sin(xx * 0.31f) + 0.15f * sin(yy * 0.17f)
        val g = 0.40f + 0.20f * sin(xx * 0.23f + 1f) + 0.10f * sin((xx + yy) * 0.11f)
        val b = 0.45f + 0.20f * sin(yy * 0.27f + 2f) + 0.10f * sin(xx * 0.13f)
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun frames(n: Int, alpha: Float = logoAlpha, noise: Float = 0.004f): List<ByteArray> {
        val rnd = Random(7)
        return (0 until n).map { t ->
            val out = ByteArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) {
                val bg = background(x, y, t)
                val a = if (logoMask(x, y)) alpha else 0f
                for (c in 0 until 3) {
                    val v = (a * 1f + (1f - a) * bg[c] + (rnd.nextFloat() - 0.5f) * 2f * noise).coerceIn(0f, 1f)
                    out[(y * w + x) * 3 + c] = (v * 255f + 0.5f).toInt().toByte()
                }
            }
            out
        }
    }

    @Test
    fun `finds a semi-transparent logo and inverts it`() {
        val fr = frames(16)
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, fr))
        assertNotNull(layer)
        layer!!
        assertTrue("watermark expected", layer.hasWatermark)
        assertEquals("ok", layer.stats.reason)
        // Mask covers the logo and (almost) nothing else.
        var hit = 0
        var logo = 0
        var falsePositives = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            val flagged = layer.alpha[p] > 0f || layer.fill[p]
            if (logoMask(x, y)) { logo++; if (flagged) hit++ } else if (flagged) falsePositives++
        }
        assertTrue("recall $hit/$logo", hit >= logo * 0.9)
        assertTrue("false positives $falsePositives", falsePositives < logo * 0.25)
        // Semi-transparent -> inverted (not filled), with a sensible opacity.
        assertTrue(layer.stats.invertedPixels > layer.stats.filledPixels)
        val centre = 27 * w + 30
        assertTrue("alpha ${layer.alpha[centre]}", abs(layer.alpha[centre] - logoAlpha) < 0.12f)
        // Inversion of one frame restores the background at the logo centre.
        val t = 5
        val frame = fr[t]
        val bg = background(30, 27, t)
        for (c in 0 until 3) {
            val j = ((frame[centre * 3 + c].toInt() and 0xFF) / 255f)
            val restored = (j - layer.colour[centre * 3 + c]) / (1f - layer.alpha[centre])
            assertTrue("channel $c restored $restored vs ${bg[c]}", abs(restored - bg[c]) < 0.06f)
        }
    }

    @Test
    fun `opaque logo is flagged for filling with distances to clean pixels`() {
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, frames(12, alpha = 1f)))!!
        assertTrue(layer.hasWatermark)
        assertTrue(layer.stats.filledPixels > layer.stats.invertedPixels)
        val centre = 27 * w + 30
        assertTrue(layer.fill[centre])
        val left = layer.distances[centre * 4].toInt() and 0xFF
        val right = layer.distances[centre * 4 + 1].toInt() and 0xFF
        assertTrue("left $left", left in 8..13)
        assertTrue("right $right", right in 8..13)
    }

    @Test
    fun `static video gives no layer`() {
        val fr = List(12) { frames(1)[0] }
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, fr))!!
        assertTrue(!layer.hasWatermark)
        assertEquals("static", layer.stats.reason)
    }

    @Test
    fun `too few frames gives null`() {
        assertEquals(null, WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, frames(3))))
    }

    @Test
    fun `layer packing keeps regions texel aligned and method switches to layer mode`() {
        val zone = WatermarkZone(1, NormalizedRect(0.5f, 0.5f, 0.6f, 0.6f))
        val region = WatermarkLayer.regionOf(zone, 1920, 1080)
        assertEquals(960 - WatermarkLayer.MARGIN, region[0])
        assertEquals(540 - WatermarkLayer.MARGIN, region[1])
        val analysed = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, frames(8)))!!
        // Region size must match the analysed size for the layer to be used.
        val m = WatermarkLayer.MARGIN
        val small = WatermarkZone(2, NormalizedRect(m / 200f, m / 100f, (w - m) / 200f, (h - m) / 100f))
        val r2 = WatermarkLayer.regionOf(small, 200, 100)
        assertEquals(0, r2[0])
        assertEquals(0, r2[1])
        assertEquals(w, r2[2])
        assertEquals(h, r2[3])
        val packed = WatermarkLayer.pack(200, 100, listOf(small), listOf(analysed))
        assertEquals(1, packed.regions.size)
        assertEquals(w, packed.atlasWidth)
        assertEquals(2 * h, packed.atlasHeight)
        assertTrue(packed.hasWatermark)
        assertEquals(WatermarkShader.METHOD_LAYER, WatermarkShader.methodId(RemovalSettings(), packed))
        val rects = packed.rectUniforms(listOf(small), yUp = false)
        assertEquals(0f, rects[0], 1e-6f)
        assertEquals(w.toFloat(), rects[2], 1e-6f)
        assertEquals(0f, packed.offsetUniforms(listOf(small))[0], 1e-6f)
    }
}
