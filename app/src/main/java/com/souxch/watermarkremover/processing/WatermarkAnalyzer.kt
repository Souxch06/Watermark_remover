package com.souxch.watermarkremover.processing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Recovers the watermark layer that a video app composited on top of the picture, so that the
 * picture BEHIND the watermark can be restored (instead of being painted over).
 *
 * Model: every frame is `J = a * W + (1 - a) * I` where `I` is the true picture, `W` the
 * watermark colour and `a` its opacity (both constant over time because the logo does not move).
 * Because the picture changes from frame to frame while the logo does not, the gradients of the
 * logo are the only ones that are identical in every frame. We therefore:
 *  1. take, at every pixel, the median over N frames of the horizontal / vertical gradients and
 *     keep only the ones that are consistent (much larger than their spread);
 *  2. integrate them (Poisson equation, solved exactly with a sine transform) -> `V = a*(W - I~)`,
 *     the watermark relief, which is zero wherever no logo is present -> a pixel-accurate mask;
 *  3. estimate the opacity `a` of each connected part of the logo from how much the pixel values
 *     vary over time compared with their neighbourhood (a semi-transparent logo damps the
 *     variations by `1 - a`);
 *  4. build per-pixel `c = a*W` and `a`, and check on the sample frames that the inversion
 *     `I = (J - c) / (1 - a)` really removes the logo; parts that are opaque or that fail the
 *     check are flagged for spatial filling instead.
 *
 * Everything is pure Kotlin (no Android types) so it runs on the JVM tests. All arrays are
 * row-major, `[y * width + x]`, RGB values in 0..1.
 */
object WatermarkAnalyzer {

    /** Max opacity we still invert; above that the picture is (almost) gone -> fill. */
    const val MAX_INVERT_ALPHA = 0.85f
    /** A watermark layer is only trusted if this many frames were analysed. */
    const val MIN_FRAMES = 6
    /** Frames of the analysed region: each `width*height*3` RGB bytes (row-major). */
    class Frames(val width: Int, val height: Int, val frames: List<ByteArray>)

