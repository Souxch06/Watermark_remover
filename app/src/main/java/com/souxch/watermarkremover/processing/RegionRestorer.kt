package com.souxch.watermarkremover.processing

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Restores the analysed region of one zone, frame after frame, on the CPU:
 *
 *  1. **Presence**: tells whether the logo is actually in this frame (some apps move their
 *     watermark around); the test compares the frame's gradients with the gradients the logo
 *     would produce over the frame's own background, so it does not depend on brightness.
 *  2. **Inversion**: semi-transparent pixels are un-blended, `I = (J - a*W) / (1 - a)`.
 *  3. **Temporal propagation**: the background moves while the logo does not, so what is hidden
 *     now was visible a few frames ago. The motion of the clean pixels between the previous and
 *     the current frame is estimated (block matching) and the picture restored in the previous
 *     frame is carried along it. Every value has a confidence (inverse variance); candidates are
 *     blended by confidence, with outlier rejection, so a wrong motion or a moving object never
 *     drags garbage in. Over a static scene this becomes a temporal average that removes the
 *     noise amplified by the inversion.
 *  4. **Fill**: whatever is still unknown (opaque logo, first frames) is interpolated from the
 *     nearest restored pixels.
 *
 * Pure Kotlin (testable on the JVM). One instance per zone per export/preview; not thread-safe.
 * Frames are RGBA bytes of the region, 4 bytes per pixel, rows of [width] pixels.
 */
class RegionRestorer(private val layer: WatermarkAnalyzer.Layer) {

    val width: Int = layer.width
    val height: Int = layer.height
    private val px = width * height

    /** Pixels touched by the logo (inverted or filled). */
    private val mask = BooleanArray(px) { layer.alpha[it] > 0f || layer.fill[it] }
    val hasLogo: Boolean = mask.any { it }

    /**
     * Per-frame presence gating is only enabled when the analysis itself saw sampled frames
     * without the logo (watermarks that move around). A logo found in every sampled frame is
     * always removed: a noisy presence score must never silently leave the watermark in place.
     */
    val gated: Boolean = layer.stats.presentFrames < layer.stats.frames

    // Presence taps: pairs of neighbouring pixels with the logo's colour / opacity steps.
    private val tapP: IntArray
    private val tapQ: IntArray
    private val tapN: IntArray
    private val tapDc: FloatArray
    private val tapDa: FloatArray

    // Clean pixels used to estimate the motion of the background (subsampled).
    private val template: IntArray

    // Working buffers and state carried from one frame to the next.
    private val cur = FloatArray(px * 3)
    private val out = FloatArray(px * 3)
    private val weight = FloatArray(px)
    private val lumCur = FloatArray(px)
    private val lumPrev = FloatArray(px)
    private val prevIn = FloatArray(px * 3)
    private val prevOut = FloatArray(px * 3)
    private val prevWeight = FloatArray(px)
    private val gain = FloatArray(3) { 1f }
    private val offset = FloatArray(3)
    private var hasPrevious = false
    private var wasPresent = true
    // On-line estimate of the systematic error of the inversion (per pixel): whatever is left of
    // the logo after inversion shows up as a constant difference with the picture carried from
    // clean pixels. Learned during the export, it also absorbs colour-conversion differences
    // between the analysis decoder and the export decoder.
    private val base = FloatArray(px * 3)
    private val ghostAcc = FloatArray(px * 3)
    private val ghostWeight = FloatArray(px)

    /** Presence of the logo in the last processed frame (0 = absent, 1 = fully there). */
    var presence: Float = 1f
        private set
    /** Motion of the background (pixels) between the previous and the last frame, if found. */
    var motionX: Float = 0f
        private set
    var motionY: Float = 0f
        private set
    var motionFound: Boolean = false
        private set
    /** Mean absolute difference of the best match (diagnostics). */
    var motionError: Float = 1f
        private set

