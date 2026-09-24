"""
Holographic Arc Reactor Orb QPainter Widget
Replicates Android JarvisOrbView.kt for Linux PySide6 GUI.
"""

import math
from PySide6.QtWidgets import QWidget
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QPainter, QColor, QPen, QBrush

class ArcReactorOrb(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(220, 220)
        self.pulse_phase = 0.0
        self.audio_amplitude = 0.2
        self.is_active = False

        self.timer = QTimer(self)
        self.timer.timeout.connect(self._update_animation)
        self.timer.start(16)  # 60 FPS animation

    def set_audio_amplitude(self, amp: float):
        self.audio_amplitude = max(0.1, min(amp, 1.0))

    def set_active(self, active: bool):
        self.is_active = active

    def _update_animation(self):
        self.pulse_phase += 0.05 if not self.is_active else 0.12
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        center = self.rect().center()
        base_radius = min(self.width(), self.height()) // 3.5

        # Dynamic expansion based on audio energy & state
        pulse_offset = math.sin(self.pulse_phase) * 6.0 * self.audio_amplitude
        r = base_radius + pulse_offset

        # 1. Outer Glow Ring
        painter.setPen(Qt.NoPen)
        glow_color = QColor(99, 239, 255, 45 if not self.is_active else 90)  # #63EFFF
        painter.setBrush(QBrush(glow_color))
        painter.drawEllipse(center, r + 20, r + 20)

        # 2. Holographic Outer Arc Ring
        pen = QPen(QColor("#63EFFF"), 3)
        painter.setPen(pen)
        painter.setBrush(QBrush(QColor("#05090D")))
        painter.drawEllipse(center, r + 8, r + 8)

        # 3. Inner Orb Core
        core_color = QColor("#00E5FF") if self.is_active else QColor("#00B0FF")
        painter.setPen(QPen(QColor("#63EFFF"), 2))
        painter.setBrush(QBrush(core_color))
        painter.drawEllipse(center, r, r)

        # 4. Central Energy Node
        painter.setPen(Qt.NoPen)
        painter.setBrush(QBrush(QColor("#FFFFFF")))
        painter.drawEllipse(center, r / 3.0, r / 3.0)
