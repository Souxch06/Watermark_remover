package com.souxch.watermarkremover.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.souxch.watermarkremover.model.RemovalMethod
import com.souxch.watermarkremover.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Persistent library of processed videos.
 *
 * - The video files live in the public gallery (`Movies/Watermark Remover`) via MediaStore.
 * - The index (`files/library/index.json`) and the thumbnails (`files/library/thumbs/<id>.jpg`)
 *   live in app-private storage. JSON + atomic file replace keeps the dependency footprint at
 *   zero while being robust to crashes mid-write.
 * - On load, entries whose gallery file was deleted by the user (from another app) are pruned.
 */
class LibraryRepository(private val context: Context) {

    private val dir = File(context.filesDir, "library")
    private val indexFile = File(dir, "index.json")
    private val thumbsDir = File(dir, "thumbs")
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<ProcessedVideo>>(emptyList())
    val items: StateFlow<List<ProcessedVideo>> = _items

    /** Loads the index from disk and drops entries whose video no longer exists. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = readIndex()
            val alive = stored.filter { exists(Uri.parse(it.uri)) }
            if (alive.size != stored.size) {
                stored.filterNot { alive.contains(it) }.forEach { deleteThumb(it) }
                writeIndex(alive)
            }
            _items.value = alive.sortedByDescending { it.createdAtMillis }
        }
    }

    /**
     * Moves [file] into the gallery, generates a thumbnail and records the entry.
     * The temporary [file] is always deleted afterwards.
     */
    suspend fun saveExport(
        file: File,
        source: VideoInfo,
        method: RemovalMethod,
        zoneCount: Int,
    ): ProcessedVideo = withContext(Dispatchers.IO) {
        val displayName = buildDisplayName(source.displayName)
        try {
            val meta = readMeta(file)
            val uri = insertIntoGallery(file, displayName)
            val id = System.currentTimeMillis()
            val thumb = runCatching { writeThumb(file, id) }.getOrNull()
            val entry = ProcessedVideo(
                id = id,
                uri = uri.toString(),
                displayName = displayName,
                sourceName = source.displayName,
                createdAtMillis = id,
                durationMs = meta.durationMs.takeIf { it > 0 } ?: source.durationMs,
                width = meta.width,
                height = meta.height,
                sizeBytes = file.length(),
                method = method,
                zoneCount = zoneCount,
                thumbnailPath = thumb?.absolutePath,
            )
            mutex.withLock {
                val updated = listOf(entry) + readIndex().filterNot { it.id == entry.id }
                writeIndex(updated)
                _items.value = updated.sortedByDescending { it.createdAtMillis }
            }
            entry
        } finally {
            file.delete()
        }
    }

    /** Deletes the entry, its thumbnail and (best effort) the gallery file. */
    suspend fun delete(item: ProcessedVideo) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(Uri.parse(item.uri), null, null) }
        deleteThumb(item)
        mutex.withLock {
            val updated = readIndex().filterNot { it.id == item.id }
            writeIndex(updated)
            _items.value = updated.sortedByDescending { it.createdAtMillis }
        }
    }

    suspend fun rename(item: ProcessedVideo, newName: String) = withContext(Dispatchers.IO) {
        val clean = newName.trim().ifBlank { return@withContext }
        val finalName = if (clean.endsWith(".mp4", ignoreCase = true)) clean else "$clean.mp4"
        runCatching {
            context.contentResolver.update(
                Uri.parse(item.uri),
                ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, finalName) },
                null, null,
            )
        }
        mutex.withLock {
            val updated = readIndex().map { if (it.id == item.id) it.copy(displayName = finalName) else it }
            writeIndex(updated)
            _items.value = updated.sortedByDescending { it.createdAtMillis }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun readIndex(): List<ProcessedVideo> =
        if (!indexFile.exists()) emptyList() else ProcessedVideo.listFromJson(indexFile.readText())

    private fun writeIndex(items: List<ProcessedVideo>) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ProcessedVideo.listToJson(items))
        if (!tmp.renameTo(indexFile)) {
            // Fallback for filesystems where rename over an existing file fails.
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    private fun exists(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private data class Meta(val width: Int, val height: Int, val durationMs: Long)

    private fun readMeta(file: File): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot % 180 == 0) Meta(w, h, d) else Meta(h, w, d)
        } catch (e: Exception) {
            Meta(0, 0, 0L)
        } finally {
            r.release()
        }
    }

    private fun writeThumb(file: File, id: Long): File {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            val frame = r.getFrameAtTime(durationUs / 4, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("no frame")
            val scale = THUMB_MAX_DIM.toFloat() / maxOf(frame.width, frame.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            } else frame
            thumbsDir.mkdirs()
            val out = File(thumbsDir, "$id.jpg")
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (scaled !== frame) frame.recycle()
            scaled.recycle()
            return out
        } finally {
            r.release()
        }
    }

    private fun deleteThumb(item: ProcessedVideo) {
        item.thumbnailPath?.let { File(it).delete() }
    }

    private fun insertIntoGallery(file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val albumDir = File(moviesDir, ALBUM).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(albumDir, displayName).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                ?: throw IOException("Cannot open output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    companion object {
        const val ALBUM = "Watermark Remover"
        private const val THUMB_MAX_DIM = 512

        /** `IMG_1234.mp4` -> `IMG_1234_sans_filigrane.mp4` (no double extension, never blank). */
        fun buildDisplayName(sourceName: String): String {
            val base = sourceName.substringBeforeLast('.').ifBlank { "video" }
            return "${base}_sans_filigrane.mp4"
        }
    }
}
