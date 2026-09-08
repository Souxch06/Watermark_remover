package com.souxch.watermarkremover.processing

import com.souxch.watermarkremover.model.NormalizedRect
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.RemovalSettings
import com.souxch.watermarkremover.model.WatermarkZone

/**
 * GLSL ES 1.00 sources shared by the export pipeline (Media3 `GlEffect`) and the live preview
 * (`GLSurfaceView`). Keeping the shader in one place guarantees the preview matches the export.
 *
 * Coordinates: the fragment shader works in texture space where (0,0) is the bottom-left corner
 * (OpenGL convention). Zones are authored in display space with (0,0) top-left, so [zoneUniforms]
 * flips the y axis before uploading.
 */
object WatermarkShader {

    /** Maximum number of zones processed in a single pass. */
    const val MAX_ZONES = 6

    /** Anti-aliasing band (in pixels) around a reconstructed zone. */
    const val INPAINT_EDGE_PIXELS = 2.5f

    // ---- Uniform / attribute names ----
    const val U_TEX_SAMPLER = "uTexSampler"
    const val U_TRANSFORMATION_MATRIX = "uTransformationMatrix"
    const val U_TEX_TRANSFORMATION_MATRIX = "uTexTransformationMatrix"
    const val U_TEXEL_SIZE = "uTexelSize"
    const val U_ZONES = "uZones"
    const val U_ZONE_COUNT = "uZoneCount"
    const val U_METHOD = "uMethod"
    const val U_STRENGTH = "uStrength"
    const val U_FEATHER = "uFeather"
    const val U_LAYER_SAMPLER = "uLayerSampler"
    const val U_LAYER_RECT = "uLayerRect"
    const val U_LAYER_OFFSET = "uLayerOffset"
    const val U_LAYER_SCALE = "uLayerScale"
    const val U_LAYER_TEXEL = "uLayerTexel"
    const val A_FRAME_POSITION = "aFramePosition"

    /** Shader method id of the "true picture behind the watermark" mode (see [WatermarkLayer]). */
    const val METHOD_LAYER = 3

    /**
     * Vertex shader compatible with Media3's `BaseGlShaderProgram` conventions: positions come in
     * NDC and are multiplied by a transformation matrix; texture coordinates are derived from the
     * position and optionally transformed (needed for external/OES textures in the preview).
     */
    val VERTEX_SHADER: String = """
        attribute vec4 aFramePosition;
        uniform mat4 uTransformationMatrix;
        uniform mat4 uTexTransformationMatrix;
        varying vec2 vTexSamplingCoord;
        void main() {
          gl_Position = uTransformationMatrix * aFramePosition;
          vec4 texCoord = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0);
          vTexSamplingCoord = (uTexTransformationMatrix * texCoord).xy;
        }
    """.trimIndent()

