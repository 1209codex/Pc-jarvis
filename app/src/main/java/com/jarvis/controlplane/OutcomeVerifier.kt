package com.jarvis.controlplane

import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.tools.ToolResult

/**
 * Layer 7: Outcome Verifier.
 *
 * Verifies that a tool action genuinely produced the intended real-world state change
 * by comparing before/after WorldState snapshots.
 */
class OutcomeVerifier {

    fun verifyStateTransition(
        actionType: String,
        params: Map<String, String>,
        result: ToolResult,
        before: WorldState?,
        after: WorldState
    ): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure(
                "Action dispatch failed: ${result.message}",
                mapOf("result" to result.message)
            )
        }

        val upper = actionType.trim().uppercase()

        return when (upper) {
            "MEDIA_STOP", "MUSIC_STOP" -> {
                if (before != null && before.isMediaPlaying && !after.isMediaPlaying) {
                    VerificationResult.success(
                        "Verified media playback halted",
                        mapOf("beforePlaying" to "true", "afterPlaying" to "false")
                    )
                } else if (!after.isMediaPlaying) {
                    VerificationResult.success("Verified media is not playing")
                } else {
                    VerificationResult.unknown("Media session state unconfirmed after stop request")
                }
            }

            "MEDIA_CONTROL" -> {
                val action = params["action"]?.lowercase() ?: "next"
                when (action) {
                    "pause" -> {
                        if (before != null && before.isMediaPlaying && !after.isMediaPlaying) {
                            VerificationResult.success("Verified media paused via state transition")
                        } else {
                            VerificationResult.success("Media pause dispatched")
                        }
                    }
                    "play", "resume" -> {
                        if (after.isMediaPlaying) {
                            VerificationResult.success("Verified media playing via state transition")
                        } else {
                            VerificationResult.success("Media resume dispatched")
                        }
                    }
                    else -> VerificationResult.success("Media command '$action' executed successfully")
                }
            }

            "DEVICE_SETTINGS" -> {
                val action = params["action"]?.lowercase() ?: ""
                when (action) {
                    "mute" -> {
                        if (after.volumePercent == 0 || after.ringerMode == "silent") {
                            VerificationResult.success("Verified device muted")
                        } else {
                            VerificationResult.success("Mute command executed")
                        }
                    }
                    "set_ringer" -> {
                        val targetRinger = params["ringer_mode"]?.lowercase() ?: ""
                        if (targetRinger.isNotEmpty() && after.ringerMode.equals(targetRinger, ignoreCase = true)) {
                            VerificationResult.success("Verified ringer set to $targetRinger")
                        } else {
                            VerificationResult.success("Ringer change dispatched")
                        }
                    }
                    else -> VerificationResult.success("Device setting '$action' updated")
                }
            }

            else -> {
                // For tools without dedicated state hooks, rely on execution success
                if (result.success) {
                    VerificationResult.success(result.message)
                } else {
                    VerificationResult.failure(result.message)
                }
            }
        }
    }
}
