"""
System Telemetry & Performance Metrics Collector
Replicates Android MetricsCollector.kt for Linux Software.
"""

import time
from typing import Dict, List, Any

class MetricsCollector:
    shared = None

    def __init__(self):
        self.api_call_count = 0
        self.total_tokens_used = 0
        self.total_latency_ms = 0.0
        self.tool_execution_counts: Dict[str, int] = {}
        self.memory_eviction_count = 0

    def record_api_call(self, tokens: int, latency_ms: float):
        self.api_call_count += 1
        self.total_tokens_used += tokens
        self.total_latency_ms += latency_ms

    def record_tool_call(self, tool_name: str):
        self.tool_execution_counts[tool_name] = self.tool_execution_counts.get(tool_name, 0) + 1

    def record_eviction(self):
        self.memory_eviction_count += 1

    def get_summary(self) -> Dict[str, Any]:
        avg_latency = (self.total_latency_ms / self.api_call_count) if self.api_call_count > 0 else 0.0
        return {
            "api_call_count": self.api_call_count,
            "total_tokens_used": self.total_tokens_used,
            "avg_latency_ms": round(avg_latency, 2),
            "tool_executions": self.tool_execution_counts,
            "evictions": self.memory_eviction_count
        }

MetricsCollector.shared = MetricsCollector()
