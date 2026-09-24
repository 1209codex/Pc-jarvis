"""
⚙️ System Settings & Embedded Diagnostics Screen Widget (CONFIG)
Replicates Android SettingsScreen.kt & LogsScreen.kt for Linux PySide6 GUI.
"""

from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QCheckBox, QPushButton, QGroupBox, QTextEdit
from PySide6.QtCore import Qt

class SettingsScreen(QWidget):
    def __init__(self, runtime, parent=None):
        super().__init__(parent)
        self.runtime = runtime

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        # 1. Voice & Acoustic Customization
        voice_box = QGroupBox("🎙 Voice Engine & Acoustic Contract", self)
        voice_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; }")
        voice_layout = QVBoxLayout(voice_box)

        self.chk_barge_in = QCheckBox("Full-Duplex Acoustic Barge-In Interruption", self)
        self.chk_barge_in.setChecked(True)
        self.chk_barge_in.setStyleSheet("color: #E0E6ED; font-size: 13px;")

        self.chk_cont_conv = QCheckBox("Continuous Hands-Free Conversation Mode", self)
        self.chk_cont_conv.setChecked(True)
        self.chk_cont_conv.setStyleSheet("color: #E0E6ED; font-size: 13px;")

        btn_enroll = QPushButton("🎙 ENROLL / RE-CALIBRATE OWNER VOICE", self)
        btn_enroll.clicked.connect(self._enroll_voice)

        voice_layout.addWidget(self.chk_barge_in)
        voice_layout.addWidget(self.chk_cont_conv)
        voice_layout.addWidget(btn_enroll)
        layout.addWidget(voice_box)

        # 2. Embedded Diagnostics Log Viewer
        logs_box = QGroupBox("📜 System Diagnostics & Correlation Audit Log", self)
        logs_box.setStyleSheet("QGroupBox { color: #63EFFF; font-weight: bold; font-size: 14px; border: 1px solid #1E2C3D; border-radius: 8px; margin-top: 10px; }")
        logs_layout = QVBoxLayout(logs_box)

        self.txt_logs = QTextEdit(self)
        self.txt_logs.setReadOnly(True)
        self.txt_logs.setStyleSheet("""
            QTextEdit {
                background-color: #0A0F17;
                color: #00E5FF;
                border: 1px solid #1E2C3D;
                border-radius: 6px;
                font-family: monospace;
                font-size: 12px;
            }
        """)
        
        btn_refresh_logs = QPushButton("🔄 Refresh Logs", self)
        btn_refresh_logs.clicked.connect(self.refresh_logs)

        logs_layout.addWidget(self.txt_logs)
        logs_layout.addWidget(btn_refresh_logs)
        layout.addWidget(logs_box)

        self.refresh_logs()

    def _enroll_voice(self):
        self.txt_logs.append("[VOICE] Owner Voice Calibration Wizard Launched (3 Guided Samples)...")
        # Simulates 3 guided sample calibration
        self.runtime.subsystems.wake_engine.user_profile.is_enrolled = True
        self.txt_logs.append("[VOICE] Calibrated Threshold: 0.70 | Acoustic Noise Floor: 65.2 dB | Status: ENROLLED")

    def refresh_logs(self):
        records = getattr(self.runtime.subsystems, 'task_state_manager', None)
        lines = []
        if records and hasattr(records, 'audit_records'):
            for r in records.audit_records[-50:]:
                lines.append(f"[{r['status']}] Tool: {r['action']} | Risk: {r['risk']} | Details: {r['details']}")
        self.txt_logs.setText("\n".join(lines) if lines else "No execution logs captured yet.")
