"""
Async Publish-Subscribe Event Bus Architecture
Replicates Android JarvisEventBus.kt for Linux Software.
"""

import asyncio
import logging
from typing import Callable, Dict, List, Any

logger = logging.getLogger("JarvisEventBus")

class JarvisEvent:
    def __init__(self, topic: str, payload: Any):
        self.topic = topic
        self.payload = payload

class JarvisEventBus:
    shared = None

    def __init__(self):
        self.listeners: Dict[str, List[Callable[[JarvisEvent], None]]] = {}

    def subscribe(self, topic: str, callback: Callable[[JarvisEvent], None]):
        if topic not in self.listeners:
            self.listeners[topic] = []
        self.listeners[topic].append(callback)

    def publish(self, topic: str, payload: Any):
        event = JarvisEvent(topic, payload)
        if topic in self.listeners:
            for listener in self.listeners[topic]:
                try:
                    if asyncio.iscoroutinefunction(listener):
                        asyncio.create_task(listener(event))
                    else:
                        listener(event)
                except Exception as e:
                    logger.error(f"Error handling event on topic '{topic}': {e}")

JarvisEventBus.shared = JarvisEventBus()