    /**
     * Fragment shader. `SAMPLER_TYPE` and the optional OES extension line are substituted at
     * build time depending on whether we sample a regular 2D texture (export) or an external
     * texture straight from the decoder (preview).
     */
    private val FRAGMENT_SHADER_TEMPLATE: String = """
        %EXTENSION%
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform %SAMPLER_TYPE% uTexSampler;
        uniform vec2 uTexelSize;        // 1.0 / texture size
        uniform vec4 uZones[$MAX_ZONES]; // left, bottom, right, top in texture space (y up)
        uniform int uZoneCount;
        uniform int uMethod;            // 0 = inpaint, 1 = blur, 2 = pixelate
        uniform float uStrength;        // 0..1
        uniform float uFeather;         // feather width in texture units
        // Recovered watermark layers (uMethod == 3), see WatermarkLayer / layerAt().
        uniform sampler2D uLayerSampler;
        uniform vec4 uLayerRect[$MAX_ZONES];
        uniform float uLayerOffset[$MAX_ZONES];
        uniform vec2 uLayerScale;
        uniform vec2 uLayerTexel;
        varying vec2 vTexSamplingCoord;

        vec4 sampleTex(vec2 uv) {
          return %SAMPLE_FN%(uTexSampler, clamp(uv, vec2(0.001), vec2(0.999)));
        }

        // Signed distance to the rectangle (negative inside).
        float rectDistance(vec2 p, vec4 r) {
          vec2 c = vec2((r.x + r.z) * 0.5, (r.y + r.w) * 0.5);
          vec2 h = vec2((r.z - r.x) * 0.5, (r.w - r.y) * 0.5);
          vec2 d = abs(p - c) - h;
          return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
        }

        // Approximate Gaussian blur along both axes: 9x9 taps with a large stride so the kernel
        // covers a big area at low cost. Stride scales with the zone size and uStrength.
        vec4 blurAt(vec2 uv, vec2 stride) {
          vec4 acc = vec4(0.0);
          float wsum = 0.0;
          for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
              float w = exp(-float(x * x + y * y) / 10.0);
              acc += sampleTex(uv + vec2(float(x), float(y)) * stride) * w;
              wsum += w;
            }
          }
          return acc / wsum;
        }

        // Wide, low-pass average of the picture around `p` (in texel units), with every tap kept
        // OUTSIDE the zone on the given side so the watermark itself is never sampled:
        // side 0 = left of the zone, 1 = right, 2 = below, 3 = above.
        vec4 lowPassOutside(vec2 p, float stepPx, int side, vec4 r) {
          vec2 m = uTexelSize * 2.0;
          vec4 acc = vec4(0.0);
          float wsum = 0.0;
          for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
              float w = exp(-float(x * x + y * y) / 4.5);
              vec2 q = p + vec2(float(x), float(y)) * stepPx * uTexelSize;
              if (side == 0) { q.x = min(q.x, r.x - m.x); }
              else if (side == 1) { q.x = max(q.x, r.z + m.x); }
              else if (side == 2) { q.y = min(q.y, r.y - m.y); }
              else { q.y = max(q.y, r.w + m.y); }
              acc += sampleTex(q) * w;
              wsum += w;
            }
          }
          return acc / wsum;
        }

        // Sharp reconstruction of the zone.
        //  1. Every pixel is a straight COPY of the real, untouched pixel mirrored across the
        //     nearest edge of the zone (texel-aligned, no resampling): the fill keeps 100 % of the
        //     grain, texture and fine edges of the surroundings - nothing is averaged or blurred.
        //     Only in the narrow band where two edges are equally close do the two candidates
        //     cross-fade, which hides the diagonal seam.
        //  2. The mirrored copy is then tone-matched: the large-scale colour/brightness of the
        //     patch is replaced by the colour expected at this point from the four edges (weighted
        //     by inverse squared distance), so gradients continue smoothly through the zone while
        //     the detail stays crisp.
        // Directions whose source would fall outside the frame (zone touching a border) fade out.
        vec4 inpaintAt(vec2 uv, vec4 r) {
          vec2 zoneSize = vec2(r.z - r.x, r.w - r.y);
          vec2 m = uTexelSize * 2.0;
          // Work from a point clamped inside the zone so the feather band simply continues the
          // edge reconstruction instead of sampling the watermark.
          vec2 q = clamp(uv, r.xy + uTexelSize * 0.5, r.zw - uTexelSize * 0.5);
          float dl = q.x - r.x;
          float dr = r.z - q.x;
          float db = q.y - r.y;
          float dt = r.w - q.y;

          // Mirrored source positions, snapped to texel centres (exact copy, no bilinear blur).
          vec2 pl = (floor(vec2(r.x - m.x - dl, q.y) / uTexelSize) + 0.5) * uTexelSize;
          vec2 pr = (floor(vec2(r.z + m.x + dr, q.y) / uTexelSize) + 0.5) * uTexelSize;
          vec2 pb = (floor(vec2(q.x, r.y - m.y - db) / uTexelSize) + 0.5) * uTexelSize;
          vec2 pt = (floor(vec2(q.x, r.w + m.y + dt) / uTexelSize) + 0.5) * uTexelSize;

          // Validity of each direction (smooth so no seam appears where a source leaves the frame).
          vec2 v8 = uTexelSize * 8.0;
          float vl = smoothstep(0.0, v8.x, pl.x);
          float vr = 1.0 - smoothstep(1.0 - v8.x, 1.0, pr.x);
          float vb = smoothstep(0.0, v8.y, pb.y);
          float vt = 1.0 - smoothstep(1.0 - v8.y, 1.0, pt.y);
          if (vl + vr + vb + vt <= 1e-4) {
            return blurAt(uv, uTexelSize * 8.0);
          }

          // Nearest-edge selection (distances measured in texels so it is aspect-correct;
          // invalid directions are pushed far away - values kept small enough for mediump).
          vec2 px = 1.0 / uTexelSize;
          vec4 dp = vec4(dl * px.x, dr * px.x, db * px.y, dt * px.y);
          float el = dp.x + (1.0 - vl) * 4096.0;
          float er = dp.y + (1.0 - vr) * 4096.0;
          float eb = dp.z + (1.0 - vb) * 4096.0;
          float et = dp.w + (1.0 - vt) * 4096.0;
          float emin = min(min(el, er), min(eb, et));
          float tau = max(0.05 * min(zoneSize.x * px.x, zoneSize.y * px.y), 2.0);
          float nl = exp(-(el - emin) / tau);
          float nr = exp(-(er - emin) / tau);
          float nb = exp(-(eb - emin) / tau);
          float nt = exp(-(et - emin) / tau);
          float nsum = nl + nr + nb + nt;

          vec4 copy = (sampleTex(pl) * nl + sampleTex(pr) * nr
                     + sampleTex(pb) * nb + sampleTex(pt) * nt) / nsum;

          // Tone matching: large-scale colour of the copied patch vs. the colour expected here.
          float radiusPx = clamp(0.5 * min(zoneSize.x * px.x, zoneSize.y * px.y), 6.0, 48.0);
          float stepPx = radiusPx / 3.0;
          vec4 copyLow = (lowPassOutside(pl, stepPx, 0, r) * nl + lowPassOutside(pr, stepPx, 1, r) * nr
                        + lowPassOutside(pb, stepPx, 2, r) * nb + lowPassOutside(pt, stepPx, 3, r) * nt) / nsum;

          float wl = vl / (dp.x * dp.x + 1.0);
          float wr = vr / (dp.y * dp.y + 1.0);
          float wb = vb / (dp.z * dp.z + 1.0);
          float wt = vt / (dp.w * dp.w + 1.0);
          float wsum = wl + wr + wb + wt;
          vec4 expected = (lowPassOutside(vec2(r.x - m.x, q.y), stepPx, 0, r) * wl
                         + lowPassOutside(vec2(r.z + m.x, q.y), stepPx, 1, r) * wr
                         + lowPassOutside(vec2(q.x, r.y - m.y), stepPx, 2, r) * wb
                         + lowPassOutside(vec2(q.x, r.w + m.y), stepPx, 3, r) * wt) / wsum;

          return vec4(clamp(copy.rgb + (expected.rgb - copyLow.rgb), 0.0, 1.0), copy.a);
        }

        // ---- Watermark layer mode (uMethod == 3) -------------------------------------------
        // uLayerSampler is an atlas holding, for every zone, the recovered watermark layer of the
        // analysed region (zone + margin) in DISPLAY row order (row 0 = top). For zone i the rows
        // [off, off + H) hold texel A = (a*W rgb, opacity a) and rows [off + H, off + 2H) hold
        // texel B = (fill flag, distance to the nearest clean pixel to the left / right / above,
        // in pixels / 255); the distance below is stored in texel A's alpha of fill pixels.
        // uLayerRect[i] = (region origin in texture space xy, region size in pixels zw),
        // uLayerScale = video pixels per texture unit (y negative when the texture is y-up),
        // uLayerOffset[i] = first atlas row of zone i, uLayerTexel = 1 / atlas size.

        vec2 layerPixel(vec2 uv, vec4 rect) {
          return (uv - rect.xy) * uLayerScale;
        }

        vec4 layerData(vec2 lp, vec4 rect, float off, float slot) {
          vec2 t = vec2((floor(lp.x) + 0.5) * uLayerTexel.x,
                        (off + slot * rect.w + floor(lp.y) + 0.5) * uLayerTexel.y);
          return texture2D(uLayerSampler, t);
        }

        bool layerInside(vec2 lp, vec4 rect) {
          return all(greaterThanEqual(lp, vec2(0.0))) && all(lessThan(lp, rect.zw));
        }

        // Picture behind the watermark at uv (semi-transparent parts: exact inversion).
        vec4 restoredAt(vec2 uv, vec4 rect, float off) {
          vec4 src = sampleTex(uv);
          vec4 a0 = layerData(layerPixel(uv, rect), rect, off, 0.0);
          float a = min(a0.a, 0.85);
          vec3 rgb = (src.rgb - a0.rgb) / (1.0 - a);
          return vec4(clamp(rgb, 0.0, 1.0), src.a);
        }

        vec4 layerAt(vec2 uv, vec4 rect, float off) {
          vec2 lp = layerPixel(uv, rect);
          vec4 b = layerData(lp, rect, off, 1.0);
          if (b.r < 0.5) {
            return restoredAt(uv, rect, off);
          }
          // Opaque part of the logo: rebuild the pixel from the 4 nearest clean pixels (mirrored
          // across the logo boundary, weighted by 1/d^2). Sources go through restoredAt too.
          vec4 a0 = layerData(lp, rect, off, 0.0);
          vec4 dist = vec4(b.g, b.b, b.a, a0.a) * 255.0;   // left, right, above, below
          vec2 px = 1.0 / uLayerScale;                      // one video pixel, in texture units
          vec4 acc = vec4(0.0);
          float wsum = 0.0;
          for (int k = 0; k < 4; k++) {
            float d = (k == 0) ? dist.x : (k == 1) ? dist.y : (k == 2) ? dist.z : dist.w;
            if (d < 0.5 || d > 254.5) { continue; }
            vec2 dir = (k == 0) ? vec2(-1.0, 0.0) : (k == 1) ? vec2(1.0, 0.0)
                     : (k == 2) ? vec2(0.0, -1.0) : vec2(0.0, 1.0);
            vec2 mirror = uv + dir * (2.0 * d) * px;
            vec2 boundary = uv + dir * d * px;
            vec2 mlp = layerPixel(mirror, rect);
            bool clean = layerInside(mlp, rect) && layerData(mlp, rect, off, 1.0).r < 0.5;
            vec4 c = restoredAt(clean ? mirror : boundary, rect, off);
            float w = 1.0 / (d * d);
            acc += c * w;
            wsum += w;
          }
          if (wsum <= 0.0) { return sampleTex(uv); }
          return acc / wsum;
        }

        vec4 pixelateAt(vec2 uv, vec4 r) {
          vec2 zoneSize = vec2(r.z - r.x, r.w - r.y);
          float blocks = mix(24.0, 4.0, uStrength);
          vec2 blockSize = max(zoneSize / blocks, uTexelSize * 2.0);
          vec2 local = uv - r.xy;
          vec2 snapped = (floor(local / blockSize) + 0.5) * blockSize + r.xy;
          return sampleTex(snapped);
        }

        void main() {
          vec2 uv = vTexSamplingCoord;
          vec4 original = sampleTex(uv);
          vec4 color = original;
          for (int i = 0; i < $MAX_ZONES; i++) {
            if (i >= uZoneCount) { break; }
            vec4 r = uZones[i];
            float d = rectDistance(uv, r);
            if (uMethod == 3 && uLayerRect[i].z > 0.5) {
              // Recovered layer: pixel-exact mask, no feather; only the analysed region.
              if (layerInside(layerPixel(uv, uLayerRect[i]), uLayerRect[i])) {
                color = layerAt(uv, uLayerRect[i], uLayerOffset[i]);
              }
              continue;
            }
            if (d > uFeather) { continue; }
            vec4 processed;
            if (uMethod == 0 || uMethod == 3) {
              processed = inpaintAt(uv, r);
            } else if (uMethod == 1) {
              vec2 zoneSize = vec2(r.z - r.x, r.w - r.y);
              vec2 stride = max(zoneSize * (0.02 + 0.10 * uStrength), uTexelSize * 1.5);
              processed = blurAt(uv, stride);
            } else {
              processed = pixelateAt(uv, r);
            }
            // Smooth transition from fully processed (inside) to untouched (feather distance).
            float mixAmount = 1.0 - smoothstep(0.0, max(uFeather, 1e-5), d);
            color = mix(color, processed, mixAmount);
          }
          gl_FragColor = vec4(color.rgb, original.a);
        }
    """.trimIndent()

