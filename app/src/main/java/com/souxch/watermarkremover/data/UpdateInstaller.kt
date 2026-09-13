package com.souxch.watermarkremover.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Downloads an APK with the system DownloadManager and hands it to the package installer.
 * Because every release is signed with the same key, the installer performs an in-place update:
 * the user keeps the app data and does not have to uninstall anything.
 */
class UpdateInstaller(private val context: Context) {

    private var receiver: BroadcastReceiver? = null

    /** True when the app may open the installer directly (Android 8+ needs a per-app permission). */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates (Android 8+). */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /** Fallback: let the browser download the APK from the release page. */
    fun openInBrowser(update: AppUpdate) {
        // Opens the release page, never a bare asset URL: the browser owns the download, and the
        // page is where the user can read what the update is.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Starts the download; [onDone] is called on the main thread with `true` when the installer
     * was launched, `false` on failure (caller should fall back to [openInBrowser]).
     */
    fun downloadAndInstall(update: AppUpdate, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            onDone(false)
            return
        }
        val fileName = "WatermarkRemover-${safeFileName(update.version)}.apk"
        // App-specific external dir: no storage permission needed and FileProvider can share it.
        // `filesDir` is preferred: it is private, so no other app can swap the file before the
        // installer reads it (the external app dir is world-readable/writable on old API levels).
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, fileName).apply { if (exists()) delete() }

        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Watermark Remover ${update.version}")
            .setMimeType(APK_MIME)
            // Only https, never a cleartext redirect chain, and no file:// destination from the
            // download server side.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            onDone(false)
            return
        }

        unregister()
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                unregister()
                val ok = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                } ?: false
                if (!ok || !target.exists()) {
                    onDone(false)
                    return
                }
                // The APK is about to be handed to the installer, so its integrity is checked on a
                // background thread against the digest published next to it (release workflow
                // uploads `<apk>.sha256`). A mismatch, or a download that is not an archive at all,
                // is treated as a failure and the user falls back to the browser page.
                Thread {
                    val verified = verifyApk(target, update.apkSha256)
                    mainHandler.post {
                        if (!verified) target.delete()
                        onProgress(100)
                        onDone(verified && launchInstaller(target))
                    }
                }.start()
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context, r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED,
        )

        // Lightweight progress polling (DownloadManager has no callback for it).
        Thread {
            var running = true
            while (running && receiver === r) {
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) mainHandler.post { onProgress((done * 100 / total).toInt().coerceIn(0, 99)) }
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) running = false
                    } else {
                        running = false
                    }
                }
                try { Thread.sleep(500) } catch (e: InterruptedException) { running = false }
            }
        }.start()
    }

    private fun launchInstaller(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Integrity gate before anything is handed to the package installer:
     *  - the file must really be a ZIP (an APK), whatever the server answered,
     *  - when the release publishes a SHA-256 it must match byte for byte.
     * Signature verification itself is left to Android, which refuses to update the app with an
     * APK signed by another key.
     */
    private fun verifyApk(file: File, expectedSha256: String?): Boolean = runCatching {
        if (file.length() < MIN_APK_BYTES) return@runCatching false
        if (!isZipArchive(file)) return@runCatching false
        expectedSha256 == null || sha256(file).equals(expectedSha256, ignoreCase = true)
    }.getOrDefault(false)

    private fun isZipArchive(file: File): Boolean = file.inputStream().use { input ->
        val magic = ByteArray(2)
        input.read(magic) == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Anything smaller than this cannot be an APK (guards against an HTML error page). */
        private const val MIN_APK_BYTES = 64L * 1024
        /**
         * Keeps the version taken from the release API from escaping the download directory. Only
         * digits and dots survive, each group of dots collapses to one, and a basename that is not a
         * plain version number falls back to "update".
         */
        internal fun safeFileName(version: String): String = version
            .removePrefix("v")
            .replace(Regex("[^0-9.]"), "")
            .replace(Regex("\\.+"), ".")
            .trim('.')
            .take(32)
            .trim('.')
            .ifBlank { "update" }
    }
}
