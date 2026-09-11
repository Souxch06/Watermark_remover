package com.souxch.watermarkremover.processing

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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
    private var lumBase = lumPrev // the frame the motion search matches against
    // ---- per-frame watermark alignment: real exports (clip compilations, auto-reframe)
    // re-render the watermark per clip, so it does NOT sit at exactly the same pixels all
    // along the video. The restorer works in an "aligned" domain where the watermark is
    // always at its analysed position: each frame is shifted by minus the estimated offset
    // on the way in, and the result is shifted back on the way out. The motion / history
    // machinery then stays self-consistent in that domain. ----
    private var wmOffX = 0
    private var wmOffY = 0
    private val warpBuf = FloatArray(px * 3)
    /**
     * True when the layer can localise its watermark: enough strong inversion taps, and an
     * inversion-dominated mask (a fill-dominated layer has no reliable watermark taps - its
     * few "inverted" pixels are usually background false positives, and following them would
     * align on the moving background instead of the logo).
     */
    private val wmTrackable: Boolean by lazy {
        var inverted = 0
        var masked = 0
        for (i in 0 until px) {
            if (layer.alpha[i] > 0f) inverted++
            if (mask[i]) masked++
        }
        if (inverted < masked * WM_OFF_INV_FRACTION) return@lazy false
        var strong = 0
        for (k in tapDa.indices) if (abs(tapDa[k]) >= WM_OFF_TAP_ALPHA) strong++
        strong >= WM_OFF_MIN_TAPS
    }
    /**
     * Opaque (fill-dominated) layers are localised differently: by matching the analysis-time
     * appearance template against the frame (see [OpaqueTemplate]). The template must be
     * distinctive: enough support pixels, and a support that spans the logo's shape. The
     * eroded core of a logo that drifts continuously is a small blob (say the solid mark of
     * the logo, its thin text strokes eroded away) that other parts of the logo - or bright
     * patches of background - match just as well: tracking with it locks onto them and jumps
     * around. Such a layer keeps a mask that already covers the drift range instead.
     */
    private val wmTemplate: OpaqueTemplate.Template? by lazy {
        val t = layer.template ?: return@lazy null
        if (t.idx.size < WM_TM_MIN_TRACK_PIXELS) return@lazy null
        var sMinX = width; var sMaxX = 0; var sMinY = height; var sMaxY = 0
        for (k in t.idx) {
            val x = k % width; val y = k / width
            if (x < sMinX) sMinX = x; if (x > sMaxX) sMaxX = x
            if (y < sMinY) sMinY = y; if (y > sMaxY) sMaxY = y
        }
        var mMinX = width; var mMaxX = 0; var mMinY = height; var mMaxY = 0
        for (i in 0 until px) if (mask[i]) {
            val x = i % width; val y = i / width
            if (x < mMinX) mMinX = x; if (x > mMaxX) mMaxX = x
            if (y < mMinY) mMinY = y; if (y > mMaxY) mMaxY = y
        }
        val spanX = (sMaxX - sMinX + 1).toFloat() / (mMaxX - mMinX + 1).coerceAtLeast(1)
        val spanY = (sMaxY - sMinY + 1).toFloat() / (mMaxY - mMinY + 1).coerceAtLeast(1)
        if (spanX < WM_TM_MIN_SPAN || spanY < WM_TM_MIN_SPAN) return@lazy null
        t
    }
    // Luminance of the frames t-2 and t-3: with a slow background, the sub-pixel estimate over
    // one frame is biased by the resampling; over three frames the same bias is divided by three.
    private val lumPrev2 = FloatArray(px)
    private val lumPrev3 = FloatArray(px)
    private var prevCount = 0
    private val prevIn = FloatArray(px * 3)
    private val prevOut = FloatArray(px * 3)
    private val prevWeight = FloatArray(px)
    private val gain = FloatArray(3) { 1f }
    private val offset = FloatArray(3)
    private var hasPrevious = false
    private var wasPresent = true

    // ---- motion fill: ring buffer of the last frames (the "motion fill" of pro tools) ----
    // The background moves while the logo does not, so what is hidden now was visible a few
    // frames ago (or in a frame without the logo). Instead of chaining one-frame hops, each
    // masked pixel directly looks up its background in the stored frames at the position given
    // by the accumulated motion, and adopts it when the source there is trusted and clean.
    private val historyDepth: Int = when {
        px > 100_000 -> 6
        px > 40_000 -> 10
        else -> 14
    }
    private val histOut = Array(historyDepth) { ByteArray(px * 3) }
    private val histWeight = Array(historyDepth) { ByteArray(px) } // log-quantized confidence
    private val histCumX = FloatArray(historyDepth)
    private val histCumY = FloatArray(historyDepth)
    // Warp offset each stored frame was restored under (the aligned domain changes when the
    // tracked watermark jumps between clips).
    private val histWmOffX = IntArray(historyDepth)
    private val histWmOffY = IntArray(historyDepth)
    private val histMean = Array(historyDepth) { FloatArray(3) } // clean mean, per channel
    private var histStart = 0
    private var histCount = 0
    private val histFrame = IntArray(historyDepth) // frame number of each stored frame
    private var frameNo = 0
    private var lastPushFrame = -1
    private var lastPushCumX = 0f
    private var lastPushCumY = 0f
    private var cumX = 0f
    private var cumY = 0f
    private val cleanMean = FloatArray(3) { 0.5f }

    // ---- harmonic fill state (warm-started across frames) ----
    private val fillIdx: IntArray = run {
        var n = 0
        for (i in 0 until px) if (mask[i] && layer.fill[i]) n++
        val idx = IntArray(n)
        var k = 0
        for (i in 0 until px) if (mask[i] && layer.fill[i]) idx[k++] = i
        idx
    }
    private val fillMask = BooleanArray(px) { mask[it] && layer.fill[it] }
    private var fillWarm = false
    private var fillColdDone = false
    private val fillState = FloatArray(px * 3)

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
    /** Offset of the watermark in the last processed frame, relative to its analysed position. */
    val watermarkOffsetX: Int get() = wmOffX
    val watermarkOffsetY: Int get() = wmOffY

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

    /** Drops the motion-fill history (the stored frames belong to another clip / picture). */
    private fun clearHistory() {
        histStart = 0
        histCount = 0
        lastPushFrame = -1
        lastPushCumX = 0f
        lastPushCumY = 0f
    }

    /** Forgets the previous frames (seek, new export); the learned ghost is kept. */
    fun reset() {
        hasPrevious = false
        prevCount = 0
        wmOffX = 0
        wmOffY = 0
        wasPresent = true
        presence = 1f
        motionFound = false
        histStart = 0
        histCount = 0
        frameNo = 0
        lastPushFrame = -1
        lastPushCumX = 0f
        lastPushCumY = 0f
        cumX = 0f
        cumY = 0f
        fillWarm = false
        fillColdDone = false
    }

    /**
     * Restores one frame. [rgba]: region pixels, row 0 = bottom row when [flipY] (GL read-back).
     * [output] receives the restored region in display order (row 0 = top); it may be the same
     * array as [rgba].
     */
    fun process(rgba: ByteArray, flipY: Boolean, output: ByteArray) {
        unpack(rgba, flipY)
        val prevWmOffX = wmOffX
        val prevWmOffY = wmOffY
        estimateWatermarkOffset()
        // A jump of the tracked watermark = the video cut to another clip: the stored frames
        // belong to another picture, they must not feed the temporal fill anymore.
        if (abs(wmOffX - prevWmOffX) >= 2 || abs(wmOffY - prevWmOffY) >= 2) clearHistory()
        // The frame watermark sits at layer position + (wmOffX, wmOffY): sample the frame at
        // p + off so the watermark lands exactly on its analysed position.
        if (wmOffX != 0 || wmOffY != 0) shiftInPlace(wmOffX, wmOffY)
        computeCleanMean()
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
        if (motionFound) {
            // The motion is measured between the two frames' aligned domains, so it absorbs
            // the warp jump when the tracked watermark moved; the accumulated motion must be
            // the background's own motion, or the history lookups drift with the warp.
            cumX += motionX + (wmOffX - prevWmOffX)
            cumY += motionY + (wmOffY - prevWmOffY)
        } else if (hasPrevious) {
            // No motion match = the picture itself changed (a cut the watermark tracking did
            // not see, a flash): the stored frames are from another world.
            clearHistory()
        }

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
            harmonicFill()
            if (motionFound) propagate()
            motionFill()
            textureFill()
        }

        // ---- keep state for the next frame ----
        System.arraycopy(cur, 0, prevIn, 0, px * 3)
        System.arraycopy(out, 0, prevOut, 0, px * 3)
        System.arraycopy(weight, 0, prevWeight, 0, px)
        hasPrevious = true
        pushHistory()
        pack(output, flipY, wmOffX, wmOffY)
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

    private fun pack(rgba: ByteArray, @Suppress("UNUSED_PARAMETER") flipY: Boolean, offX: Int = 0, offY: Int = 0) {
        // Un-warp: the restored content of the display pixel (x, y) is the aligned-domain
        // pixel (x - offX, y - offY) (edge-clamped).
        val d = -offY * width - offX
        for (y in 0 until height) {
            var dst = y * width * 4
            for (x in 0 until width) {
                var src = (y * width + x + d) * 3
                if (src < 0) src = 0
                if (src > (px - 1) * 3) src = (px - 1) * 3
                rgba[dst] = toByte(out[src])
                rgba[dst + 1] = toByte(out[src + 1])
                rgba[dst + 2] = toByte(out[src + 2])
                rgba[dst + 3] = -1
                dst += 4
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

    /**
     * Regression score of the layer's watermark pattern against the current frame when the
     * frame is aligned by (dx, dy): ~1 when the watermark sits there, ~0 elsewhere. The
     * presence taps (edge pixel pairs + a clean reference) shift rigidly, so the score stays
     * valid whatever the background.
     */
    private fun presenceScoreAt(dx: Int, dy: Int): Float {
        val d = dy * width + dx
        var num = 0.0
        var den = 0.0
        for (k in tapP.indices) {
            val p = tapP[k] + d
            val q = tapQ[k] + d
            val n = tapN[k] + d
            if (p < 0 || q < 0 || n < 0 || p >= px || q >= px || n >= px) continue
            val da = tapDa[k]
            if (da == 0f) continue
            val p3 = p * 3; val q3 = q * 3; val n3 = n * 3
            for (c in 0 until 3) {
                val e = tapDc[3 * k + c] - cur[n3 + c] * da
                num += (cur[q3 + c] - cur[p3 + c]) * e
                den += e * e
            }
        }
        return if (den > 1e-3) (num / den).toFloat() else -1f
    }

    /**
     * Finds where the watermark sits in the current frame (it may have been re-rendered a few
     * pixels away, or jitter slightly): the offset whose alignment scores best wins, with a
     * margin over the current offset so the estimate does not flicker. Semi-transparent layers
     * are localised by their gradient taps; opaque ones by matching their appearance template.
     */
    private fun estimateWatermarkOffset() {
        if (wmTrackable) {
            var bestScore = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (dy in -WM_OFF_SEARCH..WM_OFF_SEARCH) for (dx in -WM_OFF_SEARCH..WM_OFF_SEARCH) {
                val s = presenceScoreAt(dx, dy)
                if (s > bestScore) { bestScore = s; bestX = dx; bestY = dy }
            }
            if (bestScore < WM_OFF_MIN_SCORE) return // nothing convincing: keep the last offset
            if (wmOffX == bestX && wmOffY == bestY) return
            val current = presenceScoreAt(wmOffX, wmOffY)
            // Hysteresis: only move when the new alignment is clearly better - or when the current
            // one has collapsed (the video cut to another clip and the watermark jumped).
            if (bestScore > current + WM_OFF_MARGIN || current < WM_OFF_MIN_SCORE) {
                wmOffX = bestX
                wmOffY = bestY
            }
            return
        }
        val t = wmTemplate ?: return
        var bestScore = -Float.MAX_VALUE
        var bestX = 0
        var bestY = 0
        for (dy in -WM_TM_SEARCH..WM_TM_SEARCH) for (dx in -WM_TM_SEARCH..WM_TM_SEARCH) {
            val s = OpaqueTemplate.match(t, cur, dx, dy)
            if (s > bestScore) { bestScore = s; bestX = dx; bestY = dy }
        }
        // An argmax at the edge of the window is a lost match, not a position.
        if (abs(bestX) >= WM_TM_SEARCH || abs(bestY) >= WM_TM_SEARCH) return
        if (bestScore < WM_TM_MIN_SCORE) return
        if (wmOffX == bestX && wmOffY == bestY) return
        val current = OpaqueTemplate.match(t, cur, wmOffX, wmOffY)
        if (bestScore > current + WM_TM_MARGIN || current < WM_TM_MIN_SCORE) {
            wmOffX = bestX
            wmOffY = bestY
        }
    }

    /** Shifts [cur] by (dx, dy) in place (edge-clamped): cur'[p] = cur[p + (dx, dy)]. */
    private fun shiftInPlace(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        System.arraycopy(cur, 0, warpBuf, 0, px * 3)
        for (y in 0 until height) {
            val sy = (y + dy).coerceIn(0, height - 1)
            for (x in 0 until width) {
                val sx = (x + dx).coerceIn(0, width - 1)
                val dst = (y * width + x) * 3
                val s = (sy * width + sx) * 3
                cur[dst] = warpBuf[s]
                cur[dst + 1] = warpBuf[s + 1]
                cur[dst + 2] = warpBuf[s + 2]
            }
        }
    }

    private fun estimateMotion() {
        if (template.size < MIN_TEMPLATE) return
        for (p in 0 until px) {
            lumCur[p] = (cur[p * 3] + cur[p * 3 + 1] + cur[p * 3 + 2]) * (1f / 3f)
            lumPrev[p] = (prevIn[p * 3] + prevIn[p * 3 + 1] + prevIn[p * 3 + 2]) * (1f / 3f)
        }
        // One-frame estimate first: the +-SEARCH window covers the whole plausible per-frame
        // range, so this one is the reliable anchor whatever the pan speed.
        val oneFrameOk = searchMotion(lumPrev, factor = 1f)
        if (oneFrameOk) {
            val m1x = motionX
            val m1y = motionY
            // Slow-motion refinement: matching against t-3 divides the sub-pixel bias by
            // three. But the true shift is also three times larger and must stay inside the
            // search window - on a fast pan the 3-frame match would be out of range and lock
            // onto a wrong alignment, poisoning everything downstream. Only attempted when
            // the one-frame estimate proves the motion is slow enough, and its result must
            // stay consistent with that estimate.
            if (prevCount >= 2 && abs(m1x) * 3f <= SEARCH && abs(m1y) * 3f <= SEARCH) {
                if (searchMotion(lumPrev3, factor = 3f)) {
                    if (abs(motionX - m1x) <= MOTION_REFINE_MAX_DELTA && abs(motionY - m1y) <= MOTION_REFINE_MAX_DELTA) {
                        shiftLumHistory()
                        return
                    }
                    motionX = m1x
                    motionY = m1y
                } else {
                    motionX = m1x
                    motionY = m1y
                }
            }
        }
        shiftLumHistory()
    }

    /** Shifts the luminance history ring (t-1 -> t-2 -> t-3). */
    private fun shiftLumHistory() {
        System.arraycopy(lumPrev2, 0, lumPrev3, 0, px)
        System.arraycopy(lumPrev, 0, lumPrev2, 0, px)
        prevCount++
    }

    private fun searchMotion(base: FloatArray, factor: Float): Boolean {
        lumBase = base
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
        // A multi-frame baseline sees more change for the same misalignment: relax the gate.
        if (best > MAX_MATCH_ERROR * (1f + 0.25f * (factor - 1f))) return false
        // Sub-pixel refinement by a parabola through the neighbours.
        val ex0 = matchError(bestX - 1, bestY)
        val ex1 = matchError(bestX + 1, bestY)
        val ey0 = matchError(bestX, bestY - 1)
        val ey1 = matchError(bestX, bestY + 1)
        motionX = (bestX + parabolicOffset(ex0, best, ex1)) / factor
        motionY = (bestY + parabolicOffset(ey0, best, ey1)) / factor
        motionFound = true
        estimateTone()
        return true
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
            val d = (lumCur[p] - lumBase[q]).toDouble()
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
                for (c in 0 until 3) ghostAcc[p * 3 + c] += (base[p * 3 + c] - t[c]).coerceIn(-0.3f, 0.3f) * wT
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

    /** Per-channel mean of the clean (unmasked) pixels of the current frame (exposure tracking). */
    private fun computeCleanMean() {
        var r = 0.0; var g = 0.0; var b = 0.0
        var count = 0
        for (p in 0 until px) {
            if (mask[p]) continue
            r += cur[p * 3].toDouble(); g += cur[p * 3 + 1].toDouble(); b += cur[p * 3 + 2].toDouble()
            count++
        }
        if (count > 0) {
            cleanMean[0] = (r / count).toFloat()
            cleanMean[1] = (g / count).toFloat()
            cleanMean[2] = (b / count).toFloat()
        }
    }

    /**
     * Fills the opaque pixels by solving Laplace's equation over them (harmonic inpainting),
     * with the restored pixels around as boundary. Much smoother than a per-pixel average and
     * warm-started from the previous frame, so it converges in a few iterations; the temporal
     * passes then overwrite it wherever the real background has been seen.
     */
    private fun harmonicFill() {
        if (fillIdx.isEmpty()) return
        // Cold start: initialise from the nearest restored pixels (L, R, T, B).
        if (!fillWarm) {
            for (p in fillIdx) {
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
                    fillState[p * 3] = r / ws; fillState[p * 3 + 1] = g / ws; fillState[p * 3 + 2] = b / ws
                } else {
                    fillState[p * 3] = cleanMean[0]; fillState[p * 3 + 1] = cleanMean[1]; fillState[p * 3 + 2] = cleanMean[2]
                }
                weight[p] = W_FILL * 0.25f
            }
            fillWarm = true
        }
        // Gauss-Seidel: neighbours come from the restored pixels (boundary) or the fill state.
        val iters = if (fillColdDone) FILL_ITERS_WARM else FILL_ITERS
        fillColdDone = true
        for (it in 0 until iters) {
            val forward = it % 2 == 0
            var maxDelta = 0f
            for (kk in fillIdx.indices) {
                val p = if (forward) fillIdx[kk] else fillIdx[fillIdx.size - 1 - kk]
                val x = p % width
                val y = p / width
                val l = if (x > 0) p - 1 else p
                val r = if (x < width - 1) p + 1 else p
                val u = if (y > 0) p - width else p
                val d = if (y < height - 1) p + width else p
                for (c in 0 until 3) {
                    val vl = if (fillMask[l]) fillState[l * 3 + c] else out[l * 3 + c]
                    val vr = if (fillMask[r]) fillState[r * 3 + c] else out[r * 3 + c]
                    val vu = if (fillMask[u]) fillState[u * 3 + c] else out[u * 3 + c]
                    val vd = if (fillMask[d]) fillState[d * 3 + c] else out[d * 3 + c]
                    val newV = 0.25f * (vl + vr + vu + vd)
                    maxDelta = max(maxDelta, abs(newV - fillState[p * 3 + c]))
                    fillState[p * 3 + c] = newV
                }
            }
            if (maxDelta < 1e-4f) break
        }
        for (p in fillIdx) {
            if (weight[p] < W_FILL) {
                out[p * 3] = fillState[p * 3]
                out[p * 3 + 1] = fillState[p * 3 + 1]
                out[p * 3 + 2] = fillState[p * 3 + 2]
                weight[p] = W_FILL
            }
        }
    }

    /**
     * The "motion fill" of the pro tools: every masked pixel that is not yet established looks
     * up its own background in the stored frames, at the position given by the accumulated
     * motion, and adopts it when the source there is trusted (clean or well established). The
     * candidate is exposure-corrected towards the current frame and outlier-checked against the
     * running estimate, so a wrong motion or an occlusion never drags garbage in.
     */
    private fun motionFill() {
        if (histCount == 0) return
        for (p in 0 until px) {
            if (!mask[p] || weight[p] >= MOTION_FILL_DONE) continue
            val x = p % width
            val y = p / width
            var bestW = 0f
            var bestR = 0f; var bestG = 0f; var bestB = 0f
            var bestAge = 1
            for (h in 0 until histCount) {
                val idx = (histStart + histCount - 1 - h) % historyDepth
                val age = max(1, frameNo - histFrame[idx])
                // Where the content now at (x, y) was, `age` frames ago - in the aligned
                // domain of THAT frame, which is displaced by the warp change since then.
                val sx = x - (cumX - histCumX[idx]) + (wmOffX - histWmOffX[idx])
                val sy = y - (cumY - histCumY[idx]) + (wmOffY - histWmOffY[idx])
                if (sx < 0f || sy < 0f || sx > width - 1f || sy > height - 1f) continue
                val x0 = floor(sx).toInt()
                val y0 = floor(sy).toInt()
                val x1 = min(x0 + 1, width - 1)
                val y1 = min(y0 + 1, height - 1)
                val fx = sx - x0
                val fy = sy - y0
                val i00 = y0 * width + x0; val i10 = y0 * width + x1
                val i01 = y1 * width + x0; val i11 = y1 * width + x1
                val wSrc = (decodeWeight(histWeight[idx][i00]) * (1f - fx) * (1f - fy) +
                    decodeWeight(histWeight[idx][i10]) * fx * (1f - fy) +
                    decodeWeight(histWeight[idx][i01]) * (1f - fx) * fy +
                    decodeWeight(histWeight[idx][i11]) * fx * fy)
                if (wSrc < MOTION_FILL_MIN_SRC) continue
                // Motion error accumulates with the frame distance; one resampling.
                val sigma = sqrt(SIGMA_HOP * SIGMA_HOP * age + 1e-4f)
                val wT = 1f / (1f / wSrc + sigma * sigma)
                if (wT <= bestW) continue
                val ob = histOut[idx]
                var r = ((ob[i00 * 3].toInt() and 0xFF) * (1f - fx) * (1f - fy) +
                    (ob[i10 * 3].toInt() and 0xFF) * fx * (1f - fy) +
                    (ob[i01 * 3].toInt() and 0xFF) * (1f - fx) * fy +
                    (ob[i11 * 3].toInt() and 0xFF) * fx * fy) * INV255
                var g = ((ob[i00 * 3 + 1].toInt() and 0xFF) * (1f - fx) * (1f - fy) +
                    (ob[i10 * 3 + 1].toInt() and 0xFF) * fx * (1f - fy) +
                    (ob[i01 * 3 + 1].toInt() and 0xFF) * (1f - fx) * fy +
                    (ob[i11 * 3 + 1].toInt() and 0xFF) * fx * fy) * INV255
                var b = ((ob[i00 * 3 + 2].toInt() and 0xFF) * (1f - fx) * (1f - fy) +
                    (ob[i10 * 3 + 2].toInt() and 0xFF) * fx * (1f - fy) +
                    (ob[i01 * 3 + 2].toInt() and 0xFF) * (1f - fx) * fy +
                    (ob[i11 * 3 + 2].toInt() and 0xFF) * fx * fy) * INV255
                // Exposure correction towards the current frame.
                r *= (cleanMean[0] / max(histMean[idx][0], 0.02f)).coerceIn(0.6f, 1.6f)
                g *= (cleanMean[1] / max(histMean[idx][1], 0.02f)).coerceIn(0.6f, 1.6f)
                b *= (cleanMean[2] / max(histMean[idx][2], 0.02f)).coerceIn(0.6f, 1.6f)
                bestR = r.coerceIn(0f, 1f); bestG = g.coerceIn(0f, 1f); bestB = b.coerceIn(0f, 1f)
                bestW = wT
                bestAge = age
                if (wT >= MOTION_FILL_STRONG) break // recent and strong: stop looking further
            }
            if (bestW <= 0f) continue
            val wB = weight[p]
            // Outlier test: the candidate must agree with the running estimate.
            val tol = 3f * sqrt(1f / bestW + 1f / max(wB, 1f)) + 0.02f + 0.01f * min(bestAge, 20)
            if (max(abs(bestR - out[p * 3]), max(abs(bestG - out[p * 3 + 1]), abs(bestB - out[p * 3 + 2]))) > tol) continue
            val total = wB + bestW
            out[p * 3] = (out[p * 3] * wB + bestR * bestW) / total
            out[p * 3 + 1] = (out[p * 3 + 1] * wB + bestG * bestW) / total
            out[p * 3 + 2] = (out[p * 3 + 2] * wB + bestB * bestW) / total
            weight[p] = min(total, W_MAX)
        }
    }

    /**
     * The "content-aware" finish of the pro tools: a pixel that nothing has ever revealed (still
     * at the bare fill confidence) would otherwise stay a smooth, visibly blurry patch. Instead,
     * the REAL texture of the nearest clean pixel is grafted on it: the detail comes from the
     * actual picture (grain, edges, noise) and only the large-scale colour is kept from the
     * harmonic fill, so gradients continue smoothly while the area stops looking like blur.
     * As soon as the motion reveals the true background, the pixel is already above this
     * confidence and keeps the real thing.
     */
    private fun textureFill() {
        if (fillIdx.isEmpty()) return
        for (p in fillIdx) {
            if (weight[p] > W_FILL * 1.01f) continue
            // Nearest clean pixels (the four stored distances), nearest first. A donor sitting
            // on a strong edge is skipped: its deviation from the local mean is not "texture"
            // but the edge itself, and grafting it would push the pixel far outside the
            // harmonic colour (black / saturated patches on real videos).
            var bestQ = -1
            var mr = 0f; var mg = 0f; var mb = 0f
            val cand = IntArray(4)
            val candD = IntArray(4)
            var nc = 0
            for (k in 0 until 4) {
                val d = layer.distances[p * 4 + k].toInt() and 0xFF
                if (d == 0 || d == 255) continue
                val q = when (k) {
                    0 -> p - d
                    1 -> p + d
                    2 -> p - d * width
                    else -> p + d * width
                }
                if (q < 0 || q >= px) continue
                cand[nc] = q; candD[nc] = d; nc++
            }
            // nearest first
            for (a in 1 until nc) {
                val q = cand[a]; val d = candD[a]; var b = a - 1
                while (b >= 0 && candD[b] > d) { cand[b + 1] = cand[b]; candD[b + 1] = candD[b]; b-- }
                cand[b + 1] = q; candD[b + 1] = d
            }
            for (a in 0 until nc) {
                val q = cand[a]
                val qx = q % width
                val qy = q / width
                var r = 0f; var g = 0f; var b = 0f
                var mnR = 1f; var mxR = 0f
                var cnt = 0
                for (yy in max(0, qy - 1)..min(height - 1, qy + 1)) {
                    for (xx in max(0, qx - 1)..min(width - 1, qx + 1)) {
                        val s = yy * width + xx
                        if (fillMask[s]) continue
                        r += out[s * 3]; g += out[s * 3 + 1]; b += out[s * 3 + 2]; cnt++
                        if (out[s * 3] < mnR) mnR = out[s * 3]
                        if (out[s * 3] > mxR) mxR = out[s * 3]
                    }
                }
                if (cnt < 4) continue
                if (mxR - mnR > TEX_DONOR_RANGE) continue // edge donor, try the next one
                bestQ = q
                mr = r / cnt; mg = g / cnt; mb = b / cnt
                break
            }
            if (bestQ < 0) continue
            // Graft: capped donor detail + harmonic colour (the tone comes from the fill, the
            // grain from the real picture).
            val dr = (out[bestQ * 3] - mr).coerceIn(-TEX_DEV, TEX_DEV)
            val dg = (out[bestQ * 3 + 1] - mg).coerceIn(-TEX_DEV, TEX_DEV)
            val db = (out[bestQ * 3 + 2] - mb).coerceIn(-TEX_DEV, TEX_DEV)
            out[p * 3] = (fillState[p * 3] + dr).coerceIn(0f, 1f)
            out[p * 3 + 1] = (fillState[p * 3 + 1] + dg).coerceIn(0f, 1f)
            out[p * 3 + 2] = (fillState[p * 3 + 2] + db).coerceIn(0f, 1f)
            weight[p] = W_TEXTURE
        }
    }

    /**
     * Stores the restored frame (and its confidences) for the motion fill lookups. With a slow
     * background, consecutive frames barely differ in what they reveal: storing every frame
     * would waste the ring on a few pixels of travel and the centre of a wide opaque area
     * would never see its real background. So a frame is stored only when the background has
     * travelled far enough since the last one (or after a maximum gap, so a nearly still
     * picture keeps recent frames too): the same memory then covers a much longer span.
     */
    private fun pushHistory() {
        if (histCount > 0 && frameNo - lastPushFrame < MAX_PUSH_GAP) {
            val travelX = abs(cumX - lastPushCumX)
            val travelY = abs(cumY - lastPushCumY)
            if (travelX < PUSH_TRAVEL && travelY < PUSH_TRAVEL) {
                frameNo++
                return
            }
        }
        val idx: Int
        if (histCount < historyDepth) {
            idx = (histStart + histCount) % historyDepth
            histCount++
        } else {
            idx = histStart
            histStart = (histStart + 1) % historyDepth
        }
        lastPushFrame = frameNo
        lastPushCumX = cumX
        lastPushCumY = cumY
        histFrame[idx] = frameNo
        frameNo++
        val ob = histOut[idx]
        val wb = histWeight[idx]
        for (i in 0 until px) {
            ob[i * 3] = toByte(out[i * 3])
            ob[i * 3 + 1] = toByte(out[i * 3 + 1])
            ob[i * 3 + 2] = toByte(out[i * 3 + 2])
            wb[i] = encodeWeight(weight[i])
        }
        histCumX[idx] = cumX
        histCumY[idx] = cumY
        histWmOffX[idx] = wmOffX
        histWmOffY[idx] = wmOffY
        histMean[idx][0] = cleanMean[0]
        histMean[idx][1] = cleanMean[1]
        histMean[idx][2] = cleanMean[2]
    }

    private fun encodeWeight(w: Float): Byte {
        if (w <= 1f) return 0
        val v = (32f * log10(w)).roundToInt().coerceIn(1, 255)
        return v.toByte()
    }

    private fun decodeWeight(b: Byte): Float = WEIGHT_DECODE[b.toInt() and 0xFF]

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
        /** Max gap between the one-frame estimate and the 3-frame refinement of it. */
        private const val MOTION_REFINE_MAX_DELTA = 1.5f
        /** Search range (+- px) of the per-frame watermark alignment. */
        private const val WM_OFF_SEARCH = 12
        /** Opacity step for a tap to count towards watermark localisation. */
        private const val WM_OFF_TAP_ALPHA = 0.08f
        /** Minimum strong taps before the watermark position is tracked at all. */
        private const val WM_OFF_MIN_TAPS = 6
        /** Inverted pixels must cover at least this fraction of the mask for tracking. */
        private const val WM_OFF_INV_FRACTION = 0.50f
        /** Minimum correlation for a watermark offset to be adopted. */
        private const val WM_OFF_MIN_SCORE = 0.45f
        /** Margin required over the current offset before switching (anti-flicker). */
        private const val WM_OFF_MARGIN = 0.08f
        /** Search range (+- px) of the opaque-template watermark alignment. */
        private const val WM_TM_SEARCH = 16
        /** Minimum template match score for an offset to be adopted. */
        private const val WM_TM_MIN_SCORE = 0.55f
        /** Minimum template support pixels before the watermark position is tracked at all. */
        private const val WM_TM_MIN_TRACK_PIXELS = 150
        /** The support must span this fraction of the mask's bounding box, on both axes. */
        private const val WM_TM_MIN_SPAN = 0.5f
        /** Margin required over the current offset before switching (anti-flicker). */
        private const val WM_TM_MARGIN = 0.08f
        /** Background travel (px) needed before a new frame enters the history. */
        private const val PUSH_TRAVEL = 2.5f
        /** Maximum frames between two stored frames (keeps recent frames on a still picture). */
        private const val MAX_PUSH_GAP = 24
        /** Noise of the source pixels (8-bit + compression), before amplification by the inversion. */
        private const val SIGMA_NOISE = 0.02f
        /** Error added by one frame of propagation (motion / interpolation). */
        private const val SIGMA_HOP = 0.012f
        private const val W_CLEAN = 10_000f
        private const val W_FILL = 1f / (0.12f * 0.12f)
        private const val W_MAX = 8_000f
        /** Candidates at most ~2 hops away from a clean pixel teach the ghost estimate. */
        private const val GHOST_MIN_WEIGHT = 2_000f
        /** Evidence needed before the learned ghost is subtracted (about 1 trusted frame). */
        private const val GHOST_MIN_TOTAL = 2_500f
        /** Forgetting horizon of the ghost estimate (keeps adapting to slow drifts). */
        private const val GHOST_MAX_TOTAL = 200_000f
        /** Motion fill: minimum source confidence for a stored frame to be used. */
        private const val MOTION_FILL_MIN_SRC = 600f
        /**
         * Motion fill: stop scanning older frames once a source this strong is found. With
         * SIGMA_HOP, a source older than one frame cannot reach this weight, so breaking is
         * provably optimal - and it keeps the common case (recent clean source) at one entry.
         */
        private const val MOTION_FILL_STRONG = 2_600f
        /** Motion fill: pixels at least this certain are not looked up any more. */
        private const val MOTION_FILL_DONE = 3_000f
        /** Confidence of a texture-grafted pixel: real detail, but not the real background. */
        private const val W_TEXTURE = 250f
        /** Max local range (red channel) of a texture donor (stronger = it sits on an edge). */
        private const val TEX_DONOR_RANGE = 0.30f
        /** Cap on the grafted detail: keeps the graft inside the harmonic colour. */
        private const val TEX_DEV = 0.12f
        /** Gauss-Seidel iterations of the harmonic fill, on the first (cold) frame. */
        private const val FILL_ITERS = 26
        /**
         * Iterations on the following frames: the state is warm-started from the previous
         * frame, the boundary evolves slowly and the motion fill overwrites the result
         * wherever the real background is known - a few sweeps are enough.
         */
        private const val FILL_ITERS_WARM = 18
        /** Decoding table of the log-quantised confidences (computed once, no pow in the loops). */
        private val WEIGHT_DECODE = FloatArray(256) { if (it == 0) 0f else 10f.pow(it / 32f) }

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
