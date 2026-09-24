#!/bin/bash
set -e

echo "====================================================="
echo " 🤖 Installing J.A.R.V.I.S. for Linux Desktop"
echo "====================================================="

echo "[1/4] Installing system dependencies (requires sudo)..."
sudo apt-get update
sudo apt-get install -y python3 python3-pip python3-venv xdotool xprintidle brightnessctl libportaudio2 xclip libxcb-cursor0

echo "[2/4] Setting up Python virtual environment..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
fi
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
pip install -e .

echo "[3/4] Creating Desktop Shortcut..."
mkdir -p ~/.local/share/applications
cat <<EOF > ~/.local/share/applications/jarvis.desktop
[Desktop Entry]
Name=J.A.R.V.I.S.
Comment=Autonomous Linux AI Super-Agent
Exec=$(pwd)/venv/bin/python $(pwd)/jarvis/main.py
Icon=utilities-terminal
Terminal=false
Type=Application
Categories=Utility;
EOF
update-desktop-database ~/.local/share/applications || true

echo "[4/4] Configuring Background Systemd Service..."
mkdir -p ~/.config/systemd/user

cat <<EOF > ~/.config/systemd/user/jarvis.service
[Unit]
Description=J.A.R.V.I.S. Autonomous Linux AI Daemon
After=network.target pulseaudio.service

[Service]
Type=simple
WorkingDirectory=$(pwd)
ExecStart=$(pwd)/venv/bin/python $(pwd)/jarvis/main.py --daemon
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
echo "Systemd service installed."
echo "To start J.A.R.V.I.S. automatically on boot, run:"
echo "    systemctl --user enable jarvis.service"
echo "To start J.A.R.V.I.S. in the background right now, run:"
echo "    systemctl --user start jarvis.service"

echo "====================================================="
echo " ✅ Installation Complete!"
echo " You can now launch J.A.R.V.I.S. from your application menu,"
echo " or run the overlay manually using: ./venv/bin/python jarvis/main.py --overlay"
echo "====================================================="
