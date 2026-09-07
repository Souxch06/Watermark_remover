package com.souxch.watermarkremover.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.souxch.watermarkremover.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Reads source video metadata and extracts preview frames. */
class VideoRepository(private val context: Context) {

    suspend fun readInfo(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) throw IOException("No video track")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val (name, size) = queryNameAndSize(uri)
            VideoInfo(uri, name, width, height, ((rotation % 360) + 360) % 360, duration, mime, size)
        } finally {
            retriever.release()
        }
    }

    /** Frame used as the editing background; already rotated as displayed by players. */
    suspend fun loadFrame(uri: Uri, timeUs: Long, maxDimension: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            val scale = maxDimension.toFloat() / maxOf(full.width, full.height)
            if (scale >= 1f) full
            else Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                .also { if (it !== full) full.recycle() }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    return (name ?: "video.mp4") to size
                }
            }
        return (uri.lastPathSegment ?: "video.mp4") to 0L
    }

    /** Temporary file the Transformer writes to (it needs a real file path). */
    fun newExportFile(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "export_${System.currentTimeMillis()}.mp4")
    }

}
