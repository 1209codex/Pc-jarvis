"""
Google Stitch Material 3 Main Window & System Tray Integration
Replicates Android MainActivity.kt & ResponsiveNav.kt for Linux PySide6 GUI.
"""

import sys
from PySide6.QtWidgets import QMainWindow, QTabWidget, QVBoxLayout, QWidget, QSystemTrayIcon, QMenu
from PySide6.QtCore import Qt
from PySide6.QtGui import QIcon, QColor, QPixmap, QPainter, QBrush

from jarvis.ui.screens.dashboard_screen import DashboardScreen
from jarvis.ui.screens.skills_screen import SkillsScreen
from jarvis.ui.screens.memory_screen import MemoryScreen
from jarvis.ui.screens.api_screen import ApiManagerScreen
from jarvis.ui.screens.settings_screen import SettingsScreen

class MainWindow(QMainWindow):
    def __init__(self, runtime):
        super().__init__()
        self.runtime = runtime

        self.setWindowTitle("🤖 J.A.R.V.I.S. — Autonomous Linux AI Super-Agent")
        self.resize(960, 720)
        self.setStyleSheet("""
            QMainWindow {
                background-color: #05090D;
            }
            QTabWidget::pane {
                border: 1px solid #1E2C3D;
                background-color: #05090D;
                border-radius: 8px;
            }
            QTabBar::tab {
                background-color: #0A0F17;
                color: #8A99AD;
                padding: 10px 20px;
                font-weight: bold;
                font-size: 13px;
                border-top-left-radius: 8px;
                border-top-right-radius: 8px;
                margin-right: 4px;
            }
            QTabBar::tab:selected {
                background-color: #0E1621;
                color: #63EFFF;
                border-bottom: 2px solid #63EFFF;
            }
            QPushButton {
                background-color: #1E2C3D;
                color: #63EFFF;
                border: 1px solid #63EFFF;
                border-radius: 6px;
                padding: 6px 14px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #63EFFF;
                color: #05090D;
            }
        """)

        # Central Widget & Tab Navigation
        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)

        layout = QVBoxLayout(central_widget)
        layout.setContentsMargins(12, 12, 12, 12)

        self.tabs = QTabWidget(self)
        self.dashboard_tab = DashboardScreen(self.runtime, self)
        self.skills_tab = SkillsScreen(self.runtime, self)
        self.memory_tab = MemoryScreen(self.runtime, self)
        self.api_tab = ApiManagerScreen(self.runtime, self)
        self.settings_tab = SettingsScreen(self.runtime, self)

        self.tabs.addTab(self.dashboard_tab, "🏠 HOME")
        self.tabs.addTab(self.skills_tab, "⚡ SKILLS")
        self.tabs.addTab(self.memory_tab, "🧠 MEMORY")
        self.tabs.addTab(self.api_tab, "🔑 APIS")
        self.tabs.addTab(self.settings_tab, "⚙️ CONFIG")

        layout.addWidget(self.tabs)

        # Create System Tray Icon
        self._setup_system_tray()

    def _setup_system_tray(self):
        pixmap = QPixmap(32, 32)
        pixmap.fill(Qt.transparent)
        painter = QPainter(pixmap)
        painter.setBrush(QBrush(QColor("#63EFFF")))
        painter.drawEllipse(2, 2, 28, 28)
        painter.end()

        icon = QIcon(pixmap)
        self.tray = QSystemTrayIcon(icon, self)
        self.tray.setToolTip("J.A.R.V.I.S. Autonomous Linux Super-Agent")

        menu = QMenu(self)
        action_show = menu.addAction("Show Dashboard")
        action_wake = menu.addAction("🎙 Wake J.A.R.V.I.S.")
        action_quit = menu.addAction("Quit")

        action_show.triggered.connect(self.show)
        action_wake.triggered.connect(lambda: self.dashboard_tab._handle_mic_click())
        action_quit.triggered.connect(sys.exit)

        self.tray.setContextMenu(menu)
        self.tray.show()
