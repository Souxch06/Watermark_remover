package com.souxch.watermarkremover.processing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Recovers the watermark layer that a video app composited on top of the picture, so that the
 * picture BEHIND the watermark can be restored (instead of being painted over).
 *
 * Model: every frame is `J = a * W + (1 - a) * I` where `I` is the true picture, `W` the
 * watermark colour and `a` its opacity (both constant over time because the logo does not move).
 * Because the picture changes from frame to frame while the logo does not, the gradients of the
 * logo are the only ones that are identical in every frame. We therefore:
 *  0. find which sampled frames actually contain the logo (some apps alternate the position of
 *     their watermark, so a zone may only hold it part of the time);
 *  1. take, at every pixel, the median over those frames of the horizontal / vertical gradients
 *     and keep only the ones that are consistent (much larger than their spread);
 *  2. integrate them (Poisson equation, solved exactly with a sine transform) -> `V = a*(W - I~)`,
 *     the watermark relief, which is zero wherever no logo is present -> a pixel-accurate mask;
 *  3. estimate the opacity `a` per pixel: from how much the pixel values vary over time compared
 *     with their neighbourhood (a semi-transparent logo damps the variations by `1 - a`) and from
 *     the projection of the relief on the logo colour;
 *  4. build per-pixel `c = a*W` and `a`, and check on the sample frames that the inversion
 *     `I = (J - c) / (1 - a)` really removes the logo; parts that are opaque or that fail the
 *     check are flagged for spatial filling instead.
 * The per-frame work (presence test, inversion, temporal propagation) is done by [RegionRestorer].
 *
 * Everything is pure Kotlin (no Android types) so it runs on the JVM tests. All arrays are
 * row-major, `[y * width + x]`; frames are packed RGB bytes, maps are floats in 0..1.
 */
object WatermarkAnalyzer {

    /** Max opacity we still invert; above that the picture is (almost) gone -> fill. */
    const val MAX_INVERT_ALPHA = 0.8f
    /** A watermark layer is only trusted if this many frames were analysed. */
    const val MIN_FRAMES = 6
    /** Smallest subset of frames on which the logo may be analysed when it is not always there. */
    const val MIN_PRESENT_FRAMES = 4
    /** Largest region analysed (pixels); bigger zones fall back to the spatial reconstruction. */
    const val MAX_REGION_PIXELS = 300_000

    /** Frames of the analysed region: each `width*height*3` RGB bytes (row-major). */
    class Frames(val width: Int, val height: Int, val frames: List<ByteArray>)

    /**
     * Result of the analysis for one zone (region = zone + margin, same size as the input).
     *  - [colour]  : per pixel `a*W` (RGB), zero outside the logo
     *  - [alpha]   : per pixel opacity used for the inversion, zero outside the logo
     *  - [fill]    : true where the pixel must be re-synthesised from its neighbours instead
     *  - [distances]: for fill pixels, distance (in pixels) to the nearest non-fill pixel to the
     *                left / right / top / bottom (255 = none in that direction)
     */
    class Layer(
        val width: Int,
        val height: Int,
        val colour: FloatArray,
        val alpha: FloatArray,
        val fill: BooleanArray,
        val distances: ByteArray,
        val stats: Stats,
    ) {
        val hasWatermark: Boolean get() = stats.maskPixels > 0
    }

    data class Stats(
        val frames: Int,
        val presentFrames: Int,
        val motion: Float,
        val maskPixels: Int,
        val invertedPixels: Int,
        val filledPixels: Int,
        val components: Int,
        val reason: String,
    )

    private const val INV255 = 1f / 255f
    private const val TAU_GRADIENT = 0.02f
    private const val K_CONSISTENT = 2.0f
    private const val TAU_RELIEF = 0.05f
    private const val MIN_COMPONENT = 6
    private const val MIN_MOTION = 0.012f
    private const val RING = 4
    private const val DILATE = 1
    private const val REFINE_PASSES = 3
    private const val REFINE_GAIN = 0.8f

    // --- soft edge band (anti-aliased / compression-softened tails of the logo) ---
    /** Width (px) of the band outside the mask where faint logo tails are looked for. */
    private const val EDGE_BAND = 3
    /** Main relief above this is trusted as-is; below it the opacity is re-estimated. */
    private const val FRINGE_KEEP = 0.2f
    /** Fringe pixels must reach this opacity to be inverted (else they stay untouched). */
    private const val EDGE_MIN_ALPHA = 0.02f

    @Suppress("NOTHING_TO_INLINE")
    private inline fun v(b: ByteArray, j: Int): Float = (b[j].toInt() and 0xFF) * INV255

    /** Per-pixel inversion parameters, used to validate the layer on the sample frames. */
    private class Inversion(val colour: FloatArray, val alpha: FloatArray, val fill: BooleanArray)

