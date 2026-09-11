package bench

import com.souxch.watermarkremover.processing.RegionRestorer
import com.souxch.watermarkremover.processing.WatermarkAnalyzer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// =============================================================================================
// Synthetic Vizard-style bench.
//
// A textured background moves (per-clip motion vectors, phase jumps at the cuts); a watermark
// is composited on top (semi-transparent wordmark or opaque logo+mark), either static,
// re-rendered per clip (+offset) or slowly drifting. The analysis gets every `sampleStep`-th
// frame (like the app samples ~24 frames), the restorer runs over every frame, and the output
// is compared with the clean background (same noise realisation) over the union of the
// watermark placements.
//
// ONE file only: any variant file in the same source set breaks the build (redeclarations).
// =============================================================================================

/** Moving textured background, cut into clips. */
class Synth(
    val w: Int,
    val h: Int,
    seed: Int,
    val clipFrames: Int, // Int.MAX_VALUE = one single clip
    val mx: Float,
    val my: Float, // motion of clip 0 (px / frame)
    val jitter: Float, // extra random motion of the later clips
    val noiseAmp: Float = 0.012f,
) {
    private val g = 4
    private val grids = Array(6) { FloatArray(g * g) }
    private val clips = ArrayList<Clip>()

    class Clip(val phaseX: Float, val phaseY: Float, val mx: Float, val my: Float)

    init {
        val base = Random(seed)
        for (grid in grids) for (i in grid.indices) grid[i] = base.nextFloat()
        val rng = Random(seed * 31 + 7)
        clips.add(Clip(64f, 64f, mx, my))
        for (i in 1 until 256) {
            clips.add(
                Clip(
                    rng.nextFloat() * 160f, rng.nextFloat() * 160f,
                    mx + jitter * (rng.nextFloat() - 0.5f) * 2f,
                    my + jitter * (rng.nextFloat() - 0.5f) * 2f,
                )
            )
        }
    }

    fun clipOf(t: Int): Int = if (clipFrames == Int.MAX_VALUE) 0 else t / clipFrames

    private fun noise(grid: FloatArray, cell: Float, x: Float, y: Float): Float {
        val fx = x / cell
        val fy = y / cell
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        fun gv(ix: Int, iy: Int) = grid[((iy % g + g) % g) * g + ((ix % g + g) % g)]
        val a = gv(x0, y0)
        val b = gv(x0 + 1, y0)
        val c = gv(x0, y0 + 1)
        val d = gv(x0 + 1, y0 + 1)
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy
    }

    /** Clean background pixel (RGB 0..1) at (x, y) of frame [t]. */
    fun bg(x: Int, y: Int, t: Int, out: FloatArray) {
        val c = clips[clipOf(t)]
        val xx = x + c.phaseX - c.mx * t
        val yy = y + c.phaseY - c.my * t
        for (ch in 0 until 3) {
            val fine = noise(grids[2 * ch], 6f, xx, yy)
            val coarse = noise(grids[2 * ch + 1], 19f, xx, yy)
            val bands = 0.5f + 0.5f * sin((xx + 2f * yy) * 0.11f + ch * 2.1f)
            val v = 0.30f * fine + 0.35f * coarse + 0.35f * bands
            out[ch] = (0.10f + 0.82f * v).coerceIn(0.04f, 0.96f)
        }
    }
}

/** Watermark bitmap (hard edges) with a flat colour and opacity. */
class Wm(val w: Int, val h: Int, val shape: BooleanArray, val colour: FloatArray, val alpha: Float) {
    companion object {
        private val GLYPHS = mapOf(
            'V' to arrayOf("V...V", "V...V", "V...V", "V...V", ".V.V."),
            'I' to arrayOf("IIIII", "..I..", "..I..", "..I..", "IIIII"),
            'Z' to arrayOf("ZZZZZ", "...Z.", "..Z..", ".Z...", "ZZZZZ"),
            'A' to arrayOf(".AAA.", "A...A", "AAAAA", "A...A", "A...A"),
            'R' to arrayOf("RRRR.", "R...R", "RRRR.", "R..R.", "R...R"),
            'D' to arrayOf("DDDD.", "D...D", "D...D", "D...D", "DDDD."),
        )

        /** "VIZARD" pseudo wordmark, strokes scaled by [scale]. */
        fun wordmark(scale: Int, colour: FloatArray, alpha: Float): Wm {
            val text = "VIZARD"
            val cw = 6 // 5 px glyph + 1 gap
            val w = text.length * cw * scale
            val h = 5 * scale
            val shape = BooleanArray(w * h)
            text.forEachIndexed { li, ch ->
                val glyph = GLYPHS[ch]!!
                for (gy in 0 until 5) for (gx in 0 until 5) {
                    if (glyph[gy][gx] != ch) continue
                    for (sy in 0 until scale) for (sx in 0 until scale) {
                        val x = (li * cw + gx) * scale + sx
                        val y = gy * scale + sy
                        shape[y * w + x] = true
                    }
                }
            }
            return Wm(w, h, shape, colour, alpha)
        }

        /** Opaque logo: solid mark + the wordmark. */
        fun logoOpaque(colour: FloatArray, alpha: Float): Wm {
            val text = wordmark(2, colour, alpha)
            val mark = 12
            val gap = 4
            val w = mark + gap + text.w
            val h = max(mark, text.h)
            val shape = BooleanArray(w * h)
            for (y in 0 until mark) for (x in 0 until mark) shape[y * w + x] = true
            for (y in 0 until text.h) for (x in 0 until text.w)
                if (text.shape[y * text.w + x]) shape[y * w + mark + gap + x] = true
            return Wm(w, h, shape, colour, alpha)
        }
    }
}

