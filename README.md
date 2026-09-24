# 🤖 J.A.R.V.I.S. — Autonomous Linux AI Super-Agent & Voice Assistant

[![Platform](https://img.shields.io/badge/Platform-Linux%20(Ubuntu/Debian)-00E5FF?style=flat-square)](https://github.com/1209codex/Pc-jarvis)
[![Python](https://img.shields.io/badge/Python-3.10%2B-blue?style=flat-square)](https://python.org)
[![GUI](https://img.shields.io/badge/GUI-PySide6%20(Qt)-green?style=flat-square)](https://doc.qt.io/qtforpython/)

**J.A.R.V.I.S.** (*Just A Rather Very Intelligent System*) is a fully autonomous, closed-loop Linux desktop AI super-agent and personal voice assistant. Originally built for Android, this port brings the complete 11-layer architecture, 4D Policy Engine, and Google Stitch UI to your Linux machine.

Built for privacy-first, on-device intelligence, J.A.R.V.I.S. integrates continuous background wake-word detection, multi-provider LLM reasoning (Groq `llama3`, Gemini `gemini-2.5-flash`, OpenRouter, and local Ollama), native X11/Wayland desktop automation (via `xdotool` and `xclip`), evdev Bluetooth earbud hook interception, fail-closed policy execution, and on-device hybrid RAG memory.

---

## 📱 User Interface & Visual Showcase (Google Stitch Material 3)

The user interface is built using **PySide6**, strictly adhering to Material 3 cyber-slate aesthetics: deep `#05090D` kinetic background, `#63EFFF` holographic cyan glow, glassmorphic container panels, and fluid waveforms.

### Modes of Operation
1. **Desktop Dashboard Mode**: Full window with Skills, Memory, API config, and interactive Arc Reactor.
2. **Transparent HUD Overlay Mode (`--overlay`)**: An always-on-top, frameless Arc Reactor orb that sits quietly in the corner of your screen.
3. **Headless Daemon Mode (`--daemon`)**: Runs silently in the background as a systemd service, waking up only when you say the wake-word or click your Bluetooth headset button.
4. **Command Line Mode (`--cli`)**: Pass text instructions directly from your terminal.

---

## 📦 Installation & Setup

### Automated Installation (Ubuntu/Debian)

We provide an automated installation script that sets up all dependencies, virtual environments, desktop shortcuts, and systemd services.

```bash
git clone https://github.com/1209codex/Pc-jarvis.git pc-jarvis
cd pc-jarvis

# Run the installer script
./install.sh
```

### What `install.sh` does:
1. Installs system packages: `python3`, `xdotool`, `xprintidle`, `brightnessctl`, `xclip`, `libportaudio2`
2. Creates a Python virtual environment (`venv`) and installs `requirements.txt`
3. Creates a `.desktop` file so J.A.R.V.I.S. appears in your standard Linux app launcher.
4. Generates a `jarvis.service` systemd file in `~/.config/systemd/user/`.

### Managing the Background Daemon
If you want J.A.R.V.I.S. to listen in the background 24/7 on your desktop:
```bash
# Start it right now
systemctl --user start jarvis.service

# Check logs
journalctl --user -u jarvis.service -f

# Enable it to start automatically when you log in
systemctl --user enable jarvis.service
```

---

## 📖 Feature Guide

### 1. 🎙️ Continuous Wake-Word Detection
- **What it does**: Continuously monitors the microphone for the wake phrase.
- **How to use**: Simply say *"Hey Jarvis"* or *"Jarvis"* at normal speaking volume. It will chime and enter active listening mode. 

### 2. 🔘 Floating HUD Overlay & Barge-in
- Run `venv/bin/python jarvis/main.py --overlay` to spawn the transparent Arc Reactor HUD on your desktop.
- Click the HUD at any time to instantly trigger manual voice listening.
- Click it while J.A.R.V.I.S. is speaking to instantly silence it.

### 3. 🎛️ Bluetooth Earbud Interception (Linux `evdev`)
- **What it does**: Listens for media button events on connected Bluetooth earbuds or headsets.
- **How to use**: Single-click your headset's Play/Pause button while idle to instantly wake J.A.R.V.I.S.

### 4. 🧭 Proactive Desktop Intelligence
- **User Presence**: J.A.R.V.I.S. monitors `xprintidle` to know if you are actively at your computer or away, adjusting notification behaviors accordingly.
- **Clipboard Integration**: Automatically reads or injects text into your X11/Wayland clipboard via `xclip` / `wl-clipboard`.

### 5. ⚡ OS System Controls & Automations
- *"turn on flashlight"* $\to$ Controls your keyboard/screen brightness via `brightnessctl`.
- *"open Chrome"* $\to$ Uses `xdotool` and system calls to launch Linux applications.
- *"install htop"* $\to$ Installs software packages directly via APT using `pkexec`.
- *"read my clipboard"* $\to$ Fetches the current text in your copy-buffer.

### 6. 🧠 Local Neural Memory Core
- **What it does**: Stores user preferences, notes, and facts in a local JSON/SQLite database with semantic retrieval.
- *"Remember my passport number is A1234"* $\to$ *"What is my passport number?"*

---

## 🔨 Development & Manual Execution

If you prefer not to use the installer, you can run J.A.R.V.I.S. manually:

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
pip install -e .

# Launch Desktop App
python3 -m jarvis.main

# Launch CLI Mode
python3 -m jarvis.main --cli "calculate 15 percent of 500"

# Launch Headless Daemon
python3 -m jarvis.main --daemon
```

### Running Tests
The codebase includes a comprehensive `pytest` suite.
```bash
python3 -m pytest tests/
```