    fun analyze(input: Frames): Layer? {
        val w = input.width
        val h = input.height
        if (input.frames.size < MIN_FRAMES || w < 2 * RING + 4 || h < 2 * RING + 4 || w * h > MAX_REGION_PIXELS) return null
        val px = w * h
        val empty = { reason: String, motion: Float, present: Int ->
            Layer(w, h, FloatArray(px * 3), FloatArray(px), BooleanArray(px), ByteArray(px * 4),
                Stats(input.frames.size, present, motion, 0, 0, 0, 0, reason))
        }

        // --- 0. which frames contain the logo? ----------------------------------------------
        val presentIdx = selectPresentFrames(input.frames, w, h)
        val frames = presentIdx.map { input.frames[it] }
        val n = frames.size
        if (n < MIN_PRESENT_FRAMES) return null

        // --- temporal median / spread of the pixel values -----------------------------------
        val median = FloatArray(px * 3)
        val spread = FloatArray(px)
        val tmp = FloatArray(n)
        for (i in 0 until px) {
            var s = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmp[t] = v(frames[t], i * 3 + c)
                val (m, mad) = medianMad(tmp, n)
                median[i * 3 + c] = m
                s += mad
            }
            spread[i] = s / 3f
        }
        // Motion measured on the ring around the zone (the logo never lives there): temporal
        // spread of the pixels once each frame's global brightness is normalised, so that a still
        // picture with exposure changes / fades is still recognised as still.
        val ringIdx = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < RING || x >= w - RING || y < RING || y >= h - RING) ringIdx.add(y * w + x)
        }
        // Per frame gain / offset of the ring w.r.t. the temporal median (exposure model).
        val ringGain = FloatArray(n)
        val ringOffset = FloatArray(n)
        for (t in 0 until n) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in ringIdx) for (c in 0 until 3) {
                val x = median[i * 3 + c].toDouble()
                val y = v(frames[t], i * 3 + c).toDouble()
                sx += x; sy += y; sxx += x * x; sxy += x * y
            }
            val cnt = 3.0 * ringIdx.size
            val varX = sxx / cnt - (sx / cnt) * (sx / cnt)
            val g = if (varX > 1e-5) ((sxy / cnt - (sx / cnt) * (sy / cnt)) / varX).coerceIn(0.3, 3.0) else 1.0
            ringGain[t] = g.toFloat()
            ringOffset[t] = ((sy - g * sx) / cnt).toFloat()
        }
        val ringValues = FloatArray(ringIdx.size)
        val tmpRing = FloatArray(n)
        ringIdx.forEachIndexed { k, i ->
            var s = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmpRing[t] = v(frames[t], i * 3 + c) - (ringGain[t] * median[i * 3 + c] + ringOffset[t])
                s += medianMad(tmpRing, n).second
            }
            ringValues[k] = s / 3f
        }
        val motion = medianOf(ringValues, ringValues.size)
        if (motion < MIN_MOTION) return empty("static", motion, n)

        // --- 1. consistent median gradients -> divergence -----------------------------------
        val gx = FloatArray(px * 3)   // gradient between (x, y) and (x + 1, y)
        val gy = FloatArray(px * 3)   // gradient between (x, y) and (x, y + 1)
        consistentGradients(frames, w, h, n, dx = 1, dy = 0, out = gx, inv = null)
        consistentGradients(frames, w, h, n, dx = 0, dy = 1, out = gy, inv = null)

        // --- 2. Poisson integration per channel -> relief V ---------------------------------
        val relief = FloatArray(px * 3)
        val f = FloatArray(px)
        for (c in 0 until 3) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var d = gx[i * 3 + c] + gy[i * 3 + c]
                if (x > 0) d -= gx[(i - 1) * 3 + c]
                if (y > 0) d -= gy[(i - w) * 3 + c]
                f[i] = d
            }
            val sol = PoissonSolver.solve(f, w, h)
            for (i in 0 until px) relief[i * 3 + c] = sol[i]
        }
        val reliefMax = FloatArray(px) { i -> max(abs(relief[i * 3]), max(abs(relief[i * 3 + 1]), abs(relief[i * 3 + 2]))) }

        // --- mask -------------------------------------------------------------------------
        val mask = BooleanArray(px) { reliefMax[it] > TAU_RELIEF }
        var labels = Components.label(mask, w, h)
        Components.removeSmall(mask, labels, MIN_COMPONENT)
        repeat(DILATE) { dilate(mask, w, h) }
        for (y in 0 until h) for (x in 0 until w) {
            if (x < RING || x >= w - RING || y < RING || y >= h - RING) mask[y * w + x] = false
        }
        if (mask.none { it }) return empty("no watermark", motion, n)
        labels = Components.label(mask, w, h)
        val componentCount = labels.max()

        // --- background statistics under the logo (harmonic interpolation) --------------------
        val background = HarmonicFill.fill(median, 3, mask, w, h)
        val spreadRef = HarmonicFill.fill(spread, 1, mask, w, h)
        // Per-pixel opacity from the variance cue (valid where the background really varies),
        // smoothed with a 3x3 median inside the mask to kill the estimation noise.
        val aVarRaw = FloatArray(px) { p ->
            if (mask[p] && spreadRef[p] > 0.02f) (1f - spread[p] / spreadRef[p]).coerceIn(0f, 1f) else -1f
        }
        val aVar = medianFilterMasked(aVarRaw, w, h)

        // --- 3. per component opacity + colour, per pixel refinement -----------------------
        val alpha = FloatArray(px)
        val colour = FloatArray(px * 3)
        val fill = BooleanArray(px)
        val compColours = Array(componentCount + 1) { FloatArray(3) }
        val compAlphas = FloatArray(componentCount + 1)
        for (comp in 1..componentCount) {
            val members = ArrayList<Int>()
            for (i in 0 until px) if (labels[i] == comp) members.add(i)
            // Core = the strongest part of the relief (avoids anti-aliased edges).
            val strengths = FloatArray(members.size) { reliefMax[members[it]] }
            val p90 = percentile(strengths, 0.9f)
            val core = members.filter { reliefMax[it] >= 0.5f * p90 }.ifEmpty { members }
            val sRef = medianOf(FloatArray(core.size) { spreadRef[core[it]] }, core.size)
            var aComp = if (sRef > 0.02f) {
                medianOf(FloatArray(core.size) { i -> val p = core[i]; (1f - spread[p] / max(spreadRef[p], 1e-4f)).coerceIn(0f, 1f) }, core.size)
            } else {
                // Regression cue: J~ = a*W + (1-a)*I~ -> slope of J~ vs I~ is (1 - a).
                regressionAlpha(median, background, core)
            }
            aComp = aComp.coerceIn(0.05f, 1f)
            // Logo colour: W = I~ + V / a (median over the core, per channel).
            val compColour = FloatArray(3) { c ->
                val vals = FloatArray(core.size) { i -> val p = core[i]; (background[p * 3 + c] + relief[p * 3 + c] / aComp) }
                medianOf(vals, vals.size).coerceIn(0f, 1f)
            }
            compColour.copyInto(compColours[comp])
            compAlphas[comp] = aComp
            for (p in members) {
                // Projection of the relief on (W - I~): coverage of this pixel by the logo.
                var num = 0f
                var den = 0f
                for (c in 0 until 3) {
                    val d = compColour[c] - background[p * 3 + c]
                    num += relief[p * 3 + c] * d
                    den += d * d
                }
                val aProj = if (den > 0.01f) (num / den).coerceIn(0f, 1f) else aComp
                // Blend with the variance cue where it is reliable.
                val wVar = ((spreadRef[p] - 0.02f) / 0.05f).coerceIn(0f, 1f)
                val ap = if (aVar[p] >= 0f && wVar > 0f) (aProj + wVar * aVar[p]) / (1f + wVar) else aProj
                val a = ap.coerceIn(0f, min(1f, aComp + 0.15f))
                if (aComp > MAX_INVERT_ALPHA || a > MAX_INVERT_ALPHA) {
                    fill[p] = true
                } else {
                    alpha[p] = a
                    for (c in 0 until 3) colour[p * 3 + c] = (relief[p * 3 + c] + a * background[p * 3 + c]).coerceIn(0f, 1f)
                }
            }
        }

        // --- 3b. soft edge band -----------------------------------------------------------------
        // The fixed thresholds of steps 1-2 mishandle the anti-aliased (or compression-softened)
        // tails of the logo: their ramp is under-estimated while the relief bleeds between close
        // glyphs — either way a faint, blurred copy of the text survives the inversion as a halo.
        // The faint pixels are re-estimated directly from the data instead.
        extendSoftEdges(
            w, h, median, mask, labels, reliefMax,
            alpha, colour, fill, compColours, compAlphas, componentCount,
        )

        // --- 4. ghost removal on the inverted frames ----------------------------------------------
        // The thresholded gradients of step 1 miss the faint tails of the logo edges, so c is
        // slightly under-estimated and a pale copy of the logo survives the inversion. The median
        // gradients of the INVERTED frames inside the mask integrate to exactly that ghost: fold
        // it back into c (a few passes, the estimate converges quickly). When the ghost demands
        // more colour than the opacity allows (c = a*W <= a), it is a that was under-estimated:
        // let it grow, else the correction saturates and the residue stays.
        val invert = BooleanArray(px) { mask[it] && !fill[it] }
        if (invert.any { it }) repeat(REFINE_PASSES) { removeGhost(frames, w, h, n, colour, alpha, fill, invert) }

        // --- validation: does the inversion really remove the consistent gradients? ------------
        val energyBefore = consistentEnergy(frames, w, h, n, null)
        val energyAfter = consistentEnergy(frames, w, h, n, Inversion(colour, alpha, fill))
        for (comp in 1..componentCount) {
            var e0 = 0f
            var e1 = 0f
            for (i in 0 until px) if (labels[i] == comp && !fill[i]) { e0 += energyBefore[i]; e1 += energyAfter[i] }
            if (e0 > 0f && e1 > 0.6f * e0) {
                for (i in 0 until px) if (labels[i] == comp) fill[i] = true
            }
        }
        for (i in 0 until px) if (fill[i]) { alpha[i] = 0f; colour[i * 3] = 0f; colour[i * 3 + 1] = 0f; colour[i * 3 + 2] = 0f }

        val distances = fillDistances(fill, w, h)
        val maskCount = mask.count { it }
        val filled = fill.count { it }
        val invertedCount = alpha.count { it > 0f }
        return Layer(w, h, colour, alpha, fill, distances,
            Stats(input.frames.size, n, motion, maskCount, invertedCount, filled, componentCount, "ok"))
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Indices of the frames that contain the logo. The mean gradient over all frames shows the
     * logo (background gradients average out); each frame is then scored against it and the
     * scores are split at their largest gap when they are clearly bimodal.
     */
    fun selectPresentFrames(frames: List<ByteArray>, w: Int, h: Int): List<Int> {
        val all = frames.indices.toList()
        val total = frames.size
        if (total < MIN_FRAMES + 2) return all
        val px = w * h
        // Per-pixel MEDIAN gradient over all frames: the logo's edges survive it (present in at
        // least half of the frames), the picture's edges do not (unless the picture is static,
        // in which case every frame scores alike and nothing is excluded).
        val medX = FloatArray(px * 3)
        val medY = FloatArray(px * 3)
        val tmp = FloatArray(total)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            for (c in 0 until 3) {
                if (x + 1 < w) {
                    for (t in 0 until total) tmp[t] = v(frames[t], (i + 1) * 3 + c) - v(frames[t], i * 3 + c)
                    medX[i * 3 + c] = medianOf(tmp, total)
                }
                if (y + 1 < h) {
                    for (t in 0 until total) tmp[t] = v(frames[t], (i + w) * 3 + c) - v(frames[t], i * 3 + c)
                    medY[i * 3 + c] = medianOf(tmp, total)
                }
            }
        }
        val magnitudes = FloatArray(px * 2)
        for (i in 0 until px) {
            magnitudes[i] = max(abs(medX[i * 3]), max(abs(medX[i * 3 + 1]), abs(medX[i * 3 + 2])))
            magnitudes[px + i] = max(abs(medY[i * 3]), max(abs(medY[i * 3 + 1]), abs(medY[i * 3 + 2])))
        }
        val threshold = max(percentile(magnitudes, 0.98f), 0.02f)
        val tapsX = ArrayList<Int>()
        val tapsY = ArrayList<Int>()
        var den = 0.0
        for (i in 0 until px) {
            if (magnitudes[i] >= threshold) { tapsX.add(i); for (c in 0 until 3) den += medX[i * 3 + c] * medX[i * 3 + c] }
            if (magnitudes[px + i] >= threshold) { tapsY.add(i); for (c in 0 until 3) den += medY[i * 3 + c] * medY[i * 3 + c] }
        }
        if (den <= 1e-6 || tapsX.size + tapsY.size < 20) return all
        // Score of each frame = regression of its gradients on the median ones (1 = logo there).
        val scores = FloatArray(total)
        for (t in 0 until total) {
            val fr = frames[t]
            var num = 0.0
            for (i in tapsX) for (c in 0 until 3) num += (v(fr, (i + 1) * 3 + c) - v(fr, i * 3 + c)) * medX[i * 3 + c]
            for (i in tapsY) for (c in 0 until 3) num += (v(fr, (i + w) * 3 + c) - v(fr, i * 3 + c)) * medY[i * 3 + c]
            scores[t] = (num / den).toFloat()
        }
        val sorted = scores.copyOf().also { it.sort() }
        val top = sorted.last()
        if (top <= 0f) return all
        // Split at the largest gap of the sorted scores, when clearly bimodal.
        var bestGap = 0f
        var bestK = -1
        for (k in 0 until total - 1) {
            val gap = sorted[k + 1] - sorted[k]
            if (gap > bestGap && total - 1 - k >= MIN_PRESENT_FRAMES) { bestGap = gap; bestK = k }
        }
        if (bestK < 0 || bestGap < 0.4f * top) return all
        val cut = sorted[bestK]
        // The low cluster must really look like "no logo" (scores near zero): a logo that is
        // always there but sits on backgrounds of varying brightness spreads its scores
        // continuously and must not be split (that would enable per-frame gating for nothing).
        if (cut > 0.3f * top) return all
        return all.filter { scores[it] > cut }
    }

    /** Median of the per-frame gradients, kept only when consistent across frames. */
    private fun consistentGradients(
        frames: List<ByteArray>, w: Int, h: Int, n: Int, dx: Int, dy: Int, out: FloatArray, inv: Inversion?,
    ) {
        val tmp = FloatArray(n)
        val med = FloatArray(3)
        for (y in 0 until h - dy) for (x in 0 until w - dx) {
            val i = y * w + x
            val j = (y + dy) * w + (x + dx)
            var maxAbs = 0f
            var madSum = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmp[t] = sample(frames[t], j, c, inv) - sample(frames[t], i, c, inv)
                val (m, mad) = medianMad(tmp, n)
                med[c] = m
                maxAbs = max(maxAbs, abs(m))
                madSum += mad
            }
            val consistent = maxAbs > TAU_GRADIENT && maxAbs > K_CONSISTENT * (madSum / 3f)
            for (c in 0 until 3) out[i * 3 + c] = if (consistent) med[c] else 0f
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun sample(frame: ByteArray, p: Int, c: Int, inv: Inversion?): Float {
        val value = v(frame, p * 3 + c)
        if (inv == null) return value
        if (inv.fill[p]) return value
        return ((value - inv.colour[p * 3 + c]) / (1f - min(inv.alpha[p], MAX_INVERT_ALPHA))).coerceIn(0f, 1f)
    }

    /** Per-pixel energy of the consistent gradients (used to validate the inversion). */
    private fun consistentEnergy(frames: List<ByteArray>, w: Int, h: Int, n: Int, inv: Inversion?): FloatArray {
        val px = w * h
        val gx = FloatArray(px * 3)
        val gy = FloatArray(px * 3)
        consistentGradients(frames, w, h, n, 1, 0, gx, inv)
        consistentGradients(frames, w, h, n, 0, 1, gy, inv)
        val e = FloatArray(px)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val ex = gx[i * 3] * gx[i * 3] + gx[i * 3 + 1] * gx[i * 3 + 1] + gx[i * 3 + 2] * gx[i * 3 + 2]
            val ey = gy[i * 3] * gy[i * 3] + gy[i * 3 + 1] * gy[i * 3 + 1] + gy[i * 3 + 2] * gy[i * 3 + 2]
            e[i] += ex + ey
            if (x + 1 < w) e[i + 1] += ex
            if (y + 1 < h) e[i + w] += ey
        }
        return e
    }

    /** Step 4: integrates the median residual gradients of the inverted frames into [colour] /
     * [alpha] (they are tied by `c = a*W`). */
    private fun removeGhost(
        frames: List<ByteArray>, w: Int, h: Int, n: Int,
        colour: FloatArray, alpha: FloatArray, fill: BooleanArray, invert: BooleanArray,
    ) {
        val px = w * h
        val gxr = FloatArray(px * 3)
        val gyr = FloatArray(px * 3)
        val inv = Inversion(colour, alpha, fill)
        medianGradients(frames, w, h, n, 1, 0, gxr, inv, invert)
        medianGradients(frames, w, h, n, 0, 1, gyr, inv, invert)
        val ghosts = Array(3) { c ->
            val div = FloatArray(px)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var d = gxr[i * 3 + c] + gyr[i * 3 + c]
                if (x > 0) d -= gxr[(i - 1) * 3 + c]
                if (y > 0) d -= gyr[(i - w) * 3 + c]
                div[i] = d
            }
            PoissonMasked.solve(div, invert, w, h)
        }
        for (i in 0 until px) if (invert[i]) {
            val a = min(alpha[i], MAX_INVERT_ALPHA)
            // What the ghost says the colour should be, per channel.
            var maxTarget = 0f
            for (c in 0 until 3) maxTarget = max(maxTarget, colour[i * 3 + c] + REFINE_GAIN * ghosts[c][i] * (1f - a))
            var newA = alpha[i]
            // c = a*W never exceeds a (W <= 1): when the correction wants more colour than the
            // opacity allows, the opacity itself was under-estimated. Grow it (bounded) so the
            // colour can follow, instead of saturating at a + 0.02 and leaving a pale logo.
            if (maxTarget > newA + 0.03f && newA < MAX_INVERT_ALPHA - 0.01f) {
                newA = min(MAX_INVERT_ALPHA, newA + REFINE_GAIN * (maxTarget - 0.02f - newA))
            }
            alpha[i] = newA
            for (c in 0 until 3) {
                val target = colour[i * 3 + c] + REFINE_GAIN * ghosts[c][i] * (1f - min(newA, MAX_INVERT_ALPHA))
                colour[i * 3 + c] = target.coerceIn(0f, min(1f, newA + 0.02f))
            }
        }
    }

    /**
     * Median of the per-frame gradients for taps touching [where], kept when statistically
     * significant (larger than twice the standard error of the median, ~1.25 sigma / sqrt(n)),
     * so that the picture's own gradients, which average out, are not mistaken for the ghost.
     */
    private fun medianGradients(
        frames: List<ByteArray>, w: Int, h: Int, n: Int, dx: Int, dy: Int, out: FloatArray,
        inv: Inversion?, where: BooleanArray,
    ) {
        out.fill(0f)
        val tmp = FloatArray(n)
        val med = FloatArray(3)
        val significance = 2f * 1.25f / kotlin.math.sqrt(n.toFloat())
        for (y in 0 until h - dy) for (x in 0 until w - dx) {
            val i = y * w + x
            val j = (y + dy) * w + (x + dx)
            if (!where[i] && !where[j]) continue
            var maxAbs = 0f
            var madSum = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmp[t] = sample(frames[t], j, c, inv) - sample(frames[t], i, c, inv)
                val (m, mad) = medianMad(tmp, n)
                med[c] = m
                maxAbs = max(maxAbs, abs(m))
                madSum += mad
            }
            if (maxAbs > significance * (madSum / 3f)) for (c in 0 until 3) out[i * 3 + c] = med[c]
        }
    }

    // ------------------------------------------------------------------------------------------
    // Soft edge band (step 3b)
    // ------------------------------------------------------------------------------------------

    /**
     * Step 3b: re-estimates the opacity of the faint parts of the logo directly from the data.
     * The relief of step 2 is a Poisson integral of thresholded gradients: on anti-aliased or
     * compression-softened glyph edges its taps are cut (under-estimated ramp) while between
     * close glyphs it bleeds over clean pixels (over-estimated) — either way a pale, blurred
     * copy of the text survives the inversion. Here the faint pixels — those of the mask whose
     * relief is weak, plus a thin band around it — get their opacity from the projection of
     * `(temporal median - interpolated background)` on the component colour, which is unbiased
     * for thin features; pixels that turn out to hold no logo at all are zeroed out instead of
     * being over-inverted.
     */
    private fun extendSoftEdges(
        w: Int, h: Int, median: FloatArray,
        mask: BooleanArray, labels: IntArray, reliefMax: FloatArray,
        alpha: FloatArray, colour: FloatArray, fill: BooleanArray,
        compColours: Array<FloatArray>, compAlphas: FloatArray, componentCount: Int,
    ) {
        if (componentCount == 0) return
        val px = w * h

        // Outside band: pixels within EDGE_BAND of the mask (BFS from the mask, each carrying
        // the label of the mask pixel it comes from).
        val bandLabel = IntArray(px) // 0 = not reached, else the label of the nearest mask pixel
        val bandDist = IntArray(px)  // 0 = not reached, else the BFS distance to the mask
        var frontier = IntArray(px)
        var frontierCount = 0
        for (i in 0 until px) if (mask[i]) frontier[frontierCount++] = i
        for (step in 1..EDGE_BAND) {
            val next = ArrayList<Int>()
            for (k in 0 until frontierCount) {
                val i = frontier[k]
                val x = i % w
                val y = i / w
                val l = if (mask[i]) labels[i] else bandLabel[i]
                if (l <= 0) continue
                if (x > 0 && !mask[i - 1] && bandLabel[i - 1] == 0) { bandLabel[i - 1] = l; bandDist[i - 1] = step; next.add(i - 1) }
                if (x < w - 1 && !mask[i + 1] && bandLabel[i + 1] == 0) { bandLabel[i + 1] = l; bandDist[i + 1] = step; next.add(i + 1) }
                if (y > 0 && !mask[i - w] && bandLabel[i - w] == 0) { bandLabel[i - w] = l; bandDist[i - w] = step; next.add(i - w) }
                if (y < h - 1 && !mask[i + w] && bandLabel[i + w] == 0) { bandLabel[i + w] = l; bandDist[i + w] = step; next.add(i + w) }
            }
            if (next.isEmpty()) break
            frontier = next.toIntArray()
            frontierCount = next.size
        }

        // Targets: weak-relief pixels of the mask (the truncated / bleeding estimate of the
        // main pass) plus the outside band.
        val targets = BooleanArray(px) { (mask[it] && reliefMax[it] < FRINGE_KEEP) || bandDist[it] in 1..EDGE_BAND }
        if (!targets.any { it }) return

        // Interpolated background under the mask AND the band (the observed median there still
        // contains the faint logo).
        val fillMask = BooleanArray(px) { mask[it] || bandDist[it] in 1..EDGE_BAND }
        val bg = HarmonicFill.fill(median, 3, fillMask, w, h)

        // Opacity by projection of the observed median on the logo colour direction.
        val cand = FloatArray(px) { -1f }
        for (i in 0 until px) if (targets[i]) {
            val comp = if (mask[i]) labels[i] else bandLabel[i]
            if (comp <= 0) continue
            val cap = min(MAX_INVERT_ALPHA, compAlphas[comp] + 0.05f)
            var num = 0f
            var den = 0f
            for (c in 0 until 3) {
                val d = compColours[comp][c] - bg[i * 3 + c]
                num += (median[i * 3 + c] - bg[i * 3 + c]) * d
                den += d * d
            }
            val aData = if (den > 0.01f) (num / den).coerceIn(0f, cap) else -1f
            cand[i] = if (aData >= EDGE_MIN_ALPHA) aData else -1f
        }
        // Isolated specks are dropped (the projection itself already rejects noise: it is not
        // aligned with the logo colour direction, so it does not reach [EDGE_MIN_ALPHA]).
        val accepted = BooleanArray(px) { targets[it] && cand[it] >= EDGE_MIN_ALPHA }
        val accLabels = Components.label(accepted, w, h)
        Components.removeSmall(accepted, accLabels, 3)

        for (i in 0 until px) if (targets[i]) {
            val comp = if (mask[i]) labels[i] else bandLabel[i]
            if (comp <= 0) continue
            if (accepted[i]) {
                val a = cand[i]
                if (mask[i]) {
                    // Re-estimated faint pixel: replace the truncated estimate. A fill pixel
                    // that turns out to be semi-transparent joins the inversion set instead;
                    // one that stays near the opacity ceiling keeps being filled (inverting
                    // there would only amplify noise).
                    if (fill[i] && a >= MAX_INVERT_ALPHA - 0.05f) continue
                    fill[i] = false
                } else {
                    mask[i] = true
                    labels[i] = bandLabel[i]
                }
                alpha[i] = a
                for (c in 0 until 3) colour[i * 3 + c] = (a * compColours[comp][c]).coerceIn(0f, min(1f, a + 0.02f))
            } else if (mask[i] && !fill[i] && reliefMax[i] < FRINGE_KEEP) {
                // No faint logo here: the weak relief was Poisson bleed between glyph parts.
                // Zero it out instead of over-inverting clean pixels.
                alpha[i] = 0f
                colour[i * 3] = 0f
                colour[i * 3 + 1] = 0f
                colour[i * 3 + 2] = 0f
            }
        }
    }

    private fun regressionAlpha(median: FloatArray, background: FloatArray, core: List<Int>): Float {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        var cnt = 0
        for (p in core) for (c in 0 until 3) {
            val x = background[p * 3 + c].toDouble()
            val y = median[p * 3 + c].toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y; cnt++
        }
        if (cnt == 0) return 0.5f
        val varX = sxx / cnt - (sx / cnt) * (sx / cnt)
        if (varX < 0.003) return 0.5f
        val slope = (sxy / cnt - (sx / cnt) * (sy / cnt)) / varX
        return (1.0 - slope.coerceIn(0.0, 1.0)).toFloat()
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int) {
        val src = mask.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (src[y * w + x]) continue
            var any = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && src[yy * w + xx]) any = true
            }
            if (any) mask[y * w + x] = true
        }
    }

    /** 3x3 median of the valid (>= 0) values; -1 where fewer than 3 valid neighbours exist. */
    private fun medianFilterMasked(values: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h) { -1f }
        val buf = FloatArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            if (values[y * w + x] < 0f) continue
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    val s = values[yy * w + xx]
                    if (s >= 0f) buf[k++] = s
                }
            }
            if (k >= 3) out[y * w + x] = medianOf(buf, k)
        }
        return out
    }

    /** Distances from fill pixels to the nearest non-fill pixel: 4 bytes per pixel (L, R, T, B). */
    fun fillDistances(fill: BooleanArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            var last = -1
            for (x in 0 until w) {
                val i = y * w + x
                if (!fill[i]) last = x else out[i * 4] = clampByte(if (last >= 0) x - last else 255)
            }
            last = -1
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (!fill[i]) last = x else out[i * 4 + 1] = clampByte(if (last >= 0) last - x else 255)
            }
        }
        for (x in 0 until w) {
            var last = -1
            for (y in 0 until h) {
                val i = y * w + x
                if (!fill[i]) last = y else out[i * 4 + 2] = clampByte(if (last >= 0) y - last else 255)
            }
            last = -1
            for (y in h - 1 downTo 0) {
                val i = y * w + x
                if (!fill[i]) last = y else out[i * 4 + 3] = clampByte(if (last >= 0) last - y else 255)
            }
        }
        return out
    }

    private fun clampByte(v: Int): Byte = min(v, 255).toByte()

    /** Returns (median, MAD * 1.4826). Destroys the order of [values]. */
    private fun medianMad(values: FloatArray, n: Int): Pair<Float, Float> {
        val m = medianOf(values, n)
        for (t in 0 until n) values[t] = abs(values[t] - m)
        return m to medianOf(values, n) * 1.4826f
    }

    /** Median of the first [n] values (sorts a copy). */
    fun medianOf(values: FloatArray, n: Int): Float {
        if (n == 0) return 0f
        val a = values.copyOf(n)
        a.sort()
        return if (n % 2 == 1) a[n / 2] else 0.5f * (a[n / 2 - 1] + a[n / 2])
    }

    private fun percentile(values: FloatArray, q: Float): Float {
        if (values.isEmpty()) return 0f
        val a = values.copyOf()
        a.sort()
        return a[((a.size - 1) * q).toInt().coerceIn(0, a.size - 1)]
    }
}

