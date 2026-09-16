// Entry point of the local `verify` harness for the pure processing/model core: runs the REAL
// app unit tests (ZoneGeometryTest, WatermarkAnalyzerTest) against the REAL app sources on the
// JVM, without the Android SDK. See verify/README.md.

package com.souxch.watermarkremover.verify

import com.souxch.watermarkremover.model.ZoneGeometryTest
import com.souxch.watermarkremover.processing.WatermarkAnalyzerTest
import kotlin.system.exitProcess

fun main() {
    val failed = JvmTestRunner.run(
        listOf(
            ZoneGeometryTest::class,
            WatermarkAnalyzerTest::class,
        ),
    )
    if (failed != 0) exitProcess(1)
}
