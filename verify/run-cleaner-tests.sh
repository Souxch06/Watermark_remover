#!/usr/bin/env bash
# Compiles the REAL ported provenance cleaner (app sources) together with its REAL unit tests and
# runs them on a plain JVM — no Android SDK, no Gradle. The Gradle/CI run stays authoritative; this
# is the offline proof that the cleaner shipped in the APK actually does the work.
#
# Requires: KOTLINC (kotlin compiler launcher) and JAVA_HOME pointing at a JDK 17+.
#   KOTLINC=~/kotlin-compiler/package/bin/kotlinc JAVA_HOME=/path/to/jdk ./verify/run-cleaner-tests.sh
#
# See verify/README.md for how to obtain both (npm pack kotlin-compiler@2.1.10, any JDK 17+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC="${KOTLINC:?set KOTLINC to the kotlin compiler launcher (e.g. package/bin/kotlinc)}"
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17+}"
JAVA="$JAVA_HOME/bin/java"
OUT="${OUT:-$(mktemp -d)}"
KOTLIN_REFLECT="$(cd "$(dirname "$KOTLINC")/../lib" && pwd)/kotlin-reflect.jar"

"$KOTLINC" \
  "$ROOT/app/src/main/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleaner.kt" \
  "$ROOT/app/src/main/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleaner.kt" \
  "$ROOT/app/src/test/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleanerTest.kt" \
  "$ROOT/app/src/test/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleanerTest.kt" \
  "$ROOT/verify/src/JvmTestHarness.kt" \
  "$ROOT/verify/src/JvmTestRunner.kt" \
  "$ROOT/verify/src/RunCleanerTests.kt" \
  -include-runtime -d "$OUT/cleaner-tests.jar"

"$JAVA" -cp "$OUT/cleaner-tests.jar:$KOTLIN_REFLECT" com.souxch.watermarkremover.verify.RunCleanerTestsKt

# Bonus: the same sources, printing before/after on hand-built samples (see AUDIT-ZIP.md).
"$KOTLINC" \
  "$ROOT/app/src/main/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleaner.kt" \
  "$ROOT/app/src/main/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleaner.kt" \
  "$ROOT/verify/src/DemoCleaner.kt" \
  -include-runtime -d "$OUT/demo-cleaner.jar"
"$JAVA" -Dstdout.encoding=UTF-8 -jar "$OUT/demo-cleaner.jar"
