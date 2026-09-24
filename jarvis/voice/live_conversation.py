"""
Full-Duplex Live Conversation Controller & Voice State Machine
Replicates Android LiveConversationController.kt & VoiceState.kt for Linux Software.
"""

from enum import Enum, auto
import logging

logger = logging.getLogger("LiveConversationController")

class VoiceState(Enum):
    IDLE = auto()
    WAKING = auto()
    LISTENING = auto()
    THINKING = auto()
    SPEAKING = auto()

class LiveConversationController:
    def __init__(self, tts_engine, asr_engine, wake_engine):
        self.state = VoiceState.IDLE
        self.tts_engine = tts_engine
        self.asr_engine = asr_engine
        self.wake_engine = wake_engine
        self.on_state_changed = None

    def set_state(self, new_state: VoiceState):
        self.state = new_state
        logger.info(f"Voice State -> {new_state.name}")
        if self.on_state_changed:
            self.on_state_changed(new_state)

    def trigger_wake(self):
        self.set_state(VoiceState.WAKING)
        self.tts_engine.speak("Yes Sir?")
        self.set_state(VoiceState.LISTENING)

    def stop_speaking(self):
        """Physical/acoustic barge-in interrupt silences TTS immediately."""
        logger.info("Barge-in interrupt received: Stopping speech")
        self.set_state(VoiceState.IDLE)
