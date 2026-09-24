# Honest Automation Outcomes

## Problem

Jarvis can report an automation as completed when it only knows that the tool returned success. The fast path in `AssistantRuntime` speaks its planned response without calling verification. The agent path does call `VerificationEngine`, but several action types are currently marked `VERIFIED` from `ToolResult.success` alone. `HallucinationFirewall.sanitizeSpokenResponse` exists, but no production caller routes responses through it. The UI also labels every `AgentEvent.Completed` as “Task completed” and plays the success tone, regardless of whether an external effect was confirmed.

## Goal and scope

Make automation reports match available evidence across both direct and agent execution paths. Treat tool success as dispatch/execution evidence, not proof of a real-world postcondition. Keep this as the first reliability subproject; adding new automation capabilities is explicitly deferred until outcome reporting is trustworthy.

## Design

Reuse `VerificationStatus` (`VERIFIED`, `FAILED`, `UNKNOWN`) as the authoritative outcome. `VerificationEngine` may return `VERIFIED` only when an action-specific check or structured result provides evidence appropriate to that action. A successful tool result without such evidence becomes `UNKNOWN`; actual tool or postcondition failures remain `FAILED`. Verification exceptions or unavailable device state also become `UNKNOWN`, never optimistic success.

Route both `AssistantRuntime`'s safe/direct plan and `AgentKernel` actions through this outcome policy. Format the final response from the observed tool result and verification status, not from an LLM/planned success sentence. Verified actions may use a concise success response; unknown outcomes must say that the action was dispatched/attempted but could not be confirmed; failed actions must describe the failure. Multi-action responses must not claim the whole goal succeeded if any required action is unknown or failed.

Carry the outcome into terminal UI feedback as well as speech: an unconfirmed action must not be shown as an unqualified success or trigger the success tone. Preserve permission requests and confirmation gates; this design does not auto-grant permissions or retry non-idempotent actions to obtain evidence.

Use the existing firewall only if it can be made the single response boundary without duplicating the verification policy; otherwise remove or narrow it so there is one authoritative formatter. Do not add a second status model or duplicate verification framework.

## Acceptance criteria

1. Direct and agent execution produce the same honest wording for equivalent verified, unknown, and failed outcomes.
2. A tool returning success with no action-specific evidence cannot produce a verified result or an unqualified completion claim.
3. Failed verification cannot be overwritten by a planner response; unavailable verification yields an explicit uncertainty statement.
4. Terminal UI status and success feedback agree with the outcome conveyed by speech.
5. Tests cover each outcome on both execution paths and a multi-step task containing an unverified action.

## Non-goals

- Adding new device-control skills or integrations.
- Improving general factual-answer accuracy outside automation results.
- Broad redesign of the agent loop, permissions, autonomy policy, or tool schemas.
- Claiming an external delivery or state change is confirmed when Android does not expose observable evidence.

## Verification plan

Use focused unit tests for `VerificationEngine`, response formatting, and `GoalEvaluator`; add runtime-level coverage for direct versus agent dispatch and event feedback. Run the relevant unit tests first, then the full `testDebugUnitTest` suite and Android debug compilation. Manually exercise one verifiable action, one successful-but-unobservable dispatch, and one failed action on-device if a device is available.
