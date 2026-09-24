# Owner-Voice Wake-Word Verification

## Problem

Wake-word enrollment currently calibrates confidence and RMS energy only. `WakeWordEngine.acceptAudio` triggers immediately after a keyword score clears its threshold; it has no speaker identity check. `HybridWakeWordDetector` can also accept an acoustic heuristic independently of the neural model, including degraded mode when the TFLite model is unavailable. This can activate on another person's voice or on a heuristic false positive.

## Goal

Add an opt-in owner-only wake mode that activates only when both the configured wake phrase and a locally enrolled speaker match. If the speaker profile is missing, the model is unavailable, or verification is inconclusive, voice activation is denied. Manual tap-to-talk remains available. This is a convenience false-trigger filter, not secure authentication or liveness detection; replayed/synthesized copies may pass and zero false accepts cannot be promised.

## Design

Keep keyword spotting and speaker verification as separate gates. In ordinary mode, preserve existing wake-word behavior. In owner-only mode, require a successful neural keyword-model detection and then a successful speaker match before notifying `VoiceEngine`; never allow acoustic/degraded fallback to activate. A missing wake or speaker model fails closed for voice activation, while app status explains why and manual input continues to work.

Add an owner-only setting beside the existing voice enrollment controls. Enabling it requires a completed speaker enrollment. Re-enrollment replaces the existing local profile; reset removes it and turns owner-only mode off. The existing three-sample calibration remains clearly labelled as sensitivity calibration, not speaker training.

Enrollment captures multiple prompted utterances, derives speaker embeddings locally, and retains only the minimum profile needed for comparison—never raw PCM. Store the profile encrypted using an Android Keystore-backed key; keep mode state in the existing preferences store. Verification compares a candidate wake utterance with the enrolled template using a threshold validated for the chosen model. Scores below threshold or in an uncertain range are rejected. No remote voice processing is used.

There is no speaker-recognition model in the app today. Reuse the existing TensorFlow Lite runtime if a compatible, redistributable on-device speaker encoder can be verified. The candidate TFLite Hub conformer encoder is Apache-2.0, but its published usage requires VAD, normalization data, and `sidlingvo` processing; it is not a drop-in Android model. Before committing to an artifact, verify its license, file size, tensor contract, preprocessing, and short wake-phrase accuracy on-device. Do not substitute MFCC/RMS similarity and label it speaker verification. If no suitable artifact passes that gate, stop owner-only activation from being represented as available and report the blocker rather than shipping a fake identity check.

The listener must retain enough recent PCM to score the same utterance that caused keyword detection. Keep this buffer bounded and memory-only; clear it on stop/reset. Run verification off the real-time audio callback, suppress the wake transition until the result is known, and discard stale results if listening has stopped or the owner profile changed.

## Settings and behavior

- Existing calibration and its reset remain usable when owner-only mode is off.
- Add an `Owner voice only` switch, initially off.
- Add a separate `Train / retrain my voice` action with explicit recording/privacy disclosure and progress/results.
- The switch cannot be enabled without a valid enrolled template and available model.
- In owner-only mode, non-match, uncertain score, absent enrollment, inference error, or missing model means no wake activation; tap-to-talk is unaffected.
- Reset deletes the encrypted speaker profile and disables owner-only mode.
- Do not claim the feature prevents replay attacks or is suitable for high-security actions.

## Acceptance criteria

1. Existing non-owner mode behavior remains covered and unchanged except for separately fixed, proven false-positive bugs.
2. Owner-only mode rejects a generic wake hit when speaker verification returns non-match or uncertain, and activates only when both gates pass.
3. No enrollment, a corrupt/unreadable profile, an inference exception, or missing model never falls through to acoustic/degraded wake activation.
4. Enrollment replaces prior profile atomically; reset deletes it; raw PCM is not persisted or sent over network.
5. Settings accurately distinguish sensitivity calibration from owner speaker enrollment and show why owner-only mode is unavailable.
6. Unit tests cover both gate outcomes and all fail-closed conditions; Android-device checks cover latency, genuine-owner acceptance, and at least one other-speaker rejection in quiet and typical room conditions.
7. Any threshold is treated as a measured tradeoff: record owner false rejects and non-owner false accepts during device checks. Do not make a zero-error guarantee.

## Non-goals

- Strong biometric security, anti-spoof/liveness detection, or authorization for sensitive actions.
- Cloud-based voice processing or storing raw recordings.
- Replacing wake-word recognition, retraining the existing wake-word classifier, or changing the configured wake phrase.
- Adding arbitrary tuning sliders or a reusable speaker-verification framework.

## Verification plan

First validate the selected model artifact, preprocessing, licensing, APK impact, and inference latency on the target Android device. Add narrow tests for the owner-verification decision boundary and fail-closed routing. Then run focused wake/enrollment tests, the full unit suite, and debug/release builds. On-device, enroll the owner and test repeated genuine calls, other-speaker calls, model/profile unavailable states, tap-to-talk, and the verification latency. Do not declare owner-only mode ready if the model fails short-utterance tests or device latency is unacceptable.

## Planning references

- Local code: `WakeWordEngine.kt`, `HybridWakeWordDetector.kt`, `VoiceEnrollmentCalibrator.kt`, `VoiceEnrollmentDialog.kt`, `SettingsScreen.kt`, and `UiPreferencesStore.kt`.
- Candidate model card: https://huggingface.co/tflite-hub/conformer-speaker-encoder
- The GitHub workspace MCP returned no connected repositories; local source was used for the code audit.
