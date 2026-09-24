
import sys
from PySide6.QtWidgets import QMainWindow, QHBoxLayout, QVBoxLayout, QWidget, QSystemTrayIcon, QMenu, QPushButton, QLabel, QFrame
from PySide6.QtCore import Qt, QSize
from PySide6.QtGui import QIcon, QColor, QPixmap, QPainter, QBrush, QFont

from jarvis.ui.screens.chat_screen import ChatScreen

class MainWindow(QMainWindow):
    def __init__(self, runtime):
        super().__init__()
        self.runtime = runtime

        self.setWindowTitle("J.A.R.V.I.S.")
        self.resize(1024, 768)
        self.setStyleSheet("QMainWindow { background-color: #343541; }")

        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)

        main_layout = QHBoxLayout(central_widget)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)

        # Left Sidebar (Darker)
        sidebar = QFrame()
        sidebar.setFixedWidth(260)
        sidebar.setStyleSheet("QFrame { background-color: #202123; }")
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(10, 10, 10, 10)
        sidebar_layout.setSpacing(10)

        btn_new_chat = QPushButton("+ New chat")
        btn_new_chat.setStyleSheet("QPushButton { background-color: transparent; color: white; border: 1px solid #565869; border-radius: 5px; padding: 12px; text-align: left; font-size: 14px; } QPushButton:hover { background-color: #2A2B32; }")
        
        lbl_history = QLabel("Chat History")
        lbl_history.setStyleSheet("color: #8E8EA0; font-size: 12px; font-weight: bold; margin-top: 15px;")
        
        btn_history1 = QPushButton("Quantum Entanglement")
        btn_history1.setStyleSheet("QPushButton { background-color: transparent; color: #D1D5DB; text-align: left; padding: 10px; border: none; } QPushButton:hover { background-color: #2A2B32; border-radius: 5px; }")

        sidebar_layout.addWidget(btn_new_chat)
        sidebar_layout.addWidget(lbl_history)
        sidebar_layout.addWidget(btn_history1)
        sidebar_layout.addStretch()

        btn_settings = QPushButton("⚙ Settings")
        btn_settings.setStyleSheet("QPushButton { background-color: transparent; color: white; text-align: left; padding: 12px; border: none; } QPushButton:hover { background-color: #2A2B32; border-radius: 5px; }")
        sidebar_layout.addWidget(btn_settings)

        main_layout.addWidget(sidebar)

        # Right Main Chat Area
        self.chat_screen = ChatScreen(self.runtime, self)
        main_layout.addWidget(self.chat_screen)

        self._setup_system_tray()

    def _setup_system_tray(self):
        pixmap = QPixmap(32, 32)
        pixmap.fill(Qt.transparent)
        painter = QPainter(pixmap)
        painter.setBrush(QBrush(QColor("#19C37D")))
        painter.drawEllipse(2, 2, 28, 28)
        painter.end()

        icon = QIcon(pixmap)
        self.tray = QSystemTrayIcon(icon, self)
        self.tray.setToolTip("J.A.R.V.I.S. AI")

        menu = QMenu(self)
        action_show = menu.addAction("Open J.A.R.V.I.S.")
        action_quit = menu.addAction("Quit")

        action_show.triggered.connect(self.show)
        action_quit.triggered.connect(sys.exit)

        self.tray.setContextMenu(menu)
        self.tray.show()