/** Exact solver of `lap(u) = f` with u = 0 on the border, via a discrete sine transform (DST-I). */
object PoissonSolver {

    fun solve(f: FloatArray, w: Int, h: Int): FloatArray {
        val rowBasis = basis(w)
        val colBasis = basis(h)
        val tmp = DoubleArray(w * h)
        val coef = DoubleArray(w * h)
        // Forward DST-I along rows then columns.
        for (y in 0 until h) {
            for (k in 0 until w) {
                var s = 0.0
                for (x in 0 until w) s += f[y * w + x] * rowBasis[k * w + x]
                tmp[y * w + k] = s
            }
        }
        for (k in 0 until w) {
            for (l in 0 until h) {
                var s = 0.0
                for (y in 0 until h) s += tmp[y * w + k] * colBasis[l * h + y]
                coef[l * w + k] = s
            }
        }
        // Divide by the eigenvalues of the 5-point Laplacian.
        for (l in 0 until h) for (k in 0 until w) {
            val lambda = 2.0 * cos(Math.PI * (k + 1) / (w + 1)) + 2.0 * cos(Math.PI * (l + 1) / (h + 1)) - 4.0
            coef[l * w + k] /= lambda
        }
        // Inverse (DST-I is its own inverse up to a factor 2/(n+1) per axis).
        val scale = (2.0 / (w + 1)) * (2.0 / (h + 1))
        for (l in 0 until h) {
            for (x in 0 until w) {
                var s = 0.0
                for (k in 0 until w) s += coef[l * w + k] * rowBasis[k * w + x]
                tmp[l * w + x] = s
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var s = 0.0
                for (l in 0 until h) s += tmp[l * w + x] * colBasis[l * h + y]
                out[y * w + x] = (s * scale).toFloat()
            }
        }
        return out
    }

