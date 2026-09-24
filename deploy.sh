#!/bin/bash
# Build and deploy Jarvis to Android device via ADB
# Usage: ./deploy.sh [path-to-apk]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="jarvis"
PKG="com.jarvis"
MODEL_DIR="$SCRIPT_DIR/models"

# --- Check ADB ---
if ! command -v adb &>/dev/null; then
    echo "ERROR: adb not found. Install android-tools."
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -v "List" | grep -c -E "\bdevice\b")
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No device connected. Connect via USB or:"
    echo "  adb connect <device-ip>:5555"
    exit 1
fi
echo "Found $DEVICE_COUNT device(s)"

# --- Build or use provided APK ---
if [ -n "$1" ] && [ -f "$1" ]; then
    APK="$1"
elif [ -f "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
else
    echo "No APK found. Build first:"
    echo "  ./gradlew assembleDebug"
    echo "  # or build in Android Studio"
    echo ""
    echo "Then re-run: ./deploy.sh"
    exit 1
fi

echo "Installing APK: $APK"
adb install -r "$APK"

# --- Push models if present ---
if [ -d "$MODEL_DIR" ]; then
    echo "Pushing models to device..."
    DEVICE_MODEL_DIR="/sdcard/Android/data/$PKG/files"
    adb shell "mkdir -p $DEVICE_MODEL_DIR"

    for f in "$MODEL_DIR"/*; do
        fname=$(basename "$f")
        echo "  $fname"
        adb push "$f" "$DEVICE_MODEL_DIR/$fname"
    done

    # Also push to app's internal files dir
    echo "Copying to app internal storage..."
    adb shell "run-as $PKG cp -r $DEVICE_MODEL_DIR/* /data/data/$PKG/files/ 2>/dev/null || true"
fi

# --- Launch app ---
echo "Launching $PKG..."
adb shell am start -n "$PKG/.ui.MainActivity"

echo ""
echo "Done. App is running."
echo "Logs: adb logcat -s VoicePipeline,SherpaAsr,GroqLlm,SherpaTts,AudioCapture,TaskManager,RaphaelWakeWordEngine"
