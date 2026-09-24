"""
Continuous Audio Stream Wake-Word Listening Engine
"""

import logging
import numpy as np
from typing import Callable, Optional
from jarvis.wakeword.acoustic_detector import AcousticWakeWordDetector

logger = logging.getLogger("WakeWordEngine")

class WakeWordEngine:
    def __init__(self, sample_rate: int = 16000):
        self.sample_rate = sample_rate
        self.is_listening = False
        self.detector = AcousticWakeWordDetector()
        self.calibrated_threshold = 0.75
        self.on_wake_detected: Optional[Callable[[], None]] = None
        self._sd_stream = None

    def start_listening(self, callback: Callable[[], None]):
        self.on_wake_detected = callback
        self.is_listening = True
        try:
            import sounddevice as sd
            self._sd_stream = sd.InputStream(
                samplerate=self.sample_rate,
                channels=1,
                dtype='int16',
                blocksize=480,
                callback=self._audio_callback
            )
            self._sd_stream.start()
            logger.info("WakeWordEngine listening on default sounddevice input stream...")
        except Exception as e:
            logger.warning(f"Could not open sounddevice audio stream: {e}. Fallback to simulated audio listener.")

    def stop_listening(self):
        self.is_listening = False
        if self._sd_stream:
            try:
                self._sd_stream.stop()
                self._sd_stream.close()
            except Exception:
                pass
            self._sd_stream = None

    def _audio_callback(self, indata, frames, time_info, status):
        if not self.is_listening:
            return
        pcm_data = indata.flatten()
        score = self.detector.evaluate_audio_frame(pcm_data)
        if score >= self.calibrated_threshold:
            logger.info(f"Wake word phrase 'Jarvis' detected! (Confidence: {score})")
            if self.on_wake_detected:
                self.on_wake_detected()
