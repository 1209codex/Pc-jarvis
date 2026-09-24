"""
Audio-Reactive Dynamic Frequency Waveform Widget
Replicates Android JarvisWaveformView.kt for Linux PySide6 GUI.
"""

import math
from PySide6.QtWidgets import QWidget
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QPainter, QColor, QPen, QPainterPath

class DynamicWaveform(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumHeight(60)
        self.phase = 0.0
        self.amplitude = 0.3

        self.timer = QTimer(self)
        self.timer.timeout.connect(self._update_wave)
        self.timer.start(25)  # 40 FPS

    def set_amplitude(self, amp: float):
        self.amplitude = max(0.1, min(amp, 1.0))

    def _update_wave(self):
        self.phase += 0.08
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)

        w = self.width()
        h = self.height()
        mid_y = h / 2.0

        path = QPainterPath()
        path.moveTo(0, mid_y)

        for x in range(0, w, 4):
            normalized_x = x / w
            sine = math.sin(normalized_x * math.pi * 4 + self.phase)
            envelope = math.sin(normalized_x * math.pi)  # Fade out at ends
            y = mid_y + sine * envelope * (h / 3.0) * self.amplitude
            path.lineTo(x, y)

        pen = QPen(QColor("#63EFFF"), 2)
        painter.setPen(pen)
        painter.drawPath(path)
