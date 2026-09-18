// Entry point of the local `verify` harness for the file-provenance cleaner ported from the
// Exemple `.zip` (branch Exemple, upstream/watermarks-remover-main): runs the REAL app unit tests
// (NativeWatermarkCleanerTest, TextUnicodeCleanerTest) against the REAL app sources on the JVM,
// with no Android SDK and no Gradle. See verify/README.md.
//
// This is the proof that the cleaner is not a stub: every format cleaner (PNG/JPEG/WebP/GIF/BMP/
// TIFF/AVIF/HEIC/WAV/MP3/FLAC/MP4/ZIP/OOXML/PDF) is exercised here with byte-level assertions.

package com.souxch.watermarkremover.verify

import com.souxch.watermarkremover.cleaner.NativeWatermarkCleanerTest
import com.souxch.watermarkremover.cleaner.TextUnicodeCleanerTest
import kotlin.system.exitProcess

fun main() {
    val failed = JvmTestRunner.run(
        listOf(
            NativeWatermarkCleanerTest::class,
            TextUnicodeCleanerTest::class,
        ),
    )
    if (failed != 0) exitProcess(1)
}
