"""
Voice Activity Detection (VAD) & Acoustic Barge-In Detector
Replicates Android VadEngine.kt & BargeInDetector.kt for Linux Software.
"""

import numpy as np

class VadEngine:
    def __init__(self, energy_threshold: float = 300.0):
        self.energy_threshold = energy_threshold

    def is_speech(self, pcm_frame: np.ndarray) -> bool:
        if len(pcm_frame) == 0:
            return False
        rms = np.sqrt(np.mean(pcm_frame.astype(float) ** 2))
        return rms >= self.energy_threshold

class BargeInDetector:
    def __init__(self, vad_engine: VadEngine):
        self.vad_engine = vad_engine

    def check_barge_in(self, pcm_frame: np.ndarray) -> bool:
        """Triggers acoustic barge-in interruption if user speaks while TTS is playing."""
        return self.vad_engine.is_speech(pcm_frame)
