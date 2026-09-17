package com.souxch.watermarkremover.cleaner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** A file selected through Android's Storage Access Framework. */
data class FileSelection(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val kind: CleanFileKind,
)

data class CleanedFileArtifact(
    val file: File,
    val selection: FileSelection,
    val report: NativeCleanReport,
    val outputName: String,
)

/**
 * Bridges the pure byte cleaner to Android's content providers.
 *
 * No broad storage permission is needed: the picker grants access to the one
 * selected URI. Results are written to Downloads/Watermark Remover through
 * MediaStore on Android 10+ and to the app's external Downloads directory on
 * older releases, where FileProvider can still share the result safely.
 */
class FileCleanerRepository(private val context: Context) {
    companion object {
        private const val MAX_INPUT_BYTES = 64L * 1024L * 1024L
        private const val ALBUM = "Watermark Remover"
    }

    suspend fun inspect(uri: Uri): FileSelection = withContext(Dispatchers.IO) {
        val (name, size, mime) = query(uri)
        val head = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(4096)
            val count = input.read(buffer)
            if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        } ?: byteArrayOf()
        FileSelection(uri, name, mime, size, NativeWatermarkCleaner.classify(name, mime, head))
    }

    suspend fun clean(selection: FileSelection): CleanedFileArtifact = withContext(Dispatchers.IO) {
        val bytes = readAll(selection.uri, selection.sizeBytes)
        val output = NativeWatermarkCleaner.clean(selection.displayName, selection.mimeType, bytes)
        val dir = File(context.cacheDir, "file-cleaner").apply { mkdirs() }
        val temp = File(dir, "clean_${System.nanoTime()}_${safeBase(selection.displayName)}")
        temp.outputStream().use { it.write(output.bytes) }
        CleanedFileArtifact(
            file = temp,
            selection = selection,
            report = output.report,
            outputName = cleanedName(selection.displayName),
        )
    }

    suspend fun save(artifact: CleanedFileArtifact): Uri = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(artifact)
        } else {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val dir = File(root, ALBUM).apply { mkdirs() }
            val destination = uniqueFile(dir, artifact.outputName)
            artifact.file.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
        }.also {
            artifact.file.delete()
        }
    }

    fun discard(artifact: CleanedFileArtifact?) {
        artifact?.file?.delete()
    }

    private fun saveToMediaStore(artifact: CleanedFileArtifact): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, artifact.outputName)
            put(MediaStore.Downloads.MIME_TYPE, artifact.selection.mimeType ?: guessMime(artifact.outputName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ALBUM)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Impossible de créer le fichier de sortie")
        try {
            resolver.openOutputStream(uri)?.use { out -> artifact.file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Impossible d'écrire le fichier de sortie")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun readAll(uri: Uri, declaredSize: Long): ByteArray {
        if (declaredSize > MAX_INPUT_BYTES) {
            throw IOException("Fichier trop volumineux pour le nettoyage hors ligne (limite 64 Mo)")
        }
        val output = ByteArrayOutputStreamWithLimit(MAX_INPUT_BYTES)
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } ?: throw IOException("Impossible de lire le fichier sélectionné")
        return output.toByteArray()
    }

    private fun query(uri: Uri): Triple<String, Long, String?> {
        var name: String? = null
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "fichier")
        return Triple(displayName, size, context.contentResolver.getType(uri))
    }

    private fun cleanedName(original: String): String {
        val extension = original.substringAfterLast('.', "").lowercase(Locale.US)
        val base = safeBase(original)
        return if (extension.isBlank()) "${base}_cleaned" else "${base}_cleaned.$extension"
    }

    private fun safeBase(name: String): String {
        val value = name.substringBeforeLast('.', name)
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
            .replace("..", "")
            .trim()
            .trimStart('.')
            .take(80)
        return value.ifBlank { "file" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".${it}" }
        for (i in 2..999) {
            val candidate = File(dir, "${base}_$i$ext")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Trop de fichiers portant le même nom")
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "mp4", "mov", "m4v" -> "video/mp4"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private class ByteArrayOutputStreamWithLimit(private val limit: Long) : java.io.ByteArrayOutputStream() {
        override fun write(b: Int) {
            check(size().toLong() < limit) { "Fichier trop volumineux pour le nettoyage hors ligne (limite 64 Mo)" }
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(size().toLong() + len <= limit) { "Fichier trop volumineux pour le nettoyage hors ligne (limite 64 Mo)" }
            super.write(b, off, len)
        }
    }
}