/** Deterministic case: synthesises clean and watermarked frames. */
class CaseGen(
    val synth: Synth,
    val wm: Wm,
    val baseX: Int,
    val baseY: Int,
    val offX: (Int) -> Int,
    val offY: (Int) -> Int,
    private val seed: Int,
) {
    private val w = synth.w
    private val h = synth.h

    private fun render(t: Int): FloatArray {
        val rng = Random(seed * 1009 + t)
        val out = FloatArray(w * h * 3)
        val rgb = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            synth.bg(x, y, t, rgb)
            for (c in 0 until 3) out[(y * w + x) * 3 + c] = (rgb[c] + (rng.nextFloat() - 0.5f) * 2f * synth.noiseAmp).coerceIn(0f, 1f)
        }
        return out
    }

    private fun quantize(f: FloatArray): ByteArray {
        val out = ByteArray(f.size)
        for (i in f.indices) out[i] = (f[i] * 255f + 0.5f).toInt().toByte()
        return out
    }

    fun clean(t: Int): ByteArray = quantize(render(t))

    fun watermarked(t: Int): ByteArray {
        val bg = render(t)
        val ox = baseX + offX(t)
        val oy = baseY + offY(t)
        for (y in 0 until wm.h) for (x in 0 until wm.w) {
            if (!wm.shape[y * wm.w + x]) continue
            val dy = oy + y
            val dx = ox + x
            if (dy !in 0 until h || dx !in 0 until w) continue
            for (c in 0 until 3) {
                val v = wm.alpha * wm.colour[c] + (1f - wm.alpha) * bg[(dy * w + dx) * 3 + c]
                bg[(dy * w + dx) * 3 + c] = v.coerceIn(0f, 1f)
            }
        }
        return quantize(bg)
    }
}

private const val W = 128
private const val H = 96
private const val N_FRAMES = 72
private const val SAMPLE_STEP = 3

private val WHITE = floatArrayOf(1f, 1f, 1f)
private val GRAY = floatArrayOf(0.80f, 0.80f, 0.83f)

fun runCase(name: String, gen: CaseGen, probe: Boolean = false) {
    val samples = (0 until N_FRAMES step SAMPLE_STEP).map { gen.watermarked(it) }
    val t0 = System.currentTimeMillis()
    val layer = WatermarkAnalyzer.analyze(WatermarkAnalyzer.Frames(W, H, samples))
    val dt = System.currentTimeMillis() - t0
    if (layer == null || !layer.hasWatermark) {
        val reason = layer?.stats?.reason ?: "null"
        println("%-24s NO LAYER (%s) -> spatial fallback  [${dt} ms]".format(name, reason))
        return
    }
    val st = layer.stats
    val restorer = RegionRestorer(layer)
    var minX = W
    var maxX = 0
    var minY = H
    var maxY = 0
    for (t in 0 until N_FRAMES) {
        val ox = gen.baseX + gen.offX(t)
        val oy = gen.baseY + gen.offY(t)
        minX = min(minX, ox)
        minY = min(minY, oy)
        maxX = max(maxX, ox + gen.wm.w)
        maxY = max(maxY, oy + gen.wm.h)
    }
    var errSum = 0.0
    var errN = 0
    var lateSum = 0.0
    var lateN = 0
    var blacks = 0
    val trace = StringBuilder()
    var lastClip = -1
    val rgba = ByteArray(W * H * 4)
    for (t in 0 until N_FRAMES) {
        val wmFrame = gen.watermarked(t)
        for (i in 0 until W * H) {
            rgba[i * 4] = wmFrame[i * 3]
            rgba[i * 4 + 1] = wmFrame[i * 3 + 1]
            rgba[i * 4 + 2] = wmFrame[i * 3 + 2]
            rgba[i * 4 + 3] = -1
        }
        restorer.process(rgba, false, rgba)
        val clean = gen.clean(t)
        val late = t >= N_FRAMES * 3 / 5
        for (y in minY..maxY) for (x in minX..maxX) {
            val i = (y * W + x) * 3
            var d = 0f
            var oc = 0f
            var cc = 0f
            for (c in 0 until 3) {
                val o = (rgba[(y * W + x) * 4 + c].toInt() and 0xFF) * (1f / 255f)
                val k = (clean[i + c].toInt() and 0xFF) * (1f / 255f)
                d = max(d, abs(o - k))
                oc += o
                cc += k
            }
            errSum += d
            errN++
            if (late) {
                lateSum += d
                lateN++
            }
            if (oc / 3f < 0.12f && cc / 3f > 0.35f) blacks++
        }
        val clip = gen.synth.clipOf(t)
        if (clip != lastClip || t % 12 == 0) {
            if (trace.isNotEmpty()) trace.append(' ')
            trace.append("c$clip@(${restorer.watermarkOffsetX},${restorer.watermarkOffsetY})")
            lastClip = clip
        }
    }
    val extra = if (layer.template != null) " tmpl=on" else ""
    println(
        "%-24s mask=%-5d inv=%-5d fill=%-5d %-8s err=%.4f late=%.4f blacks=%-4d [%d ms]%s"
            .format(name, st.maskPixels, st.invertedPixels, st.filledPixels, st.reason, errSum / errN, lateSum / lateN, blacks, dt, extra)
    )
    println("%-24s wmOff: %s".format("", trace))
}

