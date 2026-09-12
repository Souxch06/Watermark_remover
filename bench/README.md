# JVM bench & test harness (development tool)

Scripts to calibrate the analysis/restoration pipeline on synthetic videos without the
Android toolchain. They are NOT part of the app: nothing here is compiled into the APK,
the Gradle build only looks at `app/`.

- `setup.sh` — installs the local toolchain into `~/.local` (volatile): kotlinc 2.4.20 from
  npm (`kotlin-compiler`), a JDK from PyPI (`jdk4py`), pillow/numpy for image probing.
- `bench.sh [filter]` — compiles `WatermarkAnalyzer.kt` + `RegionRestorer.kt` + `src/Main.kt`
  and runs the calibration matrix: moving textured background, semi-transparent wordmark or
  opaque logo, static / re-rendered per clip / drifting. Reports the mean error over the
  watermark area vs the clean ground truth, near-black pixel counts, and the per-clip
  watermark offsets the restorer tracked.
- `test.sh` — runs the full JUnit test class with a tiny org.junit shim (the same tests the
  CI runs with Gradle), for fast iteration.

Rules learned the hard way: keep exactly ONE `Main.kt` in `src/` (a variant file breaks the
build with redeclarations), rewrite whole files rather than patching them by script, and
compare versions through this bench rather than one-off diagnostics.