    /** basis[k * n + x] = sin(pi (k+1)(x+1) / (n+1)) */
    private fun basis(n: Int): DoubleArray {
        val b = DoubleArray(n * n)
        for (k in 0 until n) for (x in 0 until n) b[k * n + x] = sin(Math.PI * (k + 1) * (x + 1) / (n + 1))
        return b
    }
}

/** Solves `lap(u) = f` on the pixels of a mask, with u = 0 everywhere else (SOR, per pixel). */
object PoissonMasked {

    fun solve(f: FloatArray, mask: BooleanArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val idx = IntArray(w * h)
        var count = 0
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            idx[count++] = y * w + x
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (count == 0) return out
        val extent = max(maxX - minX + 1, maxY - minY + 1)
        val omega = (2.0 / (1.0 + sin(Math.PI / (extent + 1)))).toFloat()
        val iterations = min(extent * 3 + 60, 900)
        for (it in 0 until iterations) {
            val forward = it % 2 == 0
            var maxDelta = 0f
            for (kk in 0 until count) {
                val k = if (forward) kk else count - 1 - kk
                val i = idx[k]
                val x = i % w; val y = i / w
                var nb = 0f
                if (x > 0) nb += out[i - 1]
                if (x < w - 1) nb += out[i + 1]
                if (y > 0) nb += out[i - w]
                if (y < h - 1) nb += out[i + w]
                val old = out[i]
                val new = old + omega * (0.25f * (nb - f[i]) - old)
                out[i] = new
                maxDelta = max(maxDelta, abs(new - old))
            }
            if (maxDelta < 1e-5f) break
        }
        return out
    }
}

