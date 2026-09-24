# Owner-Voice Wake-Word Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an enrolled owner's voice activate the wake word while failing closed for other, uncertain, or unavailable speaker checks.

**Architecture:** Keep keyword spotting and speaker verification as two gates. Add a local TFLite speaker encoder and bounded recent-audio buffer; store only a Keystore-encrypted speaker template. Owner-only mode requires both the neural wake model and speaker match. Existing calibration and manual tap-to-talk remain available.

**Tech Stack:** Kotlin, Android AudioRecord, existing TensorFlow Lite 2.14.0 runtime, Android Keystore, existing JUnit test setup.

**Spec:** `docs/superpowers/specs/2026-09-24-owner-voice-wakeword-design.md`

## Global Constraints

- Owner-only voice activation fails closed for absent enrollment, uncertain scores, inference errors, corrupt profile, or unavailable models.
- Never replace speaker verification with MFCC/RMS similarity; raw PCM is memory-only and never uploaded.
- No claim of replay protection, perfect accuracy, or high-security authentication.
- Existing uncommitted user edits must be preserved.
- Validate short wake-phrase accuracy, latency, model license, APK size, and preprocessing before enabling the feature.

---

### Task 1: Validate a redistributable on-device speaker model

**Files:**
- Inspect candidate `tflite-hub/conformer-speaker-encoder` model card and files: `vad_long_model.tflite`, `vad_long_mean_stddev.csv`, `conformer_tisid_medium.tflite`.
- Create temporary validation artifacts outside the repository; do not commit user recordings or temporary audio.

**Interfaces:**
- Candidate reference call: `wav_to_dvector.WavToDvectorRunner(vad_model_file, vad_mean_stddev_file, tisid_model_file).compute_score(enroll_wav_files, test_wav_file)`.
- The app may proceed only if the inference graph, preprocessing, license, acceptable asset size, and short wake-phrase support can be documented and reproduced.

- [ ] Confirm the artifact files and Apache-2.0 licensing, and record each model's byte size.
- [ ] Use the model's documented `sidlingvo` runner with several 16 kHz mono wake-phrase samples to establish expected input duration, preprocessing, and output behavior.
- [ ] Inspect each TFLite tensor's input/output types and shapes; verify the existing TFLite 2.14.0 interpreter can invoke each graph.
- [ ] Measure cold and warm inference time on the connected Android device with a short candidate utterance.
- [ ] If the required VAD/preprocessing cannot be ported accurately or runtime/asset limits fail, stop before adding Settings controls and report that a valid owner-only implementation needs a different verified model. Do not ship a feature flag that implies identity verification without a model.

### Task 2: Add speaker-template model and deterministic decision boundary

**Files:**
- Create: `app/src/main/java/com/jarvis/wakeword/OwnerVoiceVerifier.kt`
- Create: `app/src/test/java/com/jarvis/OwnerVoiceVerifierTest.kt`
- Add validated speaker-model assets/license notices under `app/src/main/assets/` only after Task 1 passes.

**Interfaces:**
- `interface OwnerVoiceVerifier : AutoCloseable { fun createTemplate(samples: List<ShortArray>): FloatArray?; fun similarity(template: FloatArray, candidate: ShortArray): Float? }`
- `fun accepts(similarity: Float?, threshold: Float): Boolean` returns `true` only when the score exists, is finite, and is at/above the validated threshold.

- [ ] Write failing tests: high finite score accepts; low score, null, NaN, and positive infinity reject.
- [ ] Run `./gradlew testDebugUnitTest --tests com.jarvis.OwnerVoiceVerifierTest` and confirm the new tests fail before implementation.
- [ ] Implement just the validated model preprocessing/inference and normalized template comparison using the existing TFLite runtime; do not add a second inference dependency.
- [ ] Re-run the focused test and confirm it passes; test that model-load/inference exceptions surface as unavailable/non-match rather than acceptance.

### Task 3: Persist owner profile locally and securely

**Files:**
- Create: `app/src/main/java/com/jarvis/wakeword/OwnerVoiceProfileStore.kt`
- Create: `app/src/androidTest/java/com/jarvis/OwnerVoiceProfileStoreTest.kt`

**Interfaces:**
- `data class OwnerVoiceProfile(val template: FloatArray, val enrolledAt: Long, val threshold: Float)`
- `interface OwnerVoiceProfileStore { fun load(): OwnerVoiceProfile?; fun save(profile: OwnerVoiceProfile); fun clear() }`

