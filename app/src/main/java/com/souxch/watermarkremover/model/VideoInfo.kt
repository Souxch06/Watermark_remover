package com.souxch.watermarkremover.model

import android.net.Uri

/** Metadata about the source video, as read from the media container. */
data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    /** Encoded width, before applying [rotationDegrees]. */
    val width: Int,
    /** Encoded height, before applying [rotationDegrees]. */
    val height: Int,
    /** Container rotation hint: 0, 90, 180 or 270. */
    val rotationDegrees: Int,
    val durationMs: Long,
    val mimeType: String?,
    val sizeBytes: Long,
) {
    /** Width as seen by the user, once the rotation metadata has been applied. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 0) width else height

    /** Height as seen by the user, once the rotation metadata has been applied. */
    val displayHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    val aspectRatio: Float
        get() = if (displayHeight == 0) 16f / 9f else displayWidth.toFloat() / displayHeight.toFloat()
}

/**
 * Geometry helpers shared by the UI and the processing pipeline.
 *
 * Everything is pure (no Android dependencies) so it can be unit-tested on the JVM.
 */
object ZoneGeometry {

    /**
     * Computes the largest rectangle that stays inside the frame while fully excluding [zone].
     *
     * The zone touches at most two frame edges in practice (watermarks live in corners or on a
     * side). We evaluate the four candidate rectangles (above, below, left of, right of the zone)
     * and keep the one with the largest area. The result is normalized (0..1) in display space.
     */
    fun cropRectExcluding(zone: NormalizedRect): NormalizedRect {
        val z = zone.sanitized()
        val candidates = listOf(
            NormalizedRect(0f, 0f, 1f, z.top),      // keep everything above the zone
            NormalizedRect(0f, z.bottom, 1f, 1f),   // keep everything below the zone
            NormalizedRect(0f, 0f, z.left, 1f),     // keep everything left of the zone
            NormalizedRect(z.right, 0f, 1f, 1f),    // keep everything right of the zone
        )
        return candidates.maxByOrNull { it.width * it.height }!!
    }

    /**
     * Converts a normalized crop rectangle into the NDC (-1..1, y up) edges expected by
     * `androidx.media3.effect.Crop`. Returned as [left, right, bottom, top].
     */
    fun toNdcCrop(rect: NormalizedRect): FloatArray {
        val r = rect.sanitized()
        val left = r.left * 2f - 1f
        val right = r.right * 2f - 1f
        // Normalized y grows downwards, NDC y grows upwards.
        val top = 1f - r.top * 2f
        val bottom = 1f - r.bottom * 2f
        return floatArrayOf(left, right, bottom, top)
    }

    /**
     * Even-aligned output size after cropping [displayWidth] x [displayHeight] by [crop].
     * Video encoders require even dimensions.
     */
    fun croppedSize(displayWidth: Int, displayHeight: Int, crop: NormalizedRect): Pair<Int, Int> {
        val r = crop.sanitized()
        val w = (displayWidth * r.width).toInt().coerceAtLeast(2)
        val h = (displayHeight * r.height).toInt().coerceAtLeast(2)
        return (w - w % 2) to (h - h % 2)
    }
}