    init {
        val nearest = nearestCleanIndices(mask, width, height)
        // ---- presence taps ----
        class Tap(val p: Int, val q: Int, val n: Int, val magnitude: Float)
        val taps = ArrayList<Tap>()
        fun consider(p: Int, q: Int) {
            if (!mask[p] && !mask[q]) return
            val da = layer.alpha[q] - layer.alpha[p]
            var m = 0f
            for (c in 0 until 3) m = max(m, abs(layer.colour[q * 3 + c] - layer.colour[p * 3 + c] - 0.5f * da))
            if (m < 0.01f) return
            val anchor = if (layer.alpha[p] >= layer.alpha[q]) p else q
            taps.add(Tap(p, q, nearest[anchor], m))
        }
        for (y in 0 until height) for (x in 0 until width) {
            val p = y * width + x
            if (x + 1 < width) consider(p, p + 1)
            if (y + 1 < height) consider(p, p + width)
        }
        taps.sortByDescending { it.magnitude }
        val kept = taps.take(MAX_TAPS)
        tapP = IntArray(kept.size) { kept[it].p }
        tapQ = IntArray(kept.size) { kept[it].q }
        tapN = IntArray(kept.size) { kept[it].n }
        tapDa = FloatArray(kept.size) { layer.alpha[kept[it].q] - layer.alpha[kept[it].p] }
        tapDc = FloatArray(kept.size * 3) { i -> val t = kept[i / 3]; val c = i % 3; layer.colour[t.q * 3 + c] - layer.colour[t.p * 3 + c] }
        // ---- motion template ----
        val clean = ArrayList<Int>()
        for (y in 1 until height - 1) for (x in 1 until width - 1) if (!mask[y * width + x]) clean.add(y * width + x)
        val stride = max(1, clean.size / MAX_TEMPLATE)
        template = IntArray((clean.size + stride - 1) / stride) { clean[it * stride] }
    }

    /** Forgets the previous frames (seek, new export); the learned ghost is kept. */
    fun reset() {
        hasPrevious = false
        wasPresent = true
        presence = 1f
        motionFound = false
    }

    /**
     * Restores one frame. [rgba]: region pixels, row 0 = bottom row when [flipY] (GL read-back).
     * [output] receives the restored region in display order (row 0 = top); it may be the same
     * array as [rgba].
     */
    fun process(rgba: ByteArray, flipY: Boolean, output: ByteArray) {
        unpack(rgba, flipY)
        presence = if (tapP.isEmpty()) 1f else measurePresence()
        // Hysteresis: a logo does not blink, so a present logo needs a clear drop to be declared
        // gone (and vice versa); the ramp keeps fades smooth. Biased towards "present": a logo
        // that is there but modelled imperfectly still scores well above 0.3, a missing one ~0.
        val s = when {
            !gated -> 1f
            wasPresent -> ((presence - 0.10f) / 0.20f).coerceIn(0f, 1f)
            else -> ((presence - 0.35f) / 0.20f).coerceIn(0f, 1f)
        }
        wasPresent = s >= 0.5f
        val active = s > 0f

        // ---- motion of the background since the previous frame ----
        motionFound = false
        if (hasPrevious) estimateMotion()

        // ---- base estimate: raw / inverted / fill, with confidences ----
        for (p in 0 until px) {
            val a = layer.alpha[p]
            if (!active || !mask[p] || layer.fill[p] || a <= 0f) {
                for (c in 0 until 3) out[p * 3 + c] = cur[p * 3 + c]
                weight[p] = if (!active || !mask[p]) W_CLEAN else 0f
                continue
            }
            val ae = min(a * s, WatermarkAnalyzer.MAX_INVERT_ALPHA)
            val inv = 1f / (1f - ae)
            val correct = ghostWeight[p] >= GHOST_MIN_TOTAL
            for (c in 0 until 3) {
                val v = ((cur[p * 3 + c] - layer.colour[p * 3 + c] * s) * inv).coerceIn(0f, 1f)
                base[p * 3 + c] = v
                out[p * 3 + c] = if (correct) (v - s * ghostAcc[p * 3 + c] / ghostWeight[p]).coerceIn(0f, 1f) else v
            }
            val sigma = SIGMA_NOISE * inv
            weight[p] = 1f / (sigma * sigma)
        }
        if (active) {
            // Fill pixels: distance-weighted average of the nearest restored pixels (L, R, T, B).
            for (y in 0 until height) for (x in 0 until width) {
                val p = y * width + x
                if (!mask[p] || !layer.fill[p]) continue
                var r = 0f; var g = 0f; var b = 0f; var ws = 0f
                for (k in 0 until 4) {
                    val d = layer.distances[p * 4 + k].toInt() and 0xFF
                    if (d == 0 || d == 255) continue
                    val q = when (k) {
                        0 -> p - d
                        1 -> p + d
                        2 -> p - d * width
                        else -> p + d * width
                    }
                    val wq = 1f / (d.toFloat() * d)
                    r += out[q * 3] * wq; g += out[q * 3 + 1] * wq; b += out[q * 3 + 2] * wq; ws += wq
                }
                if (ws > 0f) {
                    out[p * 3] = r / ws; out[p * 3 + 1] = g / ws; out[p * 3 + 2] = b / ws
                    weight[p] = W_FILL
                } else {
                    weight[p] = W_FILL * 0.25f
                }
            }
            // ---- temporal propagation ----
            if (motionFound) propagate()
        }

        // ---- keep state for the next frame ----
        System.arraycopy(cur, 0, prevIn, 0, px * 3)
        System.arraycopy(out, 0, prevOut, 0, px * 3)
        System.arraycopy(weight, 0, prevWeight, 0, px)
        hasPrevious = true
        pack(output, flipY)
    }

