package com.souxch.watermarkremover.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        val fileName = "WatermarkRemover-${update.version}.apk"
        // App-specific external dir: no storage permission needed and FileProvider can share it.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val target = File(dir, fileName).apply { if (exists()) delete() }

        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Watermark Remover ${update.version}")
            .setMimeType(APK_MIME)
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
                if (ok && target.exists()) {
                    onProgress(100)
                    onDone(launchInstaller(target))
                } else {
                    onDone(false)
                }
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

    fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
