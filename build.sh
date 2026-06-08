#!/usr/bin/env bash
# build.sh — Compile usque mobile package into Android AAR via gomobile.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
USQUE_DIR="$SCRIPT_DIR/../usque"
OUTPUT_DIR="$SCRIPT_DIR/app/libs"

source /etc/profile.d/go.sh 2>/dev/null || true
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.1.13356709"
export GOPATH="${GOPATH:-$HOME/go}"
export PATH="$PATH:/usr/local/go/bin:$GOPATH/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

if [ ! -d "$USQUE_DIR/mobile" ]; then
    echo "Error: usque not found at $USQUE_DIR"
    echo "Run ./fetch.sh first to clone the source."
    exit 1
fi

if ! command -v gomobile &>/dev/null; then
    echo "Error: gomobile not found in PATH"
    echo "Install with: go install golang.org/x/mobile/cmd/gomobile@latest"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

echo "==> Building usque AAR..."
echo "    source:  $USQUE_DIR ($(cd "$USQUE_DIR" && git rev-parse --short HEAD))"
echo "    target:  android/arm64 (API 24)"
echo "    output:  $OUTPUT_DIR"

cd "$USQUE_DIR"

gomobile bind \
    -target=android/arm64 \
    -androidapi 24 \
    -ldflags="-s -w" \
    -o "$OUTPUT_DIR/usque.aar" \
    ./mobile

echo "==> Done."
ls -lh "$OUTPUT_DIR"/usque.*
