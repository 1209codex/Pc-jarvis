#!/usr/bin/env bash
set -e

APK_PATH="/home/shanu/Desktop/android-jarvis/exports/Jarvis-v1.0-release.apk"

echo "=== Jarvis Android ADB Auto-Installer ==="
echo "Checking ADB connection..."

DEVICE=$(adb devices | grep -v "List of devices" | grep "device$" | head -n 1 | awk '{print $1}')

if [ -z "$DEVICE" ]; then
    echo "Phone not recognized by ADB yet."
    echo "Please ensure USB Debugging is turned ON in Developer Options on your Samsung phone."
    echo "Waiting for device..."
    adb wait-for-device
fi

echo "Connected Device: $(adb devices -l | grep -v 'List of devices' | head -n 1)"
echo "Installing $APK_PATH with permissions granted (-g)..."
adb install -r -d -g "$APK_PATH"

echo "Launching Jarvis..."
adb shell am start -n com.jarvis/.ui.MainActivity

echo "✅ Jarvis successfully installed and launched!"
