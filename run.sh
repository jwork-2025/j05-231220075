#!/bin/bash

set -e

# 编译（总是先编译一次，自动发现源码）
mkdir -p build/classes
LWJGL_CP="."
if [ -d "lib/lwjgl" ]; then
  LWJGL_CP=".:lib/lwjgl/*"
fi
SOURCES=$(find src/main/java -type f -name "*.java")
javac -d build/classes -cp "$LWJGL_CP" $SOURCES

LWJGL_DIR="lib/lwjgl"
CLASSPATH="build/classes"
if [ -d "$LWJGL_DIR" ]; then
  CLASSPATH="$CLASSPATH:$LWJGL_DIR/*"
fi

# 计算 natives 路径（与 download_lwjgl.sh 保持一致）
OS="$(uname -s)"; ARCH_RAW="$(uname -m)"
case "$OS" in
  Darwin*) OS_ID="macos";;
  Linux*) OS_ID="linux";;
  MINGW*|MSYS*|CYGWIN*|Windows*) OS_ID="windows";;
  *) OS_ID="";;
esac
case "$ARCH_RAW" in
  arm64|aarch64) ARCH_ID="arm64";;
  x86_64) ARCH_ID="x86_64";;
  *) ARCH_ID="";;
esac

JAVA_FLAGS=""
if [ -n "$OS_ID" ] && [ -n "$ARCH_ID" ] && [ -d "$LWJGL_DIR/natives/${OS_ID}-${ARCH_ID}" ]; then
  JAVA_FLAGS="-Dorg.lwjgl.librarypath=$LWJGL_DIR/natives/${OS_ID}-${ARCH_ID}"
fi

MODE=${1:-single}
MAIN_CLASS="com.gameengine.example.GameExample"
if [ "$MODE" = "server" ]; then
  MAIN_CLASS="com.gameengine.example.ServerLauncher"
fi

if [[ "$OS" == Darwin* ]] && [ "$MAIN_CLASS" = "com.gameengine.example.GameExample" ]; then
  exec java -XstartOnFirstThread $JAVA_FLAGS -cp "$CLASSPATH" "$MAIN_CLASS"
else
  exec java $JAVA_FLAGS -cp "$CLASSPATH" "$MAIN_CLASS"
fi
