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
    const val A_FRAME_POSITION = "aFramePosition"

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

        // Small 3x3 tent blur used to split a sample into low / high frequencies.
        vec4 blurSmall(vec2 uv) {
          vec2 s = uTexelSize * 1.5;
          vec4 acc = sampleTex(uv) * 4.0;
          acc += (sampleTex(uv + vec2(s.x, 0.0)) + sampleTex(uv - vec2(s.x, 0.0))
                + sampleTex(uv + vec2(0.0, s.y)) + sampleTex(uv - vec2(0.0, s.y))) * 2.0;
          acc += sampleTex(uv + s) + sampleTex(uv - s)
               + sampleTex(uv + vec2(s.x, -s.y)) + sampleTex(uv + vec2(-s.x, s.y));
          return acc / 16.0;
        }

        // High-frequency content (grain, texture, fine edges) at uv.
        vec3 detailAt(vec2 uv) {
          return sampleTex(uv).rgb - blurSmall(uv).rgb;
        }

        // Frequency-separation inpainting.
        //  * LOW frequencies (tones, gradients) come from a smooth blend of the colours found just
        //    outside each edge of the zone (blurred along the edge, weighted by inverse squared
        //    distance), so the fill always matches its surroundings without a visible seam.
        //  * HIGH frequencies (grain, texture) are borrowed from the real pixels outside the zone,
        //    mirrored across the nearest edges - slightly compressed and skewed so that large zones
        //    do not show an obvious mirror image. Adding them back is what keeps the result from
        //    looking blurry.
        // Directions whose source would fall outside the frame (zone touching a border) fade out.
        vec4 inpaintAt(vec2 uv, vec4 r) {
          vec2 zoneSize = vec2(r.z - r.x, r.w - r.y);
          vec2 margin = uTexelSize * 4.0;
          float dl = uv.x - r.x;
          float dr = r.z - uv.x;
          float db = uv.y - r.y;
          float dt = r.w - uv.y;

          // Mirrored source positions (k < 1 compresses, the perpendicular term skews).
          float k = 0.8;
          float skew = 0.25;
          vec2 pl = vec2(r.x - margin.x - dl * k, uv.y + dl * skew);
          vec2 pr = vec2(r.z + margin.x + dr * k, uv.y - dr * skew);
          vec2 pb = vec2(uv.x + db * skew, r.y - margin.y - db * k);
          vec2 pt = vec2(uv.x - dt * skew, r.w + margin.y + dt * k);

          // Validity of each direction (smooth so no seam appears where a source leaves the frame).
          float vl = smoothstep(0.0, 0.05, pl.x);
          float vr = 1.0 - smoothstep(0.95, 1.0, pr.x);
          float vb = smoothstep(0.0, 0.05, pb.y);
          float vt = 1.0 - smoothstep(0.95, 1.0, pt.y);

          float wl = vl / (dl * dl + 1e-5);
          float wr = vr / (dr * dr + 1e-5);
          float wb = vb / (db * db + 1e-5);
          float wt = vt / (dt * dt + 1e-5);
          float wsum = wl + wr + wb + wt;
          if (wsum <= 1e-4) {
            return blurAt(uv, uTexelSize * 8.0);
          }

          // Low frequencies: colours just outside each edge, blurred mostly ALONG the edge (kills
          // streaks) and kept fully outside the zone (never samples the watermark itself).
          vec2 sLR = vec2(uTexelSize.x * 1.5, max(zoneSize.y * 0.03, uTexelSize.y * 1.5));
          vec2 sTB = vec2(max(zoneSize.x * 0.03, uTexelSize.x * 1.5), uTexelSize.y * 1.5);
          vec2 off = margin + uTexelSize * 6.0;
          vec4 base = (blurAt(vec2(r.x - off.x, uv.y), sLR) * wl
                     + blurAt(vec2(r.z + off.x, uv.y), sLR) * wr
                     + blurAt(vec2(uv.x, r.y - off.y), sTB) * wb
                     + blurAt(vec2(uv.x, r.w + off.y), sTB) * wt) / wsum;

          // High frequencies: real texture borrowed from the mirrored sources.
          vec3 detail = (detailAt(pl) * wl + detailAt(pr) * wr
                       + detailAt(pb) * wb + detailAt(pt) * wt) / wsum;

          float amount = clamp(uStrength * 1.4, 0.0, 1.0);
          return vec4(clamp(base.rgb + detail * amount, 0.0, 1.0), base.a);
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
            if (d > uFeather) { continue; }
            vec4 processed;
            if (uMethod == 0) {
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

    fun methodId(settings: RemovalSettings): Int =
        if (settings.method.usesShader) settings.method.shaderId else RemovalMethod.INPAINT.shaderId

    /** Feather expressed in texture units, based on the smaller dimension so it looks isotropic. */
    fun featherTextureUnits(settings: RemovalSettings, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val minDim = minOf(width, height).toFloat()
        val pixels = settings.featherFraction * minDim
        // Convert pixel count to texture units using the larger axis so it's never bigger than
        // the fraction on either axis.
        return pixels / maxOf(width, height).toFloat()
    }
}
