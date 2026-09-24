"""
Unified Smart Command Bar Input Widget
Replicates Android Smart Command Bar (Voice & Keyboard) for Linux PySide6 GUI.
"""

from PySide6.QtWidgets import QWidget, QHBoxLayout, QLineEdit, QPushButton
from PySide6.QtCore import Signal, Qt

class SmartCommandBar(QWidget):
    command_dispatched = Signal(str)
    mic_toggled = Signal()
    stop_clicked = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(8, 4, 8, 4)

        self.input_field = QLineEdit(self)
        self.input_field.setPlaceholderText("Ask J.A.R.V.I.S. anything or enter natural language command...")
        self.input_field.setStyleSheet("""
            QLineEdit {
                background-color: #0E1621;
                color: #63EFFF;
                border: 1px solid #1E2C3D;
                border-radius: 18px;
                padding: 8px 14px;
                font-size: 14px;
            }
            QLineEdit:focus {
                border: 1px solid #63EFFF;
            }
        """)
        self.input_field.returnPressed.connect(self._on_send)

        self.mic_btn = QPushButton("🎙", self)
        self.mic_btn.setStyleSheet("""
            QPushButton {
                background-color: #1E2C3D;
                color: #63EFFF;
                border-radius: 18px;
                font-size: 16px;
                min-width: 36px;
                min-height: 36px;
            }
            QPushButton:hover {
                background-color: #00E5FF;
                color: #05090D;
            }
        """)
        self.mic_btn.clicked.connect(self.mic_toggled.emit)

        self.send_btn = QPushButton("➤", self)
        self.send_btn.setStyleSheet("""
            QPushButton {
                background-color: #00E5FF;
                color: #05090D;
                border-radius: 18px;
                font-size: 16px;
                min-width: 36px;
                min-height: 36px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #63EFFF;
            }
        """)
        self.send_btn.clicked.connect(self._on_send)

        self.stop_btn = QPushButton("⏹", self)
        self.stop_btn.setStyleSheet("""
            QPushButton {
                background-color: #FF5252;
                color: #FFFFFF;
                border-radius: 18px;
                font-size: 16px;
                min-width: 36px;
                min-height: 36px;
            }
        """)
        self.stop_btn.clicked.connect(self.stop_clicked.emit)

        layout.addWidget(self.input_field)
        layout.addWidget(self.mic_btn)
        layout.addWidget(self.send_btn)
        layout.addWidget(self.stop_btn)

    def _on_send(self):
        text = self.input_field.text().strip()
        if text:
            self.command_dispatched.emit(text)
            self.input_field.clear()
