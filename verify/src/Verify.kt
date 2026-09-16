@file:Suppress("UNUSED_PARAMETER")

package com.souxch.watermarkremover.verify
import com.souxch.watermarkremover.data.LibraryRepository
import com.souxch.watermarkremover.data.UpdateInstaller
import com.souxch.watermarkremover.data.UpdateChecker
import com.souxch.watermarkremover.data.ExportQuality

/**
 * Standalone checks for the logic that does not need the Android SDK. Mirrors the JUnit tests that
 * run in CI, so the same assertions can be run locally without Gradle.
 */
private var failures = 0

private fun check(what: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("ok   $what")
    } else {
        failures++
        println("FAIL $what ${if (detail.isNotEmpty()) "-> $detail" else ""}")
    }
}

fun main() {
    // --- guard: the local mirror must match the app sources it mirrors (see Mirror.kt) ---
    val drift = VerifyMirrorSync.drift()
    drift.forEach { println("FAIL $it") }
    if (drift.isNotEmpty()) {
        println("${drift.size} mirrored declaration(s) have drifted — refusing to run stale checks.")
        throw AssertionError("mirror drift: ${drift.size} declaration(s)")
    }
    println("ok   mirror in sync with the app sources")

    // --- file names derived from untrusted input (picker name / GitHub release version) ---
    for (hostile in listOf("../../evil.mp4", "..\\..\\evil.mp4", "/sdcard/evil.mp4", "a/b/c.mp4", "a\u0000b.mp4", "..", "...")) {
        val name = LibraryRepository.buildDisplayName(hostile)
        check(
            "display name for ${hostile.replace("\u0000", "\\0")}",
            !name.contains('/') && !name.contains('\\') && !name.contains("..") && name.endsWith(".mp4"),
            name,
        )
    }
    check("unicode name survives", LibraryRepository.sanitize("vidéo été") == "vidéo été")
    check("clean name is kept", LibraryRepository.buildDisplayName("IMG_1234.mp4") == "IMG_1234_sans_filigrane.mp4")
    check("blank name falls back", LibraryRepository.buildDisplayName("") == "video_sans_filigrane.mp4")
    check("extension is replaced", LibraryRepository.buildDisplayName("clip.MOV") == "clip_sans_filigrane.mp4")
    check("sanitize is bounded", LibraryRepository.sanitize("x".repeat(500)).length <= 96)
    check("sanitize never blank", LibraryRepository.sanitize("...") == "video")

    check("update file name is safe", UpdateInstaller.safeFileName("../../evil") == "update", UpdateInstaller.safeFileName("../../evil"))
    check("update version is kept", UpdateInstaller.safeFileName("v1.4.0") == "1.4.0", UpdateInstaller.safeFileName("v1.4.0"))
    check("update file name is bounded", UpdateInstaller.safeFileName("9".repeat(80)).length == 32)
    check("dots cannot escape", !UpdateInstaller.safeFileName("...").contains(".."))

    // --- version comparison used to decide whether an update is offered ---
    check("newer version detected", UpdateChecker.isNewer("1.10.0", "1.9.9"))
    check("debug suffix ignored", UpdateChecker.isNewer("1.3.1", "1.3.0-debug"))
    check("same version is not newer", !UpdateChecker.isNewer("1.3.0", "1.3.0"))
    check("garbage version is not newer", !UpdateChecker.isNewer("garbage", "1.3.0"))

    // --- encoder bitrate stays inside the supported range ---
    val capped = ExportQuality.MAXIMUM.targetBitrate(7680, 4320, 60f, Int.MAX_VALUE)
    check("bitrate is capped", capped <= ExportQuality.MAX_BITRATE.toInt(), capped.toString())
    check("bitrate has a floor", ExportQuality.MAXIMUM.targetBitrate(16, 16, 0f, 0) == ExportQuality.MIN_BITRATE.toInt())

    println(if (failures == 0) "\nall checks passed" else "\n$failures check(s) failed")
    if (failures != 0) throw AssertionError("$failures check(s) failed")
}
