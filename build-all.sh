#!/usr/bin/env bash
# build-all.sh — Full pipeline: fetch usque → build AAR → build APK.
# Automatically uses release signing when keystore is available,
# otherwise builds unsigned debug APK.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

source /etc/profile.d/go.sh 2>/dev/null || true
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export GOPATH="${GOPATH:-$HOME/go}"
export PATH="$PATH:/usr/local/go/bin:$GOPATH/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

HAS_SIGNING=false
KEYSTORE_BASE64="${KEYSTORE_BASE64:-}"
KEYSTORE_FILE="${KEYSTORE_FILE:-}"
if [ -f "$SCRIPT_DIR/keystore.properties" ] || [ -n "$KEYSTORE_BASE64" ] || [ -n "$KEYSTORE_FILE" ]; then
    HAS_SIGNING=true
fi

if [ "$HAS_SIGNING" = true ]; then
    BUILD_TYPE="Release"
    GRADLE_TASK="assembleRelease"
    APK_DIR="release"
    echo "╔══════════════════════════════════════╗"
    echo "║   usquebox build pipeline [SIGNED]   ║"
    echo "╚══════════════════════════════════════╝"
else
    BUILD_TYPE="Debug"
    GRADLE_TASK="assembleDebug"
    APK_DIR="debug"
    echo "╔══════════════════════════════════════╗"
    echo "║  usquebox build pipeline [UNSIGNED]  ║"
    echo "╚══════════════════════════════════════╝"
fi

echo ""
echo "─── Step 1/3: Fetch usque source ───"
bash "$SCRIPT_DIR/fetch.sh"

echo ""
echo "─── Step 2/3: Build AAR ───"
bash "$SCRIPT_DIR/build.sh"

echo ""
echo "─── Step 3/3: Build APK ($BUILD_TYPE) ───"
cd "$SCRIPT_DIR"
./gradlew "$GRADLE_TASK" --no-daemon --project-dir "$SCRIPT_DIR"

APK=$(find "$SCRIPT_DIR/app/build/outputs/apk/$APK_DIR" -name "*.apk" -print -quit 2>/dev/null)
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "Error: APK not found in app/build/outputs/apk/$APK_DIR/"
    exit 1
fi

echo ""
echo "╔══════════════════════════════════════╗"
echo "║  Build complete!                     ║"
echo "╚══════════════════════════════════════╝"
echo "  APK:     $APK"
echo "  Size:    $(du -h "$APK" | cut -f1)"
echo "  Type:    $BUILD_TYPE $([ "$HAS_SIGNING" = true ] && echo "(V1+V2+V3 signed)" || echo "(unsigned)")"
echo "  usque:   $(cd "$SCRIPT_DIR/../usque" && git rev-parse --short HEAD)"