    // ------------------------------------------------------------------------------------------

    private fun unpack(rgba: ByteArray, flipY: Boolean) {
        for (y in 0 until height) {
            val srcRow = if (flipY) height - 1 - y else y
            var src = srcRow * width * 4
            var dst = y * width * 3
            for (x in 0 until width) {
                cur[dst] = (rgba[src].toInt() and 0xFF) * INV255
                cur[dst + 1] = (rgba[src + 1].toInt() and 0xFF) * INV255
                cur[dst + 2] = (rgba[src + 2].toInt() and 0xFF) * INV255
                src += 4
                dst += 3
            }
        }
    }

    private fun pack(rgba: ByteArray, @Suppress("UNUSED_PARAMETER") flipY: Boolean) {
        for (y in 0 until height) {
            var dst = y * width * 4
            var src = y * width * 3
            for (x in 0 until width) {
                rgba[dst] = toByte(out[src])
                rgba[dst + 1] = toByte(out[src + 1])
                rgba[dst + 2] = toByte(out[src + 2])
                rgba[dst + 3] = -1
                dst += 4
                src += 3
            }
        }
    }

    /**
     * Regression of the frame's gradients on the gradients the logo produces over this frame's
     * background: `e = dc - J(nearest clean pixel) * da`. About 1 when the logo is there, 0 when
     * it is not, in between while it fades.
     */
    private fun measurePresence(): Float {
        var num = 0.0
        var den = 0.0
        for (k in tapP.indices) {
            val p = tapP[k] * 3
            val q = tapQ[k] * 3
            val n = tapN[k] * 3
            val da = tapDa[k]
            for (c in 0 until 3) {
                val e = tapDc[3 * k + c] - cur[n + c] * da
                num += (cur[q + c] - cur[p + c]) * e
                den += e * e
            }
        }
        return if (den > 1e-3) (num / den).toFloat().coerceIn(-1f, 2f) else 1f
    }

