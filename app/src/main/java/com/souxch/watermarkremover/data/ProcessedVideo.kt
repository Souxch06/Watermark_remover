package com.souxch.watermarkremover.data

import com.souxch.watermarkremover.model.RemovalMethod
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the library: a video from which the watermark has been removed.
 *
 * The video file itself lives in the public gallery (MediaStore, `Movies/Watermark Remover`),
 * so the user keeps it even if the app is uninstalled. This record stores what we need to list,
 * open, share and describe it, plus a thumbnail path in the app's private storage.
 */
data class ProcessedVideo(
    val id: Long,
    /** MediaStore content URI (or a file URI on very old devices). */
    val uri: String,
    val displayName: String,
    /** Name of the original video, for display ("depuis IMG_1234.mp4"). */
    val sourceName: String,
    val createdAtMillis: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val method: RemovalMethod,
    val zoneCount: Int,
    /** Absolute path of the JPEG thumbnail in the app's files dir; may be null if it failed. */
    val thumbnailPath: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_URI, uri)
        put(KEY_DISPLAY_NAME, displayName)
        put(KEY_SOURCE_NAME, sourceName)
        put(KEY_CREATED_AT, createdAtMillis)
        put(KEY_DURATION, durationMs)
        put(KEY_WIDTH, width)
        put(KEY_HEIGHT, height)
        put(KEY_SIZE, sizeBytes)
        put(KEY_METHOD, method.name)
        put(KEY_ZONES, zoneCount)
        put(KEY_THUMB, thumbnailPath ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_DISPLAY_NAME = "name"
        private const val KEY_SOURCE_NAME = "source"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_DURATION = "durationMs"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_SIZE = "size"
        private const val KEY_METHOD = "method"
        private const val KEY_ZONES = "zones"
        private const val KEY_THUMB = "thumb"

        /** Returns null instead of throwing when a record is malformed, so one bad entry never hides the others. */
        fun fromJson(o: JSONObject): ProcessedVideo? = try {
            ProcessedVideo(
                id = o.getLong(KEY_ID),
                uri = o.getString(KEY_URI),
                displayName = o.optString(KEY_DISPLAY_NAME, "video.mp4"),
                sourceName = o.optString(KEY_SOURCE_NAME, ""),
                createdAtMillis = o.optLong(KEY_CREATED_AT, 0L),
                durationMs = o.optLong(KEY_DURATION, 0L),
                width = o.optInt(KEY_WIDTH, 0),
                height = o.optInt(KEY_HEIGHT, 0),
                sizeBytes = o.optLong(KEY_SIZE, 0L),
                method = runCatching { RemovalMethod.valueOf(o.optString(KEY_METHOD)) }.getOrDefault(RemovalMethod.INPAINT),
                zoneCount = o.optInt(KEY_ZONES, 1),
                thumbnailPath = if (o.isNull(KEY_THUMB)) null else o.optString(KEY_THUMB, null),
            )
        } catch (e: Exception) {
            null
        }

        fun listToJson(items: List<ProcessedVideo>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return JSONObject().put("version", 1).put("items", arr).toString()
        }

        fun listFromJson(text: String): List<ProcessedVideo> {
            if (text.isBlank()) return emptyList()
            return try {
                val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
