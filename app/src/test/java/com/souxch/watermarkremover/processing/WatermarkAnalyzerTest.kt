package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.NormalizedRect
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    private fun frames(n: Int, alpha: Float = logoAlpha, noise: Float = 0.004f): List<ByteArray> =
        framesOf(n, { x, y -> if (logoMask(x, y)) alpha else 0f }, noise)

    /** Frames with a per-pixel logo opacity ([alphaOf] in 0..1, white logo). */
    private fun framesOf(n: Int, alphaOf: (Int, Int) -> Float, noise: Float = 0.004f): List<ByteArray> {
        val rnd = Random(7)
        return (0 until n).map { t ->
            val out = ByteArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) {
                val bg = background(x, y, t)
                val a = alphaOf(x, y)
                for (c in 0 until 3) {
                    val v = (a * 1f + (1f - a) * bg[c] + (rnd.nextFloat() - 0.5f) * 2f * noise).coerceIn(0f, 1f)
                    out[(y * w + x) * 3 + c] = (v * 255f + 0.5f).toInt().toByte()
                }
            }
            out
        }
    }

    /** Chebyshev distance to the hard logo shape (0 = inside). */
    private fun logoDistance(x: Int, y: Int): Int {
        fun cheb(x0: Int, y0: Int, x1: Int, y1: Int) =
            max(max(max(x0 - x, x - x1), 0), max(max(y0 - y, y - y1), 0))
        return min(cheb(20, 14, 40, 34), cheb(50, 22, 80, 28))
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
        // Inversion of one frame restores the background at the logo centre. The tolerance
        // accounts for the faint-edge re-estimation, which trades a fraction of a percent of
        // single-pixel accuracy on hard edges for a much cleaner removal of soft ones.
        val t = 5
        val frame = fr[t]
        val bg = background(30, 27, t)
        for (c in 0 until 3) {
            val j = ((frame[centre * 3 + c].toInt() and 0xFF) / 255f)
            val restored = (j - layer.colour[centre * 3 + c]) / (1f - layer.alpha[centre])
            assertTrue("channel $c restored $restored vs ${bg[c]}", abs(restored - bg[c]) < 0.065f)
        }
    }

    @Test
    fun `soft anti-aliased logo edges are recovered and inverted`() {
        // White logo with a 3 px anti-aliased ramp (interior 0.55, then 0.28 / 0.12 / 0.04):
        // the faint tails used to survive the inversion as a pale, blurred halo of the text.
        val soft = { x: Int, y: Int ->
            when (logoDistance(x, y)) { 0 -> 0.55f; 1 -> 0.28f; 2 -> 0.12f; 3 -> 0.04f; else -> 0f }
        }
        val fr = framesOf(16, soft)
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, fr))!!
        assertTrue(layer.hasWatermark)
        assertEquals("ok", layer.stats.reason)
        // The ramp is detected and inverted, and the inversion removes it (residue = mean
        // |restored - background| over the ramp pixels; the noise floor is ~0.004).
        var ramp = 0
        var flagged = 0
        var residue = 0.0
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (logoDistance(x, y) !in 1..3) continue
            ramp++
            val p = y * w + x
            if (layer.alpha[p] > 0f || layer.fill[p]) flagged++
            for (t in fr.indices) {
                val bg = background(x, y, t)
                for (c in 0 until 3) {
                    val j = (fr[t][p * 3 + c].toInt() and 0xFF) / 255f
                    val r = if (layer.alpha[p] > 0f) abs((j - layer.colour[p * 3 + c]) / (1f - layer.alpha[p]) - bg[c]) else abs(j - bg[c])
                    residue += r
                    count++
                }
            }
        }
        assertTrue("ramp recall $flagged/$ramp", flagged >= ramp * 0.8f)
        assertTrue("ramp residue ${residue / count}", residue / count < 0.022)
        // End-to-end: the restored region is close to the background and settles over time.
        val all = framesOf(40, soft)
        val restorer = RegionRestorer(layer)
        val errors = ArrayList<Float>()
        for (t in 0 until 40) {
            val out = rgba(all[t], false).also { restorer.process(it, false, it) }
            var err = 0f
            var n = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (logoDistance(x, y) > 3) continue
                val bg = background(x, y, t)
                for (c in 0 until 3) { err += abs((out[(y * w + x) * 4 + c].toInt() and 0xFF) / 255f - bg[c]); n++ }
            }
            errors.add(err / n)
        }
        assertTrue("first frame error ${errors[0]}", errors[0] < 0.045f)
        val late = errors.takeLast(10).average()
        assertTrue("late error $late vs first ${errors[0]}", late < errors[0] && late < 0.03f)
    }

    @Test
    fun `hard logo edges leave no ring`() {
        // With a hard edge, the pixel just outside the logo carries no watermark: it must not
        // be over-inverted (the old dilated mask left a faint dark ring around the glyphs).
        val fr = frames(16)
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, fr))!!
        assertTrue(layer.hasWatermark)
        var ring = 0
        var flagged = 0
        var residue = 0.0
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (logoDistance(x, y) != 1) continue
            ring++
            val p = y * w + x
            if (layer.alpha[p] > 0.02f) flagged++
            for (t in fr.indices) {
                val bg = background(x, y, t)
                for (c in 0 until 3) {
                    val j = (fr[t][p * 3 + c].toInt() and 0xFF) / 255f
                    residue += abs(j - bg[c])
                    count++
                }
            }
        }
        assertTrue("ring flagged $flagged/$ring", flagged < ring * 0.35f)
        assertTrue("ring residue ${residue / count}", residue / count < 0.008f)
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
    fun `frames without the logo are told apart from frames with it`() {
        // Logo present in the even frames only (apps alternating the watermark position).
        val with = frames(20)
        val without = frames(20, alpha = 0f)
        val mixed = with.indices.map { if (it % 2 == 0) with[it] else without[it] }
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, mixed))!!
        assertTrue(layer.hasWatermark)
        assertEquals(10, layer.stats.presentFrames)
        // The restorer measures the presence frame by frame and leaves clean frames untouched.
        val restorer = RegionRestorer(layer)
        assertTrue("gating enabled for a moving logo", restorer.gated)
        val outWith = rgba(with[3], false).also { restorer.process(it, false, it) }
        assertTrue("presence with logo ${restorer.presence}", restorer.presence > 0.7f)
        val src = rgba(without[4], false)
        val outWithout = src.copyOf().also { restorer.process(it, false, it) }
        assertTrue("presence without logo ${restorer.presence}", restorer.presence < 0.3f)
        assertTrue(outWithout.contentEquals(src))
        // Flipped input (GL read-back) gives the same presence and an upright output.
        val flipped = RegionRestorer(layer)
        val outFlipped = rgba(with[3], true).also { flipped.process(it, true, it) }
        assertTrue("flipped ${flipped.presence}", abs(flipped.presence - 1f) < 0.3f)
        val centre = 27 * w + 30
        for (c in 0 until 3) assertEquals(outWith[centre * 4 + c], outFlipped[centre * 4 + c])
    }

    @Test
    fun `restoration follows the moving background and improves over frames`() {
        val fr = frames(40)
        val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(w, h, fr.take(16)))!!
        assertEquals("logo in every sampled frame", layer.stats.frames, layer.stats.presentFrames)
        val restorer = RegionRestorer(layer)
        // A logo seen in every sampled frame is always removed (no per-frame gating).
        assertTrue("no gating for a fixed logo", !restorer.gated)
        val errors = ArrayList<Float>()
        for (t in 0 until 40) {
            val out = rgba(fr[t], false).also { restorer.process(it, false, it) }
            var err = 0f
            var count = 0
            for (y in 16 until 32) for (x in 22 until 38) {
                val bg = background(x, y, t)
                for (c in 0 until 3) { err += abs((out[(y * w + x) * 4 + c].toInt() and 0xFF) / 255f - bg[c]); count++ }
            }
            errors.add(err / count)
        }
        assertTrue("first frame error ${errors[0]}", errors[0] < 0.05f)
        val late = errors.takeLast(10).average()
        assertTrue("late error $late vs first ${errors[0]}", late < errors[0])
        assertTrue("motion found", restorer.motionFound)
        // The texture is sampled at (x + 3t, y + 2t): the content moves by (-3, -2) per frame.
        assertTrue("motion ${restorer.motionX},${restorer.motionY}", abs(restorer.motionX + 3f) < 0.6f && abs(restorer.motionY + 2f) < 0.6f)
    }

    private fun rgba(frame: ByteArray, flip: Boolean): ByteArray {
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val src = (y * w + x) * 3
            val dst = ((if (flip) h - 1 - y else y) * w + x) * 4
            out[dst] = frame[src]; out[dst + 1] = frame[src + 1]; out[dst + 2] = frame[src + 2]; out[dst + 3] = -1
        }
        return out
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
        assertEquals(h, packed.atlasHeight)
        assertTrue(packed.hasWatermark)
        assertEquals(WatermarkShader.METHOD_LAYER, WatermarkShader.methodId(RemovalSettings(), packed))
        val rects = packed.rectUniforms(listOf(small), yUp = false)
        assertEquals(0f, rects[0], 1e-6f)
        assertEquals(w.toFloat(), rects[2], 1e-6f)
        assertEquals(0f, packed.offsetUniforms(listOf(small))[0], 1e-6f)
        assertEquals(1, packed.newRestorers().size)
    }
}