    private fun estimateMotion() {
        if (template.size < MIN_TEMPLATE) return
        for (p in 0 until px) {
            lumCur[p] = (cur[p * 3] + cur[p * 3 + 1] + cur[p * 3 + 2]) * (1f / 3f)
            lumPrev[p] = (prevIn[p * 3] + prevIn[p * 3 + 1] + prevIn[p * 3 + 2]) * (1f / 3f)
        }
        // Stage 1: coarse search (step 2) over the whole range.
        var bestX = 0
        var bestY = 0
        var best = Float.MAX_VALUE
        var dy = -SEARCH
        while (dy <= SEARCH) {
            var dx = -SEARCH
            while (dx <= SEARCH) {
                val e = matchError(dx, dy)
                if (e < best) { best = e; bestX = dx; bestY = dy }
                dx += 2
            }
            dy += 2
        }
        // Stage 2: refine to the pixel around the best.
        val cx = bestX
        val cy = bestY
        for (ddy in -1..1) for (ddx in -1..1) {
            if (ddx == 0 && ddy == 0) continue
            val e = matchError(cx + ddx, cy + ddy)
            if (e < best) { best = e; bestX = cx + ddx; bestY = cy + ddy }
        }
        motionError = best
        if (best > MAX_MATCH_ERROR) return
        // Sub-pixel refinement by a parabola through the neighbours.
        val ex0 = matchError(bestX - 1, bestY)
        val ex1 = matchError(bestX + 1, bestY)
        val ey0 = matchError(bestX, bestY - 1)
        val ey1 = matchError(bestX, bestY + 1)
        motionX = bestX + parabolicOffset(ex0, best, ex1)
        motionY = bestY + parabolicOffset(ey0, best, ey1)
        motionFound = true
        estimateTone()
    }

    private fun parabolicOffset(e0: Float, e1: Float, e2: Float): Float {
        val den = e0 - 2f * e1 + e2
        if (den <= 1e-6f || e0 == Float.MAX_VALUE || e2 == Float.MAX_VALUE) return 0f
        return (0.5f * (e0 - e2) / den).coerceIn(-0.5f, 0.5f)
    }

    /**
     * Standard deviation of the luminance difference between the template and the previous frame
     * shifted by (dx, dy): insensitive to a global brightness change (exposure, fades).
     */
    private fun matchError(dx: Int, dy: Int): Float {
        var sum = 0.0
        var sum2 = 0.0
        var count = 0
        for (p in template) {
            val x = p % width - dx
            val y = p / width - dy
            if (x < 0 || y < 0 || x >= width || y >= height) continue
            val q = y * width + x
            if (mask[q]) continue
            val d = (lumCur[p] - lumPrev[q]).toDouble()
            sum += d
            sum2 += d * d
            count++
        }
        if (count < template.size / 3 || count < MIN_TEMPLATE / 2) return Float.MAX_VALUE
        val mean = sum / count
        return sqrt(max(sum2 / count - mean * mean, 0.0)).toFloat()
    }

