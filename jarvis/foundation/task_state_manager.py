"""
Task Execution State & Correlation Logger
Replicates Android TaskStateManager.kt & CorrelationLogger.kt for Linux Software.
"""

import time
import uuid
import logging
from typing import Dict, List, Any, Optional

logger = logging.getLogger("TaskStateManager")

class TaskState:
    def __init__(self, task_id: str, prompt: str):
        self.task_id = task_id
        self.prompt = prompt
        self.start_time = time.time()
        self.end_time: Optional[float] = None
        self.status = "INIT"  # INIT, PLANNING, EXECUTING, VERIFYING, COMPLETED, FAILED, HALTED
        self.logs: List[Dict[str, Any]] = []

class TaskStateManager:
    def __init__(self):
        self.active_tasks: Dict[str, TaskState] = {}
        self.audit_records: List[Dict[str, Any]] = []

    def create_task(self, prompt: str) -> TaskState:
        task_id = f"task_{uuid.uuid4().hex[:8]}"
        state = TaskState(task_id, prompt)
        self.active_tasks[task_id] = state
        logger.info(f"Task created: [{task_id}] '{prompt}'")
        return state

    def update_status(self, task_id: str, status: str):
        if task_id in self.active_tasks:
            self.active_tasks[task_id].status = status
            logger.info(f"Task [{task_id}] status -> {status}")

    def log_audit(self, task_id: Optional[str], action: str, risk: str, reason: str, status: str, details: str):
        record = {
            "timestamp": time.time(),
            "task_id": task_id or "system",
            "action": action,
            "risk": risk,
            "reason": reason,
            "status": status,
            "details": details
        }
        self.audit_records.append(record)
        if len(self.audit_records) > 1000:
            self.audit_records.pop(0)

    def complete_task(self, task_id: str, success: bool):
        if task_id in self.active_tasks:
            task = self.active_tasks[task_id]
            task.end_time = time.time()
            task.status = "COMPLETED" if success else "FAILED"
