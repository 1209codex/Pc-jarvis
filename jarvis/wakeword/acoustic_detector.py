"""
MFCC Feature Extraction & Acoustic KWS Score Detector
Replicates Android MfccExtractor.kt & AcousticWakeWordDetector.kt for Linux Software.
"""

import numpy as np

class MfccExtractor:
    def __init__(self, sample_rate: int = 16000, n_mfcc: int = 13):
        self.sample_rate = sample_rate
        self.n_mfcc = n_mfcc

    def extract_features(self, pcm_data: np.ndarray) -> np.ndarray:
        """Extracts energy and MFCC feature matrix surrogate."""
        if len(pcm_data) == 0:
            return np.zeros(self.n_mfcc)
        # Compute RMS energy & spectral zero-crossing rate
        rms = np.sqrt(np.mean(pcm_data.astype(float) ** 2))
        zcr = np.mean(np.abs(np.diff(np.sign(pcm_data)))) / 2.0
        features = np.zeros(self.n_mfcc)
        features[0] = rms
        features[1] = zcr
        return features

class AcousticWakeWordDetector:
    def __init__(self, sensitivity: float = 0.75):
        self.sensitivity = sensitivity
        self.mfcc_extractor = MfccExtractor()

    def evaluate_audio_frame(self, pcm_data: np.ndarray) -> float:
        features = self.mfcc_extractor.extract_features(pcm_data)
        energy = features[0]
        # Energy threshold detection surrogate
        if energy > 1200.0:
            return 0.85
        return 0.05
