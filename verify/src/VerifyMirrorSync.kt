// Guards the local mirror against silently drifting from the app it is supposed to test.
// `verify/src/Mirror.kt` holds copies of pure functions whose real definitions live inside files
// that also depend on the Android SDK (so they cannot be compiled on a plain JVM). The README
// promises these copies are checked against the originals; this object makes that promise real:
// every mirrored declaration must be character-identical to the one in the app source, otherwise
// the run fails instead of testing stale code.

package com.souxch.watermarkremover.verify

import java.io.File

object VerifyMirrorSync {

    data class Mirror(
        val name: String,
        val realPath: String,
        val anchor: String,
    )

    private val mirrors = listOf(
        Mirror("LibraryRepository.sanitize", "app/src/main/java/com/souxch/watermarkremover/data/LibraryRepository.kt", "internal fun sanitize"),
        Mirror("UpdateInstaller.safeFileName", "app/src/main/java/com/souxch/watermarkremover/data/UpdateInstaller.kt", "internal fun safeFileName"),
        Mirror("UpdateChecker.isNewer", "app/src/main/java/com/souxch/watermarkremover/data/UpdateChecker.kt", "fun isNewer"),
        Mirror("ExportQuality", "app/src/main/java/com/souxch/watermarkremover/model/Zones.kt", "enum class ExportQuality"),
    )

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, ".git").exists()) dir = dir.parentFile
        dir ?: File(System.getProperty("user.dir")).absoluteFile
    }

    /**
     * Returns the list of mirrors that are NOT character-identical to their real source.
     * Empty list = everything matches.
     */
    fun drift(): List<String> {
        val drift = ArrayList<String>()
        val mirror = read("verify/src/Mirror.kt")
        if (mirror == null) return listOf("verify/src/Mirror.kt could not be read")
        for (m in mirrors) {
            val real = read(m.realPath)
            val realDecl = real?.let { SourceExtract.declaration(it, m.anchor) }
            val mirrDecl = mirror?.let { SourceExtract.declaration(it, m.anchor) }
            val ok = real != null && realDecl != null && SourceExtract.equivalent(realDecl, mirrDecl)
            if (!ok) {
                drift.add("${m.name}: verify/src/Mirror.kt differs from ${m.realPath} — update the mirror or revert the source change")
            }
        }
        return drift
    }

    private fun read(path: String): String? = runCatching { File(root, path).readText() }.getOrNull()
}