- [ ] Write Android tests proving absent, saved, replaced, corrupt, and cleared profiles return the specified states.
- [ ] Encrypt the serialized finite float template with an AES-GCM key held by Android Keystore; atomically replace ciphertext and metadata in app-private preferences.
- [ ] Reject malformed lengths, non-finite values, authentication-tag failures, and unknown versions as no enrolled profile.
- [ ] Run `./gradlew connectedDebugAndroidTest --tests com.jarvis.OwnerVoiceProfileStoreTest` on the device.

### Task 4: Gate wake callbacks on speaker verification

**Files:**
- Modify: `app/src/main/java/com/jarvis/voice/WakeWordEngine.kt`
- Modify: `app/src/main/java/com/jarvis/wakeword/HybridWakeWordDetector.kt`
- Modify/add: `app/src/test/java/com/jarvis/WakeWordDetectorTest.kt` or a focused `OwnerOnlyWakeGateTest.kt`

**Interfaces:**
- Add `ownerVoiceOnly: Boolean` state and `applyOwnerVoiceProfile(profile: OwnerVoiceProfile?)` to the wake engine.
- Add `pushNeuralAudio(samples: ShortArray): Float` to `HybridWakeWordDetector`; it invokes only the neural detector and returns zero if unavailable or on exception.
- Keep `WakeWordEngine.acceptAudio(samples): Boolean`; its callback fires only after keyword and owner checks pass.

- [ ] Add tests proving legacy mode still accepts its configured detector score and owner-only mode rejects non-match, uncertain, and absent-profile outcomes.
- [ ] Add a bounded 3-second PCM ring buffer to `WakeWordEngine`; clear it on stop, reset, close, profile change, and mode change.
- [ ] In owner-only mode, call only `pushNeuralAudio`; capture the candidate audio for speaker verification outside the audio-callback thread.
- [ ] Serialize pending checks and discard late results after stop/profile change; invoke the existing wake callback only for a positive speaker decision.
- [ ] Make missing neural model, speaker model, profile, exception, or insufficient audio an explicit rejection; never use acoustic/degraded fallback in owner-only mode.
- [ ] Run focused wake routing and concurrency tests, then the existing wake-word test group.

### Task 5: Add enrollment and Settings controls

**Files:**
- Modify: `app/src/main/java/com/jarvis/ui/dialogs/VoiceEnrollmentDialog.kt`
- Modify: `app/src/main/java/com/jarvis/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/jarvis/ui/model/UiModels.kt`
- Modify: `app/src/main/java/com/jarvis/ui/data/UiPreferencesStore.kt`
- Modify: `app/src/main/java/com/jarvis/voice/VoiceEngine.kt`

**Interfaces:**
- Add `ownerVoiceOnly: Boolean = false` to `AppSettings` and serialize it in the existing settings JSON.
- Enrollment callback becomes `(OwnerVoiceProfile) -> Unit`; sensitivity calibration continues to use `UserVoiceProfile` independently.

- [ ] Add tests for preference defaults/backward compatibility and for rejecting enablement without a valid owner profile.
- [ ] Change enrollment to collect multiple consented utterances, validate audio through the existing quality checks, create the encrypted template, and discard each PCM sample immediately after embedding extraction.
- [ ] Add an `Owner voice only` switch, `Train / retrain my voice` action, profile status/unavailable reason, and reset action beside existing calibration UI.
- [ ] Keep the switch off by default and disable it unless both speaker and neural wake models are ready; reset clears template and turns the switch off.
- [ ] Apply saved mode/profile at voice-engine startup and immediately after Settings changes; preserve tap-to-talk behavior.
- [ ] Run UI/store/wake focused test groups and compile `./gradlew assembleDebug`.

### Task 6: Verify the complete behavior and release build

**Files:**
- Modify tests only where the observed integration contract requires them.
- Update: `docs/DEVICE_TEST_REPORT.md` only with measured results, if present and current.

- [ ] Run `./gradlew testDebugUnitTest` and confirm there are no regressions.
- [ ] Run `./gradlew assembleDebug assembleRelease` and inspect APK size delta.
- [ ] On-device, record owner true-accept/false-reject and non-owner false-accept behavior across repeated quiet-room and normal-room trials; record verification latency.
- [ ] Test missing/corrupt profile, missing model, inference failure, reset, mode disable, app stop/restart, and tap-to-talk.
- [ ] If an acceptance or latency criterion fails, keep owner-only disabled and report measured evidence; do not claim it is fixed.
- [ ] Commit only feature files after reviewing the diff; leave pre-existing unrelated changes untouched.