/** Harmonic (Laplace) interpolation of the values under a mask from the values around it. */
object HarmonicFill {

    /** [data] has [channels] interleaved values per pixel. Returns a new array. */
    fun fill(data: FloatArray, channels: Int, mask: BooleanArray, w: Int, h: Int): FloatArray {
        val out = data.copyOf()
        val idx = IntArray(w * h)
        var count = 0
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            idx[count++] = y * w + x
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (count == 0) return out
        // Initial guess: mean of the known values.
        val mean = FloatArray(channels)
        var known = 0
        for (i in 0 until w * h) if (!mask[i]) { for (c in 0 until channels) mean[c] += data[i * channels + c]; known++ }
        if (known == 0) return out
        for (c in 0 until channels) mean[c] /= known
        for (k in 0 until count) for (c in 0 until channels) out[idx[k] * channels + c] = mean[c]
        // Gauss-Seidel with over-relaxation, alternating sweep direction.
        val extent = max(maxX - minX + 1, maxY - minY + 1)
        val omega = (2.0 / (1.0 + sin(Math.PI / (extent + 1)))).toFloat()
        val iterations = min(extent * 3 + 60, 900)
        for (it in 0 until iterations) {
            val forward = it % 2 == 0
            var maxDelta = 0f
            for (kk in 0 until count) {
                val k = if (forward) kk else count - 1 - kk
                val i = idx[k]
                val x = i % w; val y = i / w
                val l = if (x > 0) i - 1 else i
                val r = if (x < w - 1) i + 1 else i
                val u = if (y > 0) i - w else i
                val d = if (y < h - 1) i + w else i
                for (c in 0 until channels) {
                    val nb = 0.25f * (out[l * channels + c] + out[r * channels + c] + out[u * channels + c] + out[d * channels + c])
                    val old = out[i * channels + c]
                    val new = old + omega * (nb - old)
                    out[i * channels + c] = new
                    maxDelta = max(maxDelta, abs(new - old))
                }
            }
            if (maxDelta < 1e-4f) break
        }
        return out
    }
}

/** 8-connected component labelling. */
object Components {

    /** Returns labels 1..n (0 = background). */
    fun label(mask: BooleanArray, w: Int, h: Int): IntArray {
        val labels = IntArray(w * h)
        val stack = IntArray(w * h)
        var next = 0
        for (start in 0 until w * h) {
            if (!mask[start] || labels[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start
            labels[start] = next
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    if (mask[j] && labels[j] == 0) { labels[j] = next; stack[sp++] = j }
                }
            }
        }
        return labels
    }

    fun removeSmall(mask: BooleanArray, labels: IntArray, minSize: Int) {
        val n = labels.max()
        if (n == 0) return
        val sizes = IntArray(n + 1)
        for (l in labels) sizes[l]++
        for (i in labels.indices) if (labels[i] != 0 && sizes[labels[i]] < minSize) { mask[i] = false }
    }
}
