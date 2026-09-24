#!/bin/bash
set -e

echo "====================================================="
echo " 🤖 Building Standalone J.A.R.V.I.S. Executable"
echo "====================================================="

source venv/bin/activate
pip install pyinstaller

echo "Building executable with PyInstaller..."
# We use --onedir for a faster start time, or --onefile for a single binary.
# We'll use --onedir and then compress it, or just use --onefile.
pyinstaller --noconfirm --onedir --windowed \
    --name "JARVIS" \
    --add-data "jarvis:jarvis" \
    --hidden-import "PySide6" \
    --hidden-import "sounddevice" \
    --hidden-import "numpy" \
    jarvis/main.py

echo "Build complete! Executable is located in dist/JARVIS/"

# Create a desktop file installer inside dist
cat << 'DESK' > dist/install_desktop.sh
#!/bin/bash
TARGET_DIR=~/.local/share/JARVIS
mkdir -p "$TARGET_DIR"
cp -r JARVIS/* "$TARGET_DIR/"

mkdir -p ~/.local/share/applications
cat <<EOF2 > ~/.local/share/applications/jarvis.desktop
[Desktop Entry]
Name=J.A.R.V.I.S.
Comment=Autonomous Linux AI Super-Agent
Exec=$TARGET_DIR/JARVIS
Icon=utilities-terminal
Terminal=false
Type=Application
Categories=Utility;
EOF2
update-desktop-database ~/.local/share/applications || true
echo "✅ Installed standalone J.A.R.V.I.S. to your applications menu!"
DESK
chmod +x dist/install_desktop.sh

echo "====================================================="
echo " ✅ You can now run: ./dist/install_desktop.sh"
echo "====================================================="
