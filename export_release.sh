#!/usr/bin/env bash
# =============================================================================
# Jarvis Production Release Build & Export Script
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"

GRADLE_CMD="./gradlew"
if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
fi

echo "=== Building Production Release APK ==="
"$GRADLE_CMD" assembleRelease --no-daemon

echo "=== Exporting Release Artifacts ==="
mkdir -p dist
cp -f app/build/outputs/apk/release/app-release.apk dist/jarvis-v1.0-release.apk
sha256sum dist/jarvis-v1.0-release.apk > dist/jarvis-v1.0-release.apk.sha256

echo "================================================="
echo " Production APK successfully exported to:"
echo " -> dist/jarvis-v1.0-release.apk ($(du -h dist/jarvis-v1.0-release.apk | cut -f1))"
echo " -> Checksum: $(cat dist/jarvis-v1.0-release.apk.sha256)"
echo "================================================="
