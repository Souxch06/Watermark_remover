package com.souxch.watermarkremover.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release available on GitHub. */
data class AppUpdate(
    val version: String,
    /** Direct download URL of the APK asset. */
    val apkUrl: String,
    /** Web page of the release (fallback if no APK asset was found). */
    val releaseUrl: String,
    val sizeBytes: Long,
)

/**
 * Asks the GitHub Releases API whether a newer APK exists.
 *
 * - One small unauthenticated JSON request; no telemetry, nothing about the user is sent.
 * - Checked at most once every [CHECK_INTERVAL_MS] (cached result in between).
 * - Any failure (offline, rate-limited, malformed) is swallowed: updates are best effort.
 */
class UpdateChecker(context: Context, private val currentVersion: String) {

    private val prefs: SharedPreferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    suspend fun check(force: Boolean = false): AppUpdate? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) {
            return@withContext cached()
        }
        val update = runCatching { fetchLatest() }.getOrNull()
        prefs.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putString(KEY_CACHED, update?.let { toJson(it) })
            .apply()
        update
    }

    /** Remembers that the user dismissed [version]; it will not be offered again. */
    fun dismiss(version: String) {
        prefs.edit().putString(KEY_DISMISSED, version).apply()
    }

    fun isDismissed(version: String): Boolean = prefs.getString(KEY_DISMISSED, null) == version

    private fun cached(): AppUpdate? = prefs.getString(KEY_CACHED, null)?.let { runCatching { fromJson(it) }.getOrNull() }
        ?.takeIf { isNewer(it.version, currentVersion) }

    private fun fetchLatest(): AppUpdate? {
        val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WatermarkRemover/$currentVersion")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isBlank() || !isNewer(version, currentVersion)) return null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            val page = json.optString("html_url").ifBlank { RELEASES_PAGE }
            return AppUpdate(version, apkUrl ?: page, page, size)
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(u: AppUpdate) = JSONObject()
        .put("version", u.version).put("apk", u.apkUrl).put("page", u.releaseUrl).put("size", u.sizeBytes).toString()

    private fun fromJson(s: String): AppUpdate = JSONObject(s).let {
        AppUpdate(it.getString("version"), it.getString("apk"), it.getString("page"), it.optLong("size"))
    }

    companion object {
        const val REPO = "Souxch06/Watermark_remover"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_CACHED = "cached"
        private const val KEY_DISMISSED = "dismissed"

        /**
         * Semantic-ish version comparison: "1.10.0" > "1.9.2", suffixes like "-debug" ignored.
         * Returns true when [candidate] is strictly newer than [current].
         */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
