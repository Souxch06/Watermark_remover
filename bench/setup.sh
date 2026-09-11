#!/bin/bash
# Reinstall the local toolchain (kotlinc from npm + JDK from pypi) if missing.
set -e
if [ ! -x "$HOME/.local/venv/bin/python" ]; then
  python3 -m venv ~/.local/venv
  ~/.local/venv/bin/pip install --quiet jdk4py pillow numpy
fi
if [ ! -f "$HOME/.local/tools/kc/bin/kotlinc" ]; then
  mkdir -p ~/.local/tools
  cd /tmp
  npm pack kotlin-compiler@2.4.20 >/dev/null 2>&1
  tar xzf kotlin-compiler-2.4.20.tgz
  rm -rf ~/.local/tools/kc
  mv package ~/.local/tools/kc
  rm -f kotlin-compiler-2.4.20.tgz
fi
echo "toolchain OK"
