#!/bin/bash
# JUnit harness: compiles the pure-Kotlin sources + tests + a JUnit shim with kotlinc and runs
# them reflectively. Fresh build dir each time.
set -e
export JAVA_HOME="$HOME/.local/venv/lib/python3.11/site-packages/jdk4py/java-runtime"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$HOME/.local/tools/kc/bin/kotlinc"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUILD=/tmp/wmtest2
rm -rf "$BUILD"
mkdir -p "$BUILD/src"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/processing/WatermarkAnalyzer.kt" "$BUILD/src/"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/processing/RegionRestorer.kt" "$BUILD/src/"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/processing/WatermarkLayer.kt" "$BUILD/src/"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/model/Zones.kt" "$BUILD/src/"
cp "$REPO/bench/junit/WatermarkShaderStub.kt" "$BUILD/src/"
cp "$REPO/bench/junit/OrgJUnit.kt" "$BUILD/src/"
cp "$REPO/bench/junit/TestRunner.kt" "$BUILD/src/"
cp "$REPO/app/src/test/java/com/souxch/watermarkremover/processing/WatermarkAnalyzerTest.kt" "$BUILD/src/"
cd "$BUILD"
echo "--- compiling ---"
"$KOTLINC" src -include-runtime -d tests.jar 2>&1 | grep -viE "^warning|^\s*\^" | head -30
echo "--- running ---"
java -cp tests.jar TestRunnerKt "$@"
