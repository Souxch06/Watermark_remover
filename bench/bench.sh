#!/bin/bash
# Bench build & run: fresh compile dir each time (never leave variant files in src/).
# Requires the local toolchain: kotlinc at ~/.local/tools/kc (npm i kotlin-compiler@2.4.20)
# and a JDK (pip install jdk4py into ~/.local/venv). See setup.sh.
set -e
export JAVA_HOME="$HOME/.local/venv/lib/python3.11/site-packages/jdk4py/java-runtime"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$HOME/.local/tools/kc/bin/kotlinc"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BUILD=/tmp/wmbuild
rm -rf "$BUILD"
mkdir -p "$BUILD/src"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/processing/WatermarkAnalyzer.kt" "$BUILD/src/"
cp "$REPO/app/src/main/java/com/souxch/watermarkremover/processing/RegionRestorer.kt" "$BUILD/src/"
cp "$REPO/bench/src/Main.kt" "$BUILD/src/"
cd "$BUILD"
echo "--- compiling ---"
"$KOTLINC" src -include-runtime -d bench.jar 2>&1 | grep -vi "^warning" | head -30
echo "--- running ---"
java -jar bench.jar "$@"
