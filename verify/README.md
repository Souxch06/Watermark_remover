# Local verification harness

Checks the parts of the app that do not need the Android SDK, so they can be run on a machine
without Android Studio. The JUnit tests in `app/src/test/...` remain the authoritative ones and run
in CI on every push via `./gradlew testDebugUnitTest`; this harness runs the **same test files**
against the **real app sources** on a plain JVM, including a guard that the old `verify/src/Mirror.kt`
copies cannot silently drift from the code they mirror.

## What runs

| Entry point | What it tests | Sources compiled |
|---|---|---|
| `verify/src/Verify.kt` | file-name sanitisation, version comparison, update-file safety, encoder-bitrate bounds, **mirror-sync guard** | `Mirror.kt` + the copies it holds |
| `verify/src/RunCoreTests.kt` | the entire watermark analysis + restoration pipeline (mask/opacity/inversion/motion fill/tracking) and the zone geometry — using the real `WatermarkAnalyzer`, `RegionRestorer`, `WatermarkLayer`, `WatermarkShader`, `Zones`, `VideoInfo` | the genuine `app/src/main/...` files, the genuine `app/src/test/...` test files |
| `verify/src/RunCleanerTests.kt` | the **file-provenance cleaner ported from the `Exemple` `.zip`**: byte-level assertions for PNG/JPEG/WebP/GIF/BMP/TIFF/AVIF/HEIC/WAV/MP3/FLAC/MP4/ZIP+OOXML/PDF and the Unicode text pass | the genuine `cleaner/NativeWatermarkCleaner.kt`, `cleaner/TextUnicodeCleaner.kt` and their genuine unit tests |
| `verify/src/DemoCleaner.kt` | not a test: prints the before/after of the cleaner on hand-built samples (Unicode carriers, PNG chunk, MP4 `uuid` C2PA box, DOCX props, and a **clean** MP4 that must come out unchanged) | the two genuine cleaner sources |
| `AppUpdateTest` (in CI) | update JSON parsing | covered by Gradle only — it needs a real URL connection |

`verify/src/Mirror.kt` is a copy of a few pure functions whose real definitions live in files that
also import the Android SDK (`LibraryRepository`, `UpdateInstaller`, `UpdateChecker`, `ExportQuality`):
those files cannot compile on a plain JVM, so the harness compiles the copy. `VerifyMirrorSync`
compares each copy against the real source (ignoring whitespace/comments only) and fails the run on
any divergence instead of silently testing stale code.

## Toolchain (one-time, no Android SDK, no Gradle)

Nothing here needs the Android SDK. You only need:
1. **A JDK 17+** — the compiler is run on it. (The sandbox used `pip install jdk4py`, which is a
   runtime-only JDK; `kotlinc` below only needs a JVM, not `javac`.)
2. **A Kotlin compiler** — `kotlin-compiler` on npm ships the full JVM compiler:
   ```bash
   npm pack kotlin-compiler@2.1.10   # same version as gradle/libs.versions.toml
   tar xzf kotlin-compiler-2.1.10.tgz
   KOTLINC="$PWD/package/bin/kotlinc"
   ```

## Run the checks

```bash
# 0) one-time setup (paths below are examples)
JAVA_HOME=/path/to/jdk
KOTLINC=/path/to/package/bin/kotlinc

# 1) the mirror + pure-functions harness (also runs the mirror-sync guard first)
"$KOTLINC" verify/src/Mirror.kt verify/src/Verify.kt \
  verify/src/SourceExtract.kt verify/src/VerifyMirrorSync.kt \
  -include-runtime -d verify-standalone.jar
java -jar verify-standalone.jar

# 2) the FULL processing core: real app sources + real unit tests (no mocks)
"$KOTLINC" \
  app/src/main/java/com/souxch/watermarkremover/model/Zones.kt \
  app/src/main/java/com/souxch/watermarkremover/model/VideoInfo.kt \
  app/src/main/java/com/souxch/watermarkremover/processing/WatermarkShader.kt \
  app/src/main/java/com/souxch/watermarkremover/processing/WatermarkLayer.kt \
  app/src/main/java/com/souxch/watermarkremover/processing/WatermarkAnalyzer.kt \
  app/src/main/java/com/souxch/watermarkremover/processing/RegionRestorer.kt \
  verify/src/android/net/Uri.kt \
  app/src/test/java/com/souxch/watermarkremover/model/ZoneGeometryTest.kt \
  app/src/test/java/com/souxch/watermarkremover/processing/WatermarkAnalyzerTest.kt \
  verify/src/JvmTestHarness.kt verify/src/JvmTestRunner.kt verify/src/RunCoreTests.kt \
  -include-runtime -d core-tests.jar
java -jar core-tests.jar   # expect: "28 test(s), 0 failure(s)"
```

```bash
# 3) the ported provenance cleaner: real cleaner sources + their real unit tests
"$KOTLINC" \
  app/src/main/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleaner.kt \
  app/src/main/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleaner.kt \
  app/src/test/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleanerTest.kt \
  app/src/test/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleanerTest.kt \
  verify/src/JvmTestHarness.kt verify/src/JvmTestRunner.kt verify/src/RunCleanerTests.kt \
  -include-runtime -d cleaner-tests.jar
java -cp cleaner-tests.jar:$KOTLINC_DIR/../lib/kotlin-reflect.jar \
  com.souxch.watermarkremover.verify.RunCleanerTestsKt   # expect: "19 test(s), 0 failure(s)"

# 4) optional: see what the cleaner does to a marked file (and to a clean one)
"$KOTLINC" \
  app/src/main/java/com/souxch/watermarkremover/cleaner/NativeWatermarkCleaner.kt \
  app/src/main/java/com/souxch/watermarkremover/cleaner/TextUnicodeCleaner.kt \
  verify/src/DemoCleaner.kt -include-runtime -d demo-cleaner.jar
java -Dstdout.encoding=UTF-8 -jar demo-cleaner.jar
```

`JvmTestRunner` decides the receiver per test method: a class such as `NativeWatermarkCleanerTest`
declares its helpers in a `companion object`, and the companion is not a valid receiver for the
instance test methods.

`verify/src/android/net/Uri.kt` is a local-only stand-in for `android.net.Uri` so `VideoInfo.kt`
compiles; the tested paths never call it. `JvmTestHarness.kt` is a minimal subset of the JUnit 4 API
used by the tests. None of these files are compiled by the Gradle/Android build.

The data-layer JSON test (`ProcessedVideoTest`) also needs `org.json`, which the Gradle build gets
from the `json` catalog entry. Locally it can be compiled the same way after fetching the JSON-java
sources (tag `20240303`); when no `javac` is available (`jdk4py` is runtime-only), rely on CI for
that one file — it runs in the same `./gradlew testDebugUnitTest` step.
