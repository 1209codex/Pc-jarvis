"""
⚡ Skills & Automations Deck Screen Widget (SKILLS)
Replicates Android SkillsScreen.kt for Linux PySide6 GUI.
"""

import asyncio
from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QGroupBox, QPushButton, QLabel, QLineEdit
from PySide6.QtCore import Qt

class SkillsScreen(QWidget):
    def __init__(self, runtime, parent=None):
        super().__init__(parent)
        self.runtime = runtime

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        # 1. Music & Entertainment Deck
        music_box = QGroupBox("🎵 Music & Entertainment Protocol", self)
        music_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; } QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 5px; }")
        music_layout = QGridLayout(music_box)

        btn_play = QPushButton("▶ Play / Pause", self)
        btn_next = QPushButton("⏭ Next", self)
        btn_prev = QPushButton("⏮ Prev", self)
        btn_synthwave = QPushButton("📻 Synthwave", self)

        btn_play.clicked.connect(lambda: self._dispatch("media_play_pause", {}))
        btn_next.clicked.connect(lambda: self._dispatch("media_next", {}))
        btn_prev.clicked.connect(lambda: self._dispatch("media_prev", {}))
        btn_synthwave.clicked.connect(lambda: self._dispatch("media_play", {"track": "Synthwave Cyberpunk"}))

        music_layout.addWidget(btn_prev, 0, 0)
        music_layout.addWidget(btn_play, 0, 1)
        music_layout.addWidget(btn_next, 0, 2)
        music_layout.addWidget(btn_synthwave, 0, 3)
        layout.addWidget(music_box)

        # 2. Linux Telephony & Messaging Deck (KDE Connect)
        comm_box = QGroupBox("💬 Telephony & Messaging (KDE Connect)", self)
        comm_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; }")
        comm_layout = QGridLayout(comm_box)

        btn_read_msg = QPushButton("💬 READ UNREAD", self)
        btn_read_msg.clicked.connect(lambda: self._dispatch("read_unread_messages", {}))
        comm_layout.addWidget(btn_read_msg, 0, 0)
        layout.addWidget(comm_box)

        # 3. Optical Perception & Screen Vision
        vision_box = QGroupBox("👁️ Camera Vision & Desktop OCR", self)
        vision_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; }")
        vision_layout = QGridLayout(vision_box)

        btn_cam = QPushButton("👁️ WHAT IS THIS?", self)
        btn_screen = QPushButton("📖 READ DESKTOP OCR", self)

        btn_cam.clicked.connect(lambda: self._dispatch("camera_perception", {"prompt": "What is in front of camera?"}))
        btn_screen.clicked.connect(lambda: self._dispatch("screen_vision", {}))

        vision_layout.addWidget(btn_cam, 0, 0)
        vision_layout.addWidget(btn_screen, 0, 1)
        layout.addWidget(vision_box)

        # 4. Natural Language Calculator
        calc_box = QGroupBox("🧮 Natural Language Calculator", self)
        calc_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; }")
        calc_layout = QHBoxLayout(calc_box)

        self.calc_input = QLineEdit(self)
        self.calc_input.setPlaceholderText("Enter expression (e.g. 54 * 12 + 100)...")
        btn_calc = QPushButton("Calculate", self)

        self.calc_result = QLabel("Result: --", self)
        self.calc_result.setStyleSheet("color: #00E5FF; font-weight: bold;")

        btn_calc.clicked.connect(self._on_calc)
        calc_layout.addWidget(self.calc_input)
        calc_layout.addWidget(btn_calc)
        calc_layout.addWidget(self.calc_result)
        layout.addWidget(calc_box)

        layout.addStretch()

    def _dispatch(self, tool_name: str, args: dict):
        asyncio.create_task(self.runtime.tool_executor.execute_tool(tool_name, args))

    def _on_calc(self):
        expr = self.calc_input.text()
        async def _eval():
            res = await self.runtime.tool_executor.execute_tool("calculator", {"expression": expr})
            self.calc_result.setText(f"Result: {res.output}")
        asyncio.create_task(_eval())