fun main(args: Array<String>) {
    val filter = args.getOrNull(0) ?: ""
    val probe = args.contains("probe")
    fun case(name: String, build: () -> CaseGen) {
        if (name.contains(filter)) runCase(name, build(), probe)
    }
    val single = Int.MAX_VALUE
    val staticOff: (Int) -> Int = { 0 }

    case("semi_fast_static") {
        CaseGen(Synth(W, H, 11, single, 4.5f, 3.0f, 0f), Wm.wordmark(2, WHITE, 0.5f), 28, 42, staticOff, staticOff, 101)
    }
    case("gray_fast_static") {
        CaseGen(Synth(W, H, 12, single, 4.5f, 3.0f, 0f), Wm.wordmark(2, GRAY, 0.40f), 28, 42, staticOff, staticOff, 102)
    }
    case("semi_slow") {
        CaseGen(Synth(W, H, 13, single, 0.5f, 0.3f, 0f), Wm.wordmark(2, WHITE, 0.5f), 28, 42, staticOff, staticOff, 103)
    }
    case("opaque_static") {
        CaseGen(Synth(W, H, 14, single, 4.0f, 2.5f, 0f), Wm.logoOpaque(WHITE, 1.0f), 20, 42, staticOff, staticOff, 104)
    }
    case("cuts_static") {
        CaseGen(Synth(W, H, 15, 24, 4.0f, 2.5f, 3f), Wm.wordmark(2, WHITE, 0.5f), 28, 42, staticOff, staticOff, 105)
    }
    case("cuts_shift_semi") {
        CaseGen(Synth(W, H, 16, 24, 4.0f, 2.5f, 3f), Wm.wordmark(2, WHITE, 0.5f), 22, 40, { 6 * (it / 24) }, { 3 * (it / 24) }, 106)
    }
    case("drift_semi") {
        CaseGen(Synth(W, H, 17, single, 3.0f, 2.0f, 0f), Wm.wordmark(2, WHITE, 0.5f), 28, 42,
            { (2.5f * sin(it * 0.022f)).roundToInt() }, { (1.5f * sin(it * 0.017f)).roundToInt() }, 107)
    }
    case("cuts_shift_opaque") {
        CaseGen(Synth(W, H, 18, 24, 4.0f, 2.5f, 3f), Wm.logoOpaque(WHITE, 1.0f), 20, 42, { 6 * (it / 24) }, { 3 * (it / 24) }, 108)
    }
    case("cuts_shift_opaque85") {
        CaseGen(Synth(W, H, 19, 24, 4.0f, 2.5f, 3f), Wm.logoOpaque(WHITE, 0.85f), 20, 42, { 6 * (it / 24) }, { 3 * (it / 24) }, 109)
    }
    case("drift_opaque") {
        CaseGen(Synth(W, H, 20, single, 3.0f, 2.0f, 0f), Wm.logoOpaque(WHITE, 1.0f), 20, 42,
            { (2.5f * sin(it * 0.022f)).roundToInt() }, { (1.5f * sin(it * 0.017f)).roundToInt() }, 110)
    }
    val patternOff: (Int) -> Int = { listOf(0, 6, 12, 6)[(it / 8) % 4] }
    val patternOffY: (Int) -> Int = { listOf(0, 3, 6, 3)[(it / 8) % 4] }
    case("short_clips_shift_semi") {
        CaseGen(Synth(W, H, 21, 8, 3.0f, 2.0f, 3f), Wm.wordmark(2, WHITE, 0.5f), 28, 42, patternOff, patternOffY, 111)
    }
    case("short_clips_shift_opaque") {
        CaseGen(Synth(W, H, 22, 8, 3.0f, 2.0f, 3f), Wm.logoOpaque(WHITE, 1.0f), 20, 42, patternOff, patternOffY, 112)
    }
}
