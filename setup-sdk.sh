#!/bin/bash
# Setup Java + Android SDK for building the Jarvis APK
# Run this once on a fresh machine

set -e

ANDROID_SDK_DIR="$HOME/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "=== Installing Java 17 ==="
if ! java -version 2>&1 | grep -q "17"; then
    sudo apt-get update
    sudo apt-get install -y openjdk-17-jdk
fi
echo "Java OK: $(java -version 2>&1 | head -1)"

echo ""
echo "=== Installing Android SDK ==="
mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"

if [ ! -f "$ANDROID_SDK_DIR/cmdline-tools/bin/sdkmanager" ]; then
    echo "Downloading command-line tools..."
    cd /tmp
    curl -sL "$CMDLINE_TOOLS_URL" -o cmdline-tools.zip
    unzip -qo cmdline-tools.zip
    mv cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest"
    rm cmdline-tools.zip
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools:$PATH"

echo "Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "Installing build tools + platform..."
sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"

echo ""
echo "=== Done ==="
echo "Add to ~/.bashrc:"
echo "  export ANDROID_HOME=$ANDROID_SDK_DIR"
echo "  export PATH=\$ANDROID_SDK_DIR/cmdline-tools/latest/bin:\$ANDROID_SDK_DIR/platform-tools:\$PATH"
echo ""
echo "Then run: ./gradlew assembleDebug"
