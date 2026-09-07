package com.souxch.watermarkremover.model

/**
 * Axis-aligned rectangle expressed in normalized display coordinates (0..1), origin at the
 * top-left corner of the *displayed* (rotation-corrected) video frame, y axis pointing down.
 *
 * Keeping everything normalized lets the UI, the preview renderer (which works on a downscaled
 * frame) and the exporter (which works on the full-resolution frame) share the same values.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Moves the rectangle, keeping it fully inside the unit square. */
    fun translated(dx: Float, dy: Float): NormalizedRect {
        val cdx = clampSafe(dx, -left, 1f - right)
        val cdy = clampSafe(dy, -top, 1f - bottom)
        return NormalizedRect(left + cdx, top + cdy, right + cdx, bottom + cdy)
    }

    /** Drags one corner by (dx, dy), enforcing the minimum size and the unit square bounds. */
    fun resized(corner: Corner, dx: Float, dy: Float): NormalizedRect {
        var l = left
        var t = top
        var r = right
        var b = bottom
        when (corner) {
            Corner.TOP_LEFT -> {
                l = clampSafe(l + dx, 0f, r - MIN_SIZE)
                t = clampSafe(t + dy, 0f, b - MIN_SIZE)
            }
            Corner.TOP_RIGHT -> {
                r = clampSafe(r + dx, l + MIN_SIZE, 1f)
                t = clampSafe(t + dy, 0f, b - MIN_SIZE)
            }
            Corner.BOTTOM_LEFT -> {
                l = clampSafe(l + dx, 0f, r - MIN_SIZE)
                b = clampSafe(b + dy, t + MIN_SIZE, 1f)
            }
            Corner.BOTTOM_RIGHT -> {
                r = clampSafe(r + dx, l + MIN_SIZE, 1f)
                b = clampSafe(b + dy, t + MIN_SIZE, 1f)
            }
        }
        return NormalizedRect(l, t, r, b)
    }

    /** Returns a well-formed rectangle inside the unit square with at least [MIN_SIZE] extents. */
    fun sanitized(): NormalizedRect {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        var r = maxOf(left, right).coerceIn(0f, 1f)
        var b = maxOf(top, bottom).coerceIn(0f, 1f)
        if (r - l < MIN_SIZE) r = (l + MIN_SIZE).coerceAtMost(1f)
        if (b - t < MIN_SIZE) b = (t + MIN_SIZE).coerceAtMost(1f)
        return NormalizedRect(l, t, r, b)
    }

    companion object {
        /** Smallest allowed width/height, as a fraction of the frame. */
        const val MIN_SIZE = 0.02f

        /** Like [coerceIn] but never throws when the range is inverted by rounding errors. */
        fun clampSafe(value: Float, min: Float, max: Float): Float =
            if (max < min) min else value.coerceIn(min, max)

        fun fromCenter(cx: Float, cy: Float, width: Float, height: Float): NormalizedRect =
            NormalizedRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
                .sanitized()
    }
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** A rectangular area of the frame that contains (part of) a watermark. */
data class WatermarkZone(val id: Int, val rect: NormalizedRect) {
    companion object {
        /** Bottom-right corner: by far the most common watermark location. */
        val DEFAULT_RECT = NormalizedRect(left = 0.60f, top = 0.86f, right = 0.97f, bottom = 0.97f)

        /** Centered rectangle used for additional zones; the user then drags it into place. */
        val ADDITIONAL_RECT = NormalizedRect(left = 0.35f, top = 0.44f, right = 0.65f, bottom = 0.56f)
    }
}

enum class RemovalMethod(
    /** Identifier understood by the fragment shader, or -1 when the method is not shader based. */
    val shaderId: Int,
) {
    /** Reconstructs the area from the pixels surrounding it. */
    INPAINT(0),

    /** Strong Gaussian blur restricted to the area. */
    BLUR(1),

    /** Mosaic / big pixels restricted to the area. */
    PIXELATE(2),

    /** Cuts the video so the area is no longer part of the frame. */
    CROP(-1);

    val usesShader: Boolean get() = shaderId >= 0
}

data class RemovalSettings(
    val method: RemovalMethod = RemovalMethod.INPAINT,
    /** 0..1 – meaning depends on the method (blur radius, block size, inpaint smoothness). */
    val strength: Float = 0.5f,
    /** 0..1 – softness of the transition around the zone. */
    val feather: Float = 0.35f,
) {
    /** Feather width as a fraction of the smallest frame dimension. */
    val featherFraction: Float get() = feather * MAX_FEATHER_FRACTION

    companion object {
        const val MAX_FEATHER_FRACTION = 0.03f
    }
}