    /** Fragment shader that samples a regular `sampler2D` (used by the Media3 export effect). */
    val FRAGMENT_SHADER_2D: String = FRAGMENT_SHADER_TEMPLATE
        .replace("%EXTENSION%", "")
        .replace("%SAMPLER_TYPE%", "sampler2D")
        .replace("%SAMPLE_FN%", "texture2D")

    /** Fragment shader that samples a `samplerExternalOES` (used by the live preview). */
    val FRAGMENT_SHADER_OES: String = FRAGMENT_SHADER_TEMPLATE
        .replace("%EXTENSION%", "#extension GL_OES_EGL_image_external : require")
        .replace("%SAMPLER_TYPE%", "samplerExternalOES")
        .replace("%SAMPLE_FN%", "texture2D")

    /**
     * Packs zones into the flat `vec4[MAX_ZONES]` uniform layout (minX, minY, maxX, maxY) in the
     * texture space the shader samples from.
     *
     * @param yUp true for the export path, where Media3 delivers frames with texture row 0 at the
     *   visual bottom (display y is flipped); false for the preview path, where a Bitmap is
     *   uploaded as-is (texture row 0 = visual top, no flip).
     */
    fun zoneUniforms(zones: List<WatermarkZone>, yUp: Boolean = true): FloatArray {
        val out = FloatArray(MAX_ZONES * 4)
        zones.take(MAX_ZONES).forEachIndexed { i, zone ->
            val r: NormalizedRect = zone.rect.sanitized()
            out[i * 4 + 0] = r.left
            out[i * 4 + 1] = if (yUp) 1f - r.bottom else r.top
            out[i * 4 + 2] = r.right
            out[i * 4 + 3] = if (yUp) 1f - r.top else r.bottom
        }
        return out
    }

