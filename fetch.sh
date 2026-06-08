#!/usr/bin/env bash
# fetch.sh — Clone or update usque source at the pinned ref.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REF_FILE="$SCRIPT_DIR/usque.ref"

if [ ! -f "$REF_FILE" ]; then
    echo "Error: $REF_FILE not found"
    exit 1
fi

source "$REF_FILE"

USQUE_DIR="$SCRIPT_DIR/../usque"

if [ -d "$USQUE_DIR/.git" ]; then
    echo "==> usque repo exists, fetching latest..."
    cd "$USQUE_DIR"
    git fetch --all --quiet
else
    echo "==> Cloning usque from $USQUE_REPO..."
    rm -rf "$USQUE_DIR"
    git clone --quiet "$USQUE_REPO" "$USQUE_DIR"
    cd "$USQUE_DIR"
fi

echo "==> Checking out $USQUE_REF..."
git checkout --quiet "$USQUE_REF"
git submodule update --init --recursive --quiet 2>/dev/null || true

ACTUAL=$(git rev-parse HEAD)
echo "==> usque at commit $ACTUAL"

if [ "$ACTUAL" != "$USQUE_REF" ] && [[ "$ACTUAL" != "$USQUE_REF"* ]]; then
    echo "Warning: HEAD ($ACTUAL) does not match pinned ref ($USQUE_REF)"
fi
