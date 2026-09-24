# 🪡 J.A.R.V.I.S. UI Rebuild Specification & Stitch Integration

## 1. Overview & Stitch Connection
The J.A.R.V.I.S. Android voice assistant UI has been completely rebuilt using **Google Stitch** (`stitch-mcp`) designs, matching the application's full feature set:
- **Continuous Wake Word Detection**: TFLite DS-CNN INT8 continuous streaming KWS without audio contention.
- **Autonomous Agent Intelligence**: Goal evaluation, step-by-step reasoning, loop guards, and outcome verification.
- **Subsystem Automation**: Media & Music (Spotify, YouTube Music, YMusic), WhatsApp Direct Dispatch, Deep Research with candidate ranking, OS Device Controls, and Natural Language Calculation.
- **Memory Subsystem**: Local SQLite RAG (FTS + BM25), CAG session awareness, and MAG persistent facts.
- **Security & APIs**: Hardware-backed Android Keystore AES-GCM encrypted LLM credentials and Groq model switching (`gpt-oss-20b`, `gpt-oss-120b`, `llama-3.3-70b-versatile`).

---

## 2. Google Stitch Project & Generated Screen Assets
- **Stitch Project ID**: `projects/3145573286617872806` (Title: *Jarvis Android Voice Assistant*)
- **Dashboard Screen**: Screen ID `83fdb7309c364432a7134405734e3773`
  - Visual Preview: `docs/stitch/dashboard_preview.png`
  - Stitch HTML/CSS Code: `docs/stitch/dashboard_screen.html`
- **Skills & Protocols Screen**: Screen ID `75fd779711e740528a61db3ddadcd4aa`
  - Visual Preview: `docs/stitch/skills_preview.png`
  - Stitch HTML/CSS Code: `docs/stitch/skills_screen.html`

---

## 3. Stitch Design System & Color Palette
- **Background**: `#05090D` (Deep Cyber-Slate / Kinetic Intelligence)
- **Primary Surface**: `#0B141E` / `#101A23` (Dark Glassmorphic Panels)
- **Surface High**: `#18202A` / `#222B35` (Active Card Fill)
- **Primary Glow**: `#63EFFF` (Holographic Cyan)
- **Secondary Accent**: `#0266FF` (Electric Blue Container)
- **Border / Outline**: `#203442` / `#3C494B` (Subtle HUD Strokes)
- **State Badges**: `#70F0B0` (Success/Online), `#FF6B6B` (Error/Halted), `#63EFFF` (Listening/Planning)
- **Typography Tokens**:
  - Display / Headlines: `Sora` / Android Sans Bold
  - Body: `Inter`
  - Technical Data & HUD Caps: `JetBrains Mono`

---

## 4. Screen Rebuild Breakdown

### 4.1. Dashboard Screen (`DashboardScreen.kt`)
1. **Stitch Top HUD Bar**:
   - Branding: `J.A.R.V.I.S. • NEURAL_OS • KINETIC INTELLIGENCE`
   - Glowing dynamic service pill: `ONLINE`, `READY`, `STARTING`, `OFFLINE`
   - Quick header action buttons: `CANCEL TASK` and `⚙ SETTINGS`
2. **Holographic Hero Section**:
   - Centered circular Arc Reactor orb (`JarvisOrbView`) with multi-particle orbital rotation and pulse animations
   - Real-time frequency soundwave bars (`JarvisWaveformView`)
   - Dynamic status label: `LISTENING FOR JARVIS...`, `THINKING...`, `EXECUTING...`, `VERIFYING...`
3. **Stitch Quick Action Tech Chips** (Horizontal scrolling):
   - `▶ Play Music`: Instant synthwave playback
   - `💬 WhatsApp`: Quick communication transmission
   - `🔍 Research`: Deep web search query
   - `⚡ Skills Hub`: Switch to Skills tab
   - `◈ Memory Core`: Switch to Memory tab
4. **Active Protocol Tracker Card** (`⚡ ACTIVE PROTOCOL`):
   - Real-time multi-step progress list with checkmarks & live status badges (`PLANNING`, `EXECUTING`, `VERIFYING`, `STANDBY`)
5. **Live Glassmorphic Exchange**:
   - Cyan-accented user chat bubble with safe sanitization
   - Cyber-slate Jarvis response bubble with live execution text
6. **Command Vector Composer**:
   - Large center tactile microphone button (`🎙 MIC`) for manual Tap-to-Talk
   - Clean command input field with action send listener
   - `RUN CMD`, `CANCEL`, and `STOP` controls

### 4.2. Skills & Automation Screen (`SkillsScreen.kt`)
1. **Media & Sound Protocol Card**:
   - Presets: *Synthwave*, *Cyberpunk Mix*, *Deep Focus*, *Lo-Fi Chill*, *Top Hits*
   - App quick-launchers: Spotify, YouTube Music, YMusic
2. **Comm Link (WhatsApp) Card**:
   - Direct transmission composer with instant message queuing
   - Recent nodes avatars & Quick Voice Trigger button
3. **Deep Research & RAG Card**:
   - Multi-source query input (DuckDuckGo + Local SQLite RAG)
   - Status tracking candidate ranker and confidence scoring
4. **Core Ops & Device Automation Card**:
   - Wi-Fi, Bluetooth, and Battery Saver quick system intents
   - Natural Language Calculator: instantaneous computation (e.g. `20% of 1500 = 300`)

### 4.3. Memory Core Screen (`MemoryScreen.kt`)
1. **Health Metrics HUD**: 4 cards for `ACTIVE`, `PREFERENCES`, `DOC CHUNKS`, `MESSAGES`
2. **Store New Memory**: Form to save custom key-value memories into SQLite
3. **FTS Query & Relevance Search**: Search with live BM25 scoring
4. **Memory Cards**: Source provenance, timestamps, and `ARCHIVE` button

### 4.4. API & Model Manager (`ApiManagerScreen.kt`)
1. **Reasoning Models**: Supported Groq model chips (`gpt-oss-20b`, `gpt-oss-120b`, `llama-3.3-70b-versatile`)
2. **Keystore Security Badge**: AES-GCM hardware encryption indicator
3. **Active Endpoints**: Live ping testing (`TEST PING`), CRUD editing, and toggles

### 4.5. Settings & Trace Logs (`SettingsScreen.kt`)
1. **Audio & Wake Model**: Displays model status (`TFLite DS-CNN INT8`) and audio source (`VOICE_RECOGNITION`)
2. **Network & System**: Timeouts, retries, theme selection, verbosity
3. **Embedded Diagnostics**: Live correlation logs viewer (`X-Jarvis-Correlation-Id`) with Refresh & Clear

---

## 5. Verification & Build Artifacts
- **Kotlin Compilation**: `./gradlew compileDebugKotlin` completed with `BUILD SUCCESSFUL`
- **Unit & Regression Test Suite**: `./gradlew test` completed with `BUILD SUCCESSFUL` (48 tasks passed)
- **Production Package**: `./gradlew assembleDebug` generated `app-debug.apk` and root [`Jarvis-Final.apk`](../../Jarvis-Final.apk) (150 MB)
