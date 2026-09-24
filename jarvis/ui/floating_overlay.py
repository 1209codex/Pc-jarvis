"""
Transparent Floating HUD Overlay
Replicates Android TYPE_APPLICATION_OVERLAY for Linux PySide6 GUI.
Features: Draggable, Snap to Edge, Expandable Mini HUD.
"""

from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QFrame, QApplication
from PySide6.QtCore import Qt, QPoint, QTimer, QPropertyAnimation, QEasingCurve, QRect
from PySide6.QtGui import QMouseEvent, QColor

from jarvis.ui.widgets.arc_reactor_orb import ArcReactorOrb
import sys

class JarvisFloatingOverlay(QWidget):
    def __init__(self, runtime):
        super().__init__()
        self.runtime = runtime
        
        # Tool window (no taskbar), Frameless, Always on Top.
        # We removed WindowTransparentForInput so it CAN be clicked/dragged.
        self.setWindowFlags(Qt.WindowStaysOnTopHint | Qt.FramelessWindowHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        
        self.is_dragging = False
        self.drag_start_pos = QPoint()
        self.is_expanded = False
        
        self.resize(300, 150) # Wider to accommodate expanded HUD
        
        # Main layout
        self.main_layout = QHBoxLayout(self)
        self.main_layout.setContentsMargins(10, 10, 10, 10)
        self.main_layout.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        
        # Expanded Mini HUD Panel (Hidden by default)
        self.hud_panel = QFrame()
        self.hud_panel.setStyleSheet("QFrame { background-color: rgba(10, 15, 23, 220); border: 1px solid #63EFFF; border-radius: 12px; padding: 10px; }")
        self.hud_panel.hide()
        
        hud_layout = QVBoxLayout(self.hud_panel)
        self.status_badge = QLabel("ONLINE • IDLE")
        self.status_badge.setStyleSheet("color: #63EFFF; font-weight: bold; font-size: 11px;")
        
        self.preview_text = QLabel("Awaiting input...")
        self.preview_text.setStyleSheet("color: white; font-size: 13px;")
        self.preview_text.setWordWrap(True)
        
        hud_layout.addWidget(self.status_badge)
        hud_layout.addWidget(self.preview_text)
        
        self.main_layout.addWidget(self.hud_panel)
        
        # The Orb
        self.orb = ArcReactorOrb(self)
        self.orb.setFixedSize(80, 80)
        self.orb.set_active(True)
        self.orb.set_audio_amplitude(0.3)
        self.main_layout.addWidget(self.orb)
        
        # Position in bottom right corner
        self._snap_to_edge()
        
        # Auto collapse timer
        self.collapse_timer = QTimer(self)
        self.collapse_timer.timeout.connect(self.collapse_hud)

    def mousePressEvent(self, event: QMouseEvent):
        if event.button() == Qt.LeftButton:
            self.is_dragging = True
            self.drag_start_pos = event.globalPos() - self.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event: QMouseEvent):
        if self.is_dragging:
            self.move(event.globalPos() - self.drag_start_pos)
            event.accept()

    def mouseReleaseEvent(self, event: QMouseEvent):
        if event.button() == Qt.LeftButton and self.is_dragging:
            self.is_dragging = False
            self._snap_to_edge()
            
            # If it was a click (not a drag), expand or collapse
            if (event.globalPos() - self.frameGeometry().topLeft() - self.drag_start_pos).manhattanLength() < 5:
                self.toggle_hud()
                
            event.accept()

    def _snap_to_edge(self):
        screen = QApplication.primaryScreen().geometry()
        pos = self.pos()
        
        # Snap to right edge by default
        target_x = screen.width() - self.width() + 20
        target_y = max(0, min(pos.y(), screen.height() - self.height()))
        
        if pos.x() < screen.width() / 2:
            # Snap to left edge
            target_x = -20
            self.main_layout.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
        else:
            self.main_layout.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        self.anim = QPropertyAnimation(self, b"pos")
        self.anim.setDuration(300)
        self.anim.setStartValue(self.pos())
        self.anim.setEndValue(QPoint(target_x, target_y))
        self.anim.setEasingCurve(QEasingCurve.OutBack)
        self.anim.start()

    def toggle_hud(self):
        if self.is_expanded:
            self.collapse_hud()
        else:
            self.expand_hud()

    def expand_hud(self):
        self.is_expanded = True
        self.hud_panel.show()
        self.collapse_timer.start(6000) # Auto collapse after 6s
        
    def collapse_hud(self):
        self.is_expanded = False
        self.hud_panel.hide()
        self.collapse_timer.stop()

    def update_status(self, status, message):
        self.status_badge.setText(status.upper())
        self.preview_text.setText(message)
        if not self.is_expanded:
            self.expand_hud()

    def toggle_visibility(self):
        if self.isVisible():
            self.hide()
        else:
            self.show()