    /** Largest region analysed (pixels); bigger zones fall back to the spatial reconstruction. */
    const val MAX_REGION_PIXELS = 400_000

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
        val motion: Float,
        val maskPixels: Int,
        val invertedPixels: Int,
        val filledPixels: Int,
        val components: Int,
        val reason: String,
    )

    private const val TAU_GRADIENT = 0.02f
    private const val K_CONSISTENT = 2.0f
    private const val TAU_RELIEF = 0.05f
    private const val MIN_COMPONENT = 6
    private const val MIN_MOTION = 0.008f
    private const val RING = 4

    fun analyze(input: Frames): Layer? {
        val w = input.width
        val h = input.height
        val n = input.frames.size
        if (n < MIN_FRAMES || w < 2 * RING + 4 || h < 2 * RING + 4 || w * h > MAX_REGION_PIXELS) return null
        val px = w * h
        // Unpack to floats once (n * px * 3 floats; bounded by MAX_REGION_PIXELS).
        val frames: List<FloatArray> = input.frames.map { bytes ->
            FloatArray(px * 3) { j -> (bytes[j].toInt() and 0xFF) * (1f / 255f) }
        }

        // --- temporal median / spread of the pixel values ---------------------------------
        val median = FloatArray(px * 3)
        val spread = FloatArray(px)
        val tmp = FloatArray(n)
        for (i in 0 until px) {
            var s = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmp[t] = frames[t][i * 3 + c]
                val (m, mad) = medianMad(tmp, n)
                median[i * 3 + c] = m
                s += mad
            }
            spread[i] = s / 3f
        }
        // Motion measured on the ring around the zone (the logo never lives there).
        val ringValues = ArrayList<Float>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < RING || x >= w - RING || y < RING || y >= h - RING) ringValues.add(spread[y * w + x])
        }
        val motion = medianOf(ringValues.toFloatArray(), ringValues.size)
        if (motion < MIN_MOTION) {
            return Layer(w, h, FloatArray(px * 3), FloatArray(px), BooleanArray(px), ByteArray(px * 4),
                Stats(n, motion, 0, 0, 0, 0, "static"))
        }

        // --- consistent median gradients -> divergence --------------------------------------
        val gx = FloatArray(px * 3)   // gradient between (x, y) and (x + 1, y)
        val gy = FloatArray(px * 3)   // gradient between (x, y) and (x, y + 1)
        consistentGradients(frames, w, h, n, dx = 1, dy = 0, out = gx)
        consistentGradients(frames, w, h, n, dx = 0, dy = 1, out = gy)

        // --- Poisson integration per channel -> relief V --------------------------------------
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
            val v = PoissonSolver.solve(f, w, h)
            for (i in 0 until px) relief[i * 3 + c] = v[i]
        }
        val reliefMax = FloatArray(px) { i -> max(abs(relief[i * 3]), max(abs(relief[i * 3 + 1]), abs(relief[i * 3 + 2]))) }

        // --- mask -------------------------------------------------------------------------
        val mask = BooleanArray(px) { reliefMax[it] > TAU_RELIEF }
        var labels = Components.label(mask, w, h)
        Components.removeSmall(mask, labels, MIN_COMPONENT)
        dilate(mask, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < RING || x >= w - RING || y < RING || y >= h - RING) mask[y * w + x] = false
        }
        var maskCount = mask.count { it }
        if (maskCount == 0) {
            return Layer(w, h, FloatArray(px * 3), FloatArray(px), BooleanArray(px), ByteArray(px * 4),
                Stats(n, motion, 0, 0, 0, 0, "no watermark"))
        }
        labels = Components.label(mask, w, h)
        val componentCount = labels.max()

        // --- background statistics under the logo (harmonic interpolation) --------------------
        val background = HarmonicFill.fill(median, 3, mask, w, h)
        val spreadRef = HarmonicFill.fill(spread, 1, mask, w, h)

        // --- per component opacity + colour ---------------------------------------------------
        val alpha = FloatArray(px)
        val colour = FloatArray(px * 3)
        val fill = BooleanArray(px)
        val compAlpha = FloatArray(componentCount + 1)
        val compColour = FloatArray((componentCount + 1) * 3)
        for (comp in 1..componentCount) {
            val members = ArrayList<Int>()
            for (i in 0 until px) if (labels[i] == comp) members.add(i)
            // Core = the strongest part of the relief (avoids anti-aliased edges).
            val strengths = FloatArray(members.size) { reliefMax[members[it]] }
            val p90 = percentile(strengths, 0.9f)
            val core = members.filter { reliefMax[it] >= 0.5f * p90 }.ifEmpty { members }
            val sRef = medianOf(FloatArray(core.size) { spreadRef[core[it]] }, core.size)
            var a = if (sRef > 0.02f) {
                // Variance cue: the logo damps the temporal variations by (1 - a).
                medianOf(FloatArray(core.size) { i -> val p = core[i]; (1f - spread[p] / max(spreadRef[p], 1e-4f)).coerceIn(0f, 1f) }, core.size)
            } else {
                // Regression cue: J~ = a*W + (1-a)*I~ -> slope of J~ vs I~ is (1 - a).
                regressionAlpha(median, background, core)
            }
            a = a.coerceIn(0.05f, 1f)
            // Logo colour: W = I~ + V / a (median over the core, per channel).
            for (c in 0 until 3) {
                val vals = FloatArray(core.size) { i -> val p = core[i]; (background[p * 3 + c] + relief[p * 3 + c] / a) }
                compColour[comp * 3 + c] = medianOf(vals, vals.size).coerceIn(0f, 1f)
            }
            compAlpha[comp] = a
            for (p in members) {
                if (a > MAX_INVERT_ALPHA) {
                    fill[p] = true
                } else {
                    // Per-pixel opacity from the relief: V = a_px * (W - I~) (soft edges of the logo).
                    var num = 0f
                    var den = 0f
                    for (c in 0 until 3) {
                        val d = compColour[comp * 3 + c] - background[p * 3 + c]
                        num += relief[p * 3 + c] * d
                        den += d * d
                    }
                    val ap = if (den > 0.01f) (num / den).coerceIn(0f, min(1f, a + 0.1f)) else a
                    alpha[p] = ap
                    for (c in 0 until 3) colour[p * 3 + c] = (relief[p * 3 + c] + ap * background[p * 3 + c]).coerceIn(0f, 1f)
                }
            }
        }

        // --- validation: does the inversion really remove the consistent gradients? ------------
        val energyBefore = consistentEnergy(frames, w, h, n)
        val inverted = frames.map { fr ->
            FloatArray(px * 3) { j ->
                val p = j / 3
                if (alpha[p] > 0f && !fill[p]) ((fr[j] - colour[j]) / (1f - min(alpha[p], MAX_INVERT_ALPHA))).coerceIn(0f, 1f) else fr[j]
            }
        }
        val energyAfter = consistentEnergy(inverted, w, h, n)
        for (comp in 1..componentCount) {
            var e0 = 0f
            var e1 = 0f
            for (i in 0 until px) if (labels[i] == comp && !fill[i]) { e0 += energyBefore[i]; e1 += energyAfter[i] }
            if (e0 > 0f && e1 > 0.6f * e0) {
                for (i in 0 until px) if (labels[i] == comp) { fill[i] = true; alpha[i] = 0f }
            }
        }
        for (i in 0 until px) if (fill[i]) { alpha[i] = 0f; colour[i * 3] = 0f; colour[i * 3 + 1] = 0f; colour[i * 3 + 2] = 0f }

        val distances = fillDistances(fill, w, h)
        maskCount = mask.count { it }
        val filled = fill.count { it }
        val invertedCount = alpha.count { it > 0f }
        return Layer(w, h, colour, alpha, fill, distances, Stats(n, motion, maskCount, invertedCount, filled, componentCount, "ok"))
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Median of the per-frame gradients, kept only when consistent across frames. */
    private fun consistentGradients(frames: List<FloatArray>, w: Int, h: Int, n: Int, dx: Int, dy: Int, out: FloatArray) {
        val tmp = FloatArray(n)
        val med = FloatArray(3)
        for (y in 0 until h - dy) for (x in 0 until w - dx) {
            val i = y * w + x
            val j = (y + dy) * w + (x + dx)
            var maxAbs = 0f
            var madSum = 0f
            for (c in 0 until 3) {
                for (t in 0 until n) tmp[t] = frames[t][j * 3 + c] - frames[t][i * 3 + c]
                val (m, mad) = medianMad(tmp, n)
                med[c] = m
                maxAbs = max(maxAbs, abs(m))
                madSum += mad
            }
            val consistent = maxAbs > TAU_GRADIENT && maxAbs > K_CONSISTENT * (madSum / 3f)
            for (c in 0 until 3) out[i * 3 + c] = if (consistent) med[c] else 0f
        }
    }

    /** Per-pixel energy of the consistent gradients (used to validate the inversion). */
    private fun consistentEnergy(frames: List<FloatArray>, w: Int, h: Int, n: Int): FloatArray {
        val px = w * h
        val gx = FloatArray(px * 3)
        val gy = FloatArray(px * 3)
        consistentGradients(frames, w, h, n, 1, 0, gx)
        consistentGradients(frames, w, h, n, 0, 1, gy)
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
        // Forward DST-I along rows then columns.
        val rowBasis = basis(w)
        val colBasis = basis(h)
        val tmp = DoubleArray(w * h)
        val coef = DoubleArray(w * h)
        // rows
        for (y in 0 until h) {
            for (k in 0 until w) {
                var s = 0.0
                for (x in 0 until w) s += f[y * w + x] * rowBasis[k * w + x]
                tmp[y * w + k] = s
            }
        }
        // columns
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
        for (k in 0 until n) for (x in 0 until n) b[k * n + x] = kotlin.math.sin(Math.PI * (k + 1) * (x + 1) / (n + 1))
        return b
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
        val omega = (2.0 / (1.0 + kotlin.math.sin(Math.PI / (extent + 1)))).toFloat()
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
