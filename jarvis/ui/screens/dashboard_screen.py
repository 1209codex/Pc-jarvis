"""
🏠 Home Dashboard Screen Widget (HOME)
Replicates Android DashboardScreen.kt for Linux PySide6 GUI.
"""

from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTextEdit, QFrame
from PySide6.QtCore import Qt, Slot
from jarvis.ui.widgets.arc_reactor_orb import ArcReactorOrb
from jarvis.ui.widgets.dynamic_waveform import DynamicWaveform
from jarvis.ui.widgets.smart_command_bar import SmartCommandBar

class DashboardScreen(QWidget):
    def __init__(self, runtime, parent=None):
        super().__init__(parent)
        self.runtime = runtime

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        # 1. Top HUD Bar
        hud_bar = QHBoxLayout()
        self.status_badge = QLabel("● READY (Tier 5: SYSTEM_SETTINGS)", self)
        self.status_badge.setStyleSheet("color: #00E676; font-weight: bold; font-size: 13px;")
        
        self.protocol_stage = QLabel("Stage: IDLE", self)
        self.protocol_stage.setStyleSheet("color: #63EFFF; font-weight: bold; font-size: 13px;")

        hud_bar.addWidget(self.status_badge)
        hud_bar.addStretch()
        hud_bar.addWidget(self.protocol_stage)
        layout.addLayout(hud_bar)

        # 2. Hero Arc Reactor Orb Core
        self.orb_view = ArcReactorOrb(self)
        orb_layout = QHBoxLayout()
        orb_layout.addStretch()
        orb_layout.addWidget(self.orb_view)
        orb_layout.addStretch()
        layout.addLayout(orb_layout)

        # 3. Dynamic Waveform Visualizer
        self.waveform_view = DynamicWaveform(self)
        layout.addWidget(self.waveform_view)

        # 4. Live Dialogue Exchange Stream
        self.dialogue_log = QTextEdit(self)
        self.dialogue_log.setReadOnly(True)
        self.dialogue_log.setStyleSheet("""
            QTextEdit {
                background-color: #0A0F17;
                color: #E0E6ED;
                border: 1px solid #1E2C3D;
                border-radius: 8px;
                padding: 10px;
                font-family: monospace;
                font-size: 13px;
            }
        """)
        self.dialogue_log.append("<span style='color:#63EFFF;'>[J.A.R.V.I.S.]</span> Systems initialized. At your service, Sir.")
        layout.addWidget(self.dialogue_log)

        # 5. Unified Smart Command Bar
        self.command_bar = SmartCommandBar(self)
        self.command_bar.command_dispatched.connect(self._handle_user_command)
        self.command_bar.mic_toggled.connect(self._handle_mic_click)
        self.command_bar.stop_clicked.connect(self._handle_stop)
        layout.addWidget(self.command_bar)

        # Listen to pipeline stage changes
        # self.runtime.subsystems.execution_pipeline.add_listener(self._on_stage_changed)

    def _on_stage_changed(self, stage: str):
        self.protocol_stage.setText(f"Stage: {stage}")
        if stage in ["PLANNING", "EXECUTING"]:
            self.orb_view.set_active(True)
            self.waveform_view.set_amplitude(0.8)
        else:
            self.orb_view.set_active(False)
            self.waveform_view.set_amplitude(0.2)

    @Slot(str)
    def _handle_user_command(self, text: str):
        self.dialogue_log.append(f"<br/><span style='color:#00E5FF;'>[USER]</span> {text}")
        import asyncio
        asyncio.create_task(self._async_execute(text))

    async def _async_execute(self, text: str):
        result = await self.runtime.execute_command(text)
        spoken = result.get("spoken_response", "")
        self.dialogue_log.append(f"<span style='color:#63EFFF;'>[J.A.R.V.I.S.]</span> {spoken}")

    def _handle_mic_click(self):
        self.dialogue_log.append("<span style='color:#FFD600;'>[MIC]</span> Listening for voice input...")
        self.runtime.subsystems.live_conversation.trigger_wake()

    def _handle_stop(self):
        self.dialogue_log.append("<span style='color:#FF5252;'>[STOP]</span> Current operation cancelled.")