    /**
     * Per channel gain / offset of the current frame with respect to the previous one (on the
     * clean pixels, along the motion), so that propagated values follow brightness changes.
     */
    private fun estimateTone() {
        val dx = Math.round(motionX)
        val dy = Math.round(motionY)
        for (c in 0 until 3) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            var count = 0
            for (p in template) {
                val x = p % width - dx
                val y = p / width - dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                val q = y * width + x
                if (mask[q]) continue
                val xv = prevIn[q * 3 + c].toDouble()
                val yv = cur[p * 3 + c].toDouble()
                sx += xv; sy += yv; sxx += xv * xv; sxy += xv * yv
                count++
            }
            if (count < MIN_TEMPLATE / 2) { gain[c] = 1f; offset[c] = 0f; continue }
            val varX = sxx / count - (sx / count) * (sx / count)
            val g = if (varX > 1e-4) ((sxy / count - (sx / count) * (sy / count)) / varX) else 1.0
            gain[c] = g.coerceIn(0.6, 1.6).toFloat()
            offset[c] = ((sy - gain[c] * sx) / count).toFloat()
        }
    }

    /** Blends the previous frame's restored picture, carried along the motion, into [out]. */
    private fun propagate() {
        val sigmaHop2 = SIGMA_HOP * SIGMA_HOP
        for (y in 0 until height) for (x in 0 until width) {
            val p = y * width + x
            if (!mask[p] || weight[p] >= W_CLEAN) continue
            val sx = x - motionX
            val sy = y - motionY
            if (sx < 0f || sy < 0f || sx > width - 1f || sy > height - 1f) continue
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val fx = sx - x0
            val fy = sy - y0
            val i00 = y0 * width + x0; val i10 = y0 * width + x1; val i01 = y1 * width + x0; val i11 = y1 * width + x1
            val wSrc = min(min(prevWeight[i00], prevWeight[i10]), min(prevWeight[i01], prevWeight[i11]))
            if (wSrc <= 0f) continue
            val w00 = (1f - fx) * (1f - fy); val w10 = fx * (1f - fy); val w01 = (1f - fx) * fy; val w11 = fx * fy
            val wT = 1f / (1f / wSrc + sigmaHop2)
            val wB = weight[p]
            // Outlier test: the candidate must agree with the base estimate within their noise.
            val tol = 3f * sqrt(1f / wT + 1f / max(wB, 1f)) + 0.02f
            var maxDiff = 0f
            val t = FloatArray(3)
            for (c in 0 until 3) {
                val v = prevOut[i00 * 3 + c] * w00 + prevOut[i10 * 3 + c] * w10 + prevOut[i01 * 3 + c] * w01 + prevOut[i11 * 3 + c] * w11
                t[c] = (v * gain[c] + offset[c]).coerceIn(0f, 1f)
                maxDiff = max(maxDiff, abs(t[c] - out[p * 3 + c]))
            }
            if (maxDiff > tol) continue
            if (wT >= GHOST_MIN_WEIGHT && layer.alpha[p] > 0f && !layer.fill[p]) {
                // Trusted picture from clean pixels: learn what the inversion gets wrong here.
                val newTotal = ghostWeight[p] + wT
                for (c in 0 until 3) ghostAcc[p * 3 + c] += (base[p * 3 + c] - t[c]).coerceIn(-0.15f, 0.15f) * wT
                if (newTotal > GHOST_MAX_TOTAL) {
                    val k = GHOST_MAX_TOTAL / newTotal
                    for (c in 0 until 3) ghostAcc[p * 3 + c] *= k
                    ghostWeight[p] = GHOST_MAX_TOTAL
                } else {
                    ghostWeight[p] = newTotal
                }
            }
            val total = wB + wT
            for (c in 0 until 3) out[p * 3 + c] = (out[p * 3 + c] * wB + t[c] * wT) / total
            weight[p] = min(total, W_MAX)
        }
    }

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    companion object {
        private const val INV255 = 1f / 255f
        private const val MAX_TAPS = 3000
        private const val MAX_TEMPLATE = 2500
        private const val MIN_TEMPLATE = 60
        /** Half size of the motion search window, in pixels per frame. */
        private const val SEARCH = 16
        /** Largest deviation accepted for a motion match. */
        private const val MAX_MATCH_ERROR = 0.06f
        /** Noise of the source pixels (8-bit + compression), before amplification by the inversion. */
        private const val SIGMA_NOISE = 0.02f
        /** Error added by one frame of propagation (motion / interpolation). */
        private const val SIGMA_HOP = 0.012f
        private const val W_CLEAN = 10_000f
        private const val W_FILL = 1f / (0.12f * 0.12f)
        private const val W_MAX = 2_500f
        /** Candidates at most ~2 hops away from a clean pixel teach the ghost estimate. */
        private const val GHOST_MIN_WEIGHT = 2_000f
        /** Evidence needed before the learned ghost is subtracted (about 4 trusted frames). */
        private const val GHOST_MIN_TOTAL = 8_000f
        /** Forgetting horizon of the ghost estimate (keeps adapting to slow drifts). */
        private const val GHOST_MAX_TOTAL = 200_000f

        /** For every pixel, the index of the nearest pixel outside [mask] (itself when outside). */
        fun nearestCleanIndices(mask: BooleanArray, w: Int, h: Int): IntArray {
            val out = IntArray(w * h) { -1 }
            val queue = IntArray(w * h)
            var head = 0
            var tail = 0
            for (i in 0 until w * h) if (!mask[i]) { out[i] = i; queue[tail++] = i }
            if (tail == 0) { for (i in out.indices) out[i] = i; return out }
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0 && out[i - 1] < 0) { out[i - 1] = out[i]; queue[tail++] = i - 1 }
                if (x < w - 1 && out[i + 1] < 0) { out[i + 1] = out[i]; queue[tail++] = i + 1 }
                if (y > 0 && out[i - w] < 0) { out[i - w] = out[i]; queue[tail++] = i - w }
                if (y < h - 1 && out[i + w] < 0) { out[i + w] = out[i]; queue[tail++] = i + w }
            }
            return out
        }
    }
}
