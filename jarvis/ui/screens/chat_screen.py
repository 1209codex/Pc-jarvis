import asyncio
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QTextEdit, 
                               QPushButton, QScrollArea, QLabel, QFrame)
from PySide6.QtCore import Qt, QSize
from PySide6.QtGui import QFont, QColor

class ChatScreen(QWidget):
    def __init__(self, runtime, parent=None):
        super().__init__(parent)
        self.runtime = runtime

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Chat History Area
        self.scroll_area = QScrollArea(self)
        self.scroll_area.setWidgetResizable(True)
        self.scroll_area.setStyleSheet("QScrollArea { border: none; background-color: #343541; }")
        
        self.chat_container = QWidget()
        self.chat_container.setStyleSheet("background-color: #343541;")
        self.chat_layout = QVBoxLayout(self.chat_container)
        self.chat_layout.setAlignment(Qt.AlignTop)
        self.chat_layout.setContentsMargins(20, 20, 20, 20)
        self.chat_layout.setSpacing(20)
        
        self.scroll_area.setWidget(self.chat_container)
        layout.addWidget(self.scroll_area)

        # Input Area
        input_container = QWidget()
        input_container.setStyleSheet("background-color: #343541;")
        input_layout = QVBoxLayout(input_container)
        input_layout.setContentsMargins(20, 10, 20, 20)
        
        input_box = QFrame()
        input_box.setStyleSheet("QFrame { background-color: #40414F; border-radius: 10px; }")
        input_box_layout = QHBoxLayout(input_box)
        input_box_layout.setContentsMargins(10, 10, 10, 10)

        self.text_input = QTextEdit()
        self.text_input.setPlaceholderText("Message J.A.R.V.I.S...")
        self.text_input.setStyleSheet("QTextEdit { background-color: transparent; border: none; color: white; font-size: 14px; }")
        self.text_input.setMaximumHeight(100)
        
        self.btn_send = QPushButton("Send")
        self.btn_send.setFixedSize(60, 40)
        self.btn_send.setStyleSheet("QPushButton { background-color: #19C37D; color: white; border-radius: 5px; font-weight: bold; } QPushButton:hover { background-color: #1A8859; }")
        self.btn_send.clicked.connect(self.on_send)

        input_box_layout.addWidget(self.text_input)
        input_box_layout.addWidget(self.btn_send)
        
        input_layout.addWidget(input_box)
        layout.addWidget(input_container)
        
        self.add_message("J.A.R.V.I.S.", "Hello! I am online and ready. How can I help you today?", is_user=False)

    def add_message(self, sender, text, is_user=True):
        msg_box = QFrame()
        msg_bg = "#343541" if is_user else "#444654"
        msg_box.setStyleSheet(f"QFrame {{ background-color: {msg_bg}; border-radius: 8px; padding: 10px; }}")
        
        msg_layout = QVBoxLayout(msg_box)
        msg_layout.setContentsMargins(15, 15, 15, 15)
        
        lbl_sender = QLabel(sender)
        lbl_sender.setStyleSheet("font-weight: bold; color: #ECECF1; font-size: 14px;")
        
        lbl_text = QLabel(text)
        lbl_text.setStyleSheet("color: #D1D5DB; font-size: 14px;")
        lbl_text.setWordWrap(True)
        
        msg_layout.addWidget(lbl_sender)
        msg_layout.addWidget(lbl_text)
        
        self.chat_layout.addWidget(msg_box)
        
        # Scroll to bottom
        self.scroll_area.verticalScrollBar().setValue(self.scroll_area.verticalScrollBar().maximum())

    def on_send(self):
        text = self.text_input.toPlainText().strip()
        if not text: return
        self.text_input.clear()
        
        self.add_message("You", text, is_user=True)
        
        # Run command async
        import qasync
        qasync.QTimer.singleShot(100, lambda: asyncio.get_event_loop().create_task(self.process_command(text)))
        
    async def process_command(self, text):
        res = await self.runtime.execute_command(text)
        response = res.get('spoken_response', 'Task completed.')
        self.add_message("J.A.R.V.I.S.", response, is_user=False)