    fun zoneCount(zones: List<WatermarkZone>): Int = zones.size.coerceAtMost(MAX_ZONES)

    /**
     * Shader method for [settings]. The reconstruction method switches to [METHOD_LAYER] when a
     * recovered watermark [layer] is available (the picture behind the logo is then restored).
     */
    fun methodId(settings: RemovalSettings, layer: WatermarkLayer? = null): Int = when {
        !settings.method.usesShader -> RemovalMethod.INPAINT.shaderId
        settings.method == RemovalMethod.INPAINT && layer != null && layer.hasWatermark -> METHOD_LAYER
        else -> settings.method.shaderId
    }

    /** Feather expressed in texture units, based on the smaller dimension so it looks isotropic. */
    fun featherTextureUnits(settings: RemovalSettings, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val minDim = minOf(width, height).toFloat()
        // The reconstruction is a seamless copy of the surroundings, so it only needs a few
        // pixels of anti-aliasing at the border; a wide feather would just smear the result.
        val pixels = if (methodId(settings) == RemovalMethod.INPAINT.shaderId) {
            INPAINT_EDGE_PIXELS
        } else {
            settings.featherFraction * minDim
        }
        // Convert pixel count to texture units using the larger axis so it's never bigger than
        // the fraction on either axis.
        return pixels / maxOf(width, height).toFloat()
    }
}
