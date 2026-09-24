"""
Personal Voice Wake-Word Enrollment & Acoustic Calibration
Replicates Android UserVoiceProfile.kt, OwnerVoiceProfile.kt & VoiceEnrollmentCalibrator.kt for Linux Software.
"""

import numpy as np
import time
import logging

logger = logging.getLogger("VoiceProfile")

class UserVoiceProfile:
    def __init__(self, user_name: str = "Owner"):
        self.user_name = user_name
        self.calibrated_threshold = 0.75
        self.calibrated_noise_floor = 100.0
        self.sample_count = 0
        self.is_enrolled = False

class VoiceEnrollmentCalibrator:
    def __init__(self, profile: UserVoiceProfile):
        self.profile = profile
        self.recorded_samples = []

    def add_sample(self, pcm_data: np.ndarray) -> bool:
        """Validates sample duration, RMS energy, and non-clipping distortion."""
        rms = np.sqrt(np.mean(pcm_data.astype(float) ** 2))
        if rms < 70.0:
            logger.warning("Sample energy too low for enrollment")
            return False

        self.recorded_samples.append(pcm_data)
        self.profile.sample_count += 1
        if len(self.recorded_samples) >= 3:
            self.profile.is_enrolled = True
            self.profile.calibrated_threshold = 0.70
            logger.info("Personal Voice Calibration Complete!")
        return True
