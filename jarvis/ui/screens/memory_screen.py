"""
🧠 Neural Memory Core Screen Widget (MEMORY)
Replicates Android MemoryScreen.kt for Linux PySide6 GUI.
"""

from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QTableWidget, QTableWidgetItem, QPushButton, QHeaderView
from PySide6.QtCore import Qt

class MemoryScreen(QWidget):
    def __init__(self, runtime, parent=None):
        super().__init__(parent)
        self.runtime = runtime

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(12)

        # 1. Health Metrics HUD
        metrics_layout = QHBoxLayout()
        self.lbl_active_facts = QLabel("Active Facts: 0", self)
        self.lbl_prefs = QLabel("User Preferences: 0", self)
        self.lbl_gc = QLabel("Evictions: 0", self)

        for lbl in [self.lbl_active_facts, self.lbl_prefs, self.lbl_gc]:
            lbl.setStyleSheet("color: #63EFFF; font-weight: bold; font-size: 13px; background-color: #0E1621; padding: 6px 12px; border-radius: 6px;")
            metrics_layout.addWidget(lbl)
        
        btn_gc = QPushButton("♻️ Run Garbage Collector", self)
        btn_gc.clicked.connect(self._run_gc)
        metrics_layout.addWidget(btn_gc)
        layout.addLayout(metrics_layout)

        # 2. Memory Store Table
        self.table = QTableWidget(self)
        self.table.setColumnCount(4)
        self.table.setHorizontalHeaderLabels(["Key", "Value", "Category", "Decayed Weight"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.setStyleSheet("""
            QTableWidget {
                background-color: #0A0F17;
                color: #E0E6ED;
                gridline-color: #1E2C3D;
                border: 1px solid #1E2C3D;
            }
            QHeaderView::section {
                background-color: #0E1621;
                color: #63EFFF;
                font-weight: bold;
            }
        """)
        layout.addWidget(self.table)

        self.refresh_memories()

    def refresh_memories(self):
        items = self.runtime.subsystems.memory_store.query_memories()
        self.table.setRowCount(len(items))

        facts_cnt, pref_cnt = 0, 0
        for row, item in enumerate(items):
            if item.category == "FACTUAL_KNOWLEDGE":
                facts_cnt += 1
            elif item.category == "USER_PREFERENCE":
                pref_cnt += 1

            self.table.setItem(row, 0, QTableWidgetItem(item.key))
            self.table.setItem(row, 1, QTableWidgetItem(item.value))
            self.table.setItem(row, 2, QTableWidgetItem(item.category))
            self.table.setItem(row, 3, QTableWidgetItem(str(item.compute_decayed_weight())))

        self.lbl_active_facts.setText(f"Active Facts: {facts_cnt}")
        self.lbl_prefs.setText(f"User Preferences: {pref_cnt}")
        self.lbl_gc.setText(f"Evictions: {self.runtime.subsystems.metrics.memory_eviction_count}")

    def _run_gc(self):
        self.runtime.subsystems.memory_store.run_garbage_collection()
        self.refresh_memories()
