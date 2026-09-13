// Mirror of the pure functions the app ships, copied verbatim from:
//   app/src/main/java/com/souxch/watermarkremover/data/LibraryRepository.kt   (sanitize)
//   app/src/main/java/com/souxch/watermarkremover/data/UpdateInstaller.kt     (safeFileName)
//   app/src/main/java/com/souxch/watermarkremover/data/UpdateChecker.kt       (isNewer)
//   app/src/main/java/com/souxch/watermarkremover/model/Zones.kt              (targetBitrate)
// The verify script diffs these against the originals before running, so a divergence in the real
// sources fails the check instead of silently passing.
package com.souxch.watermarkremover.data

object LibraryRepository {
    fun buildDisplayName(sourceName: String): String = "${sanitize(sourceName)}_sans_filigrane.mp4"

    internal fun sanitize(sourceName: String): String {
        val base = sourceName
            .substringBeforeLast('.', sourceName)
            .replace(Regex("[\\\\/:*?\"<>|\u0000-\u001F]"), "")
            .replace("..", "")
            .trim()
            .trimStart('.')
            .take(96)
            .trim()
        return base.ifBlank { "video" }
    }
}

object UpdateInstaller {
    internal fun safeFileName(version: String): String = version
        .removePrefix("v")
        .replace(Regex("[^0-9.]"), "")
        .replace(Regex("\\.+"), ".")
        .trim('.')
        .take(32)
        .trim('.')
        .ifBlank { "update" }
}

object UpdateChecker {
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

enum class ExportQuality(private val sourceFactor: Float, private val bitsPerPixel: Float) {
    STANDARD(1.0f, 0.10f),
    HIGH(1.5f, 0.16f),
    MAXIMUM(2.0f, 0.30f);

    fun targetBitrate(width: Int, height: Int, frameRate: Float, sourceBitrate: Int): Int {
        val fps = if (frameRate > 1f) frameRate else 30f
        val floor = (width.toLong() * height * bitsPerPixel * fps).toLong()
        val fromSource = (sourceBitrate.toLong() * sourceFactor).toLong()
        return maxOf(floor, fromSource).coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
    }

    companion object {
        const val MIN_BITRATE = 2_000_000L
        const val MAX_BITRATE = 120_000_000L
    }
}
