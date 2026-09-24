"""
Transparent Floating HUD Overlay
Replicates Android TYPE_APPLICATION_OVERLAY for Linux PySide6 GUI.
"""

from PySide6.QtWidgets import QWidget, QVBoxLayout
from PySide6.QtCore import Qt
from jarvis.ui.widgets.arc_reactor_orb import ArcReactorOrb

class JarvisFloatingOverlay(QWidget):
    def __init__(self, runtime):
        super().__init__()
        self.runtime = runtime
        
        # Frameless, Always on Top, Tool window (no taskbar)
        self.setWindowFlags(Qt.WindowStaysOnTopHint | Qt.FramelessWindowHint | Qt.Tool | Qt.WindowTransparentForInput)
        self.setAttribute(Qt.WA_TranslucentBackground)
        
        self.resize(150, 150)
        
        # Position in bottom right corner
        import screeninfo
        try:
            monitors = screeninfo.get_monitors()
            if monitors:
                monitor = monitors[0]
                self.move(monitor.width - 170, monitor.height - 170)
        except Exception:
            pass # Fallback positioning
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        
        self.orb = ArcReactorOrb(self)
        self.orb.set_active(True)
        self.orb.set_audio_amplitude(0.4)
        layout.addWidget(self.orb)

    def toggle_visibility(self):
        if self.isVisible():
            self.hide()
        else:
            self.show()
