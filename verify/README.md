# Local verification harness

Checks the parts of the app that do not need the Android SDK, so they can be run on a machine
without Android Studio. It replaces the old `bench/` folder, whose shell scripts assumed a local
toolchain installed through `~/.local` and were never executed by CI.

`src/Mirror.kt` is a copy of the pure functions the app ships (file-name sanitisation, version
comparison, bitrate bounds). `src/Verify.kt` asserts their behaviour — including the hostile inputs
(a picker-provided display name or a release version trying to escape the destination directory).

Run it with a Kotlin compiler and a JDK 17+ (nothing else is required):

```
kotlinc src/Mirror.kt src/Verify.kt -include-runtime -d verify.jar
java -jar verify.jar
```

The same assertions run in CI through the JUnit tests
(`app/src/test/java/.../LibraryRepositoryTest.kt`, `UpdateInstallerTest.kt`,
`UpdateCheckerTest.kt`), which are the authoritative ones: keep this harness in sync when those
change, or just run `./gradlew testDebugUnitTest`.
