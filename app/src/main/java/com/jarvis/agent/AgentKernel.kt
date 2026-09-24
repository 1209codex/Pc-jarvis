package com.jarvis.agent

import android.util.Log
import com.jarvis.agent.skills.*
import com.jarvis.ai.LlmPlanner
import com.jarvis.execution.VerificationEngine
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.TaskStateManager
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class AgentKernel(
    private val planner: LlmPlanner?,
    private val contextRouter: AgentContextRouter,
    private val executor: ToolExecutor,
    private val verifier: VerificationEngine,
    private val taskState: TaskStateManager,
    private val memory: AugmentedMemoryPipeline,
    private val recovery: AgentRecoveryManager = AgentRecoveryManager(),
    private val goalEvaluator: GoalEvaluator = GoalEvaluator(),
    private val experienceManager: AgentExperienceManager = AgentExperienceManager(taskState, memory),
    private val skillRegistry: SkillRegistry,
    private val learnedSkillStore: com.jarvis.skilllearning.LearnedSkillStore? = null,
    private val checkpointManager: com.jarvis.reliability.CheckpointManager? = null,
    private val skillDiscoveryEngine: SkillDiscoveryEngine? = null
) {
    suspend fun run(
        goal: String,
        userApprovalGranted: Boolean = false,
        history: List<com.jarvis.ai.Message> = emptyList(),
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentResult {
        val taskId = taskState.createTask(goal)
        taskState.updateTaskStatus(taskId, "RUNNING")
        onEvent(AgentEvent.TaskStarted(taskId))
        Log.i(TAG, "[AGENT] taskId=$taskId goal=\"$goal\" starting closed-loop execution")

        val state = AgentState(taskId = taskId, goal = goal)
        val workingMemory = AgentWorkingMemory(goal = goal)

        val decomposed = MultiTaskDecomposer.decompose(goal)
        val parsedComplex = ComplexCommandParser.parse(goal)
        val subGoals = if (decomposed.size > 1) {
            decomposed
        } else if (parsedComplex.segments.size > 1 && parsedComplex.segments.all { MultiTaskDecomposer.hasActionVerb(it.text) }) {
            parsedComplex.segments.map { it.text }
        } else {
            emptyList()
        }
        if (subGoals.size > 1) {
            workingMemory.subGoals.addAll(subGoals)
            Log.i(TAG, "[MULTI-TASK] Decomposed compound goal '$goal' (${parsedComplex.wordCount} words, conditionals=${parsedComplex.hasConditionals}) into ${subGoals.size} sub-goals: $subGoals")
        }

        val skillProposal = skillDiscoveryEngine?.discoverProposedSkill(parsedComplex)
        if (skillProposal != null && parsedComplex.isSkillTeachingIntent) {
            workingMemory.facts["skill_proposal_id"] = skillProposal.skillId
            workingMemory.facts["skill_proposal_prompt"] = skillProposal.clarificationPrompt
        }

        val guard = AgentLoopGuard()
        val stepObservations = mutableListOf<AgentObservation>()

        // Check for relevant past experience
        val experiences = experienceManager.findRelevantExperience(goal)
        if (experiences.isNotEmpty()) {
            val best = experiences.first()
            workingMemory.facts["previous_experience"] = "When '${best.goalPattern}': ${best.strategy}"
            Log.i(TAG, "[EXPERIENCE] Found past experience: ${best.strategy}")
        }

        return executeLoop(
            taskId = taskId,
            goal = goal,
            state = state,
            workingMemory = workingMemory,
            guard = guard,
            stepObservations = stepObservations,
            userApprovalGranted = userApprovalGranted,
            history = history,
            onEvent = onEvent
        )
    }

    suspend fun resumeTask(
        taskId: Long,
        clarification: String,
        userApprovalGranted: Boolean = false,
        history: List<com.jarvis.ai.Message> = emptyList(),
        onEvent: (AgentEvent) -> Unit = {}
    ): AgentResult {
        Log.i(TAG, "[AGENT] Resuming task $taskId with clarification: \"$clarification\"")
        taskState.updateTaskStatus(taskId, "RUNNING")
        onEvent(AgentEvent.TaskStarted(taskId))

        val checkpoint = checkpointManager?.getLatestCheckpoint(taskId)
        val (restoredState, restoredMemory) = if (checkpoint != null) {
            restoreState(checkpoint.stateJson) ?: Pair(
                AgentState(taskId = taskId, goal = clarification),
                AgentWorkingMemory(goal = clarification)
            )
        } else {
            val task = taskState.getTask(taskId)
            val g = task?.goal ?: clarification
            Pair(AgentState(taskId = taskId, goal = g), AgentWorkingMemory(goal = g))
        }

        val goal = restoredState.goal
        if (restoredMemory.subGoals.isEmpty()) {
            val subGoals = MultiTaskDecomposer.decompose(goal)
            if (subGoals.size > 1) {
                restoredMemory.subGoals.addAll(subGoals)
            }
        }
        restoredMemory.facts["user_clarification"] = clarification

        // Resolve ordinal/candidate selections from user clarification (e.g., "second wala", "pehla", "2", "option 1")
        val lowerClarification = clarification.lowercase().trim()
        val candidateIndex = when {
            lowerClarification.contains("pehla") || lowerClarification.contains("first") || lowerClarification.contains("1st") || lowerClarification == "1" -> 0
            lowerClarification.contains("second") || lowerClarification.contains("doosra") || lowerClarification.contains("2nd") || lowerClarification == "2" -> 1
            lowerClarification.contains("third") || lowerClarification.contains("teesra") || lowerClarification.contains("3rd") || lowerClarification == "3" -> 2
            else -> -1
        }

        if (candidateIndex >= 0 && candidateIndex < restoredMemory.candidates.size) {
            val chosenCandidate = restoredMemory.candidates[candidateIndex]
            val cleanTitle = chosenCandidate.substringBefore("(").trim()
            restoredMemory.facts["selected_target"] = cleanTitle
            restoredMemory.facts["query"] = cleanTitle
            Log.i(TAG, "[RESUME] Resolved candidate index $candidateIndex: $cleanTitle")
        } else {
            restoredMemory.facts["query"] = clarification
        }

        if (restoredState.iteration >= 12) {
            val limitMsg = "Cannot resume task $taskId: iteration limit already reached (${restoredState.iteration}/12)"
            Log.w(TAG, "[RESUME] $limitMsg")
            taskState.updateTaskStatus(taskId, "FAILED", limitMsg)
            onEvent(AgentEvent.Failed(limitMsg))
            return AgentResult(
                status = AgentResultStatus.FAILED,
                response = limitMsg,
                taskId = taskId,
                iterations = restoredState.iteration,
                failureReason = limitMsg
            )
        }

        val guard = AgentLoopGuard(startingIteration = restoredState.iteration)
        val stepObservations = mutableListOf<AgentObservation>()

        // An affirmative reply to a confirmation question grants approval, so
        // confirmation-gated actions (WhatsApp send, file writes) can finally
        // execute instead of bouncing off NeedsConfirmation forever.
        val consent = clarification.lowercase().trim().split(Regex("\\s+")).any {
            it in setOf("yes", "yep", "yeah", "yup", "haan", "hae", "confirm", "sure",
                "ok", "okay", "theek", "thik", "send", "bhej", "bhejo", "do", "karo", "kardo")
        }

        return executeLoop(
            taskId = taskId,
            goal = goal,
            state = restoredState,
            workingMemory = restoredMemory,
            guard = guard,
            stepObservations = stepObservations,
            userApprovalGranted = userApprovalGranted || consent,
            history = history,
            onEvent = onEvent
        )
    }

    private suspend fun executeLoop(
        taskId: Long,
        goal: String,
        state: AgentState,
        workingMemory: AgentWorkingMemory,
        guard: AgentLoopGuard,
        stepObservations: MutableList<AgentObservation>,
        userApprovalGranted: Boolean,
        history: List<com.jarvis.ai.Message> = emptyList(),
        onEvent: (AgentEvent) -> Unit
    ): AgentResult {
        try {
            val executedVerified = mutableListOf<Pair<AgentAction, VerificationResult>>()
            while (true) {
                val iterCheck = guard.checkNextIteration()
                if (iterCheck is AgentLoopGuard.GuardCheckResult.Exceeded) {
                    val limitMsg = iterCheck.reason
                    Log.w(TAG, "[GUARD] $limitMsg")
                    state.status = AgentStatus.FAILED
                    taskState.updateTaskStatus(taskId, "FAILED", limitMsg)
                    onEvent(AgentEvent.Failed(limitMsg))
                    return AgentResult(AgentResultStatus.FAILED, limitMsg, taskId, state.iteration, stepObservations, limitMsg)
                }
                guard.nextIteration()
                state.iteration = guard.currentIteration()

                val activeSubGoalIndex = if (workingMemory.subGoals.isNotEmpty()) {
                    workingMemory.currentPendingSubGoalIndex()
                } else -1

                val activeGoal = if (activeSubGoalIndex in workingMemory.subGoals.indices) {
                    workingMemory.subGoals[activeSubGoalIndex]
                } else {
                    goal
                }

                state.currentObjective = activeGoal
                taskState.updateTaskIteration(taskId, state.iteration, state.currentObjective)

                state.status = AgentStatus.CONTEXT_BUILDING
                onEvent(AgentEvent.BuildingContext)
                val context = contextRouter.buildContext(activeGoal, state, workingMemory, history)

                state.status = AgentStatus.DECIDING
                onEvent(AgentEvent.Thinking)
                val toolsDescription = executor.registry.getAvailableToolsDescription()

                val decision = if (state.pendingApproval != null && userApprovalGranted) {
                    // The user approved the previously blocked confirmation-gated action.
                    // Replay it directly — no planner round-trip, so an affirmative reply can
                    // never bounce back into another clarification.
                    val pending = state.pendingApproval!!
                    state.pendingApproval = null
                    Log.i(TAG, "[APPROVED] Replaying action ${pending.type} with user approval")
                    AgentDecision(
                        type = DecisionType.ACT,
                        action = AgentAction(pending.type, pending.params),
                        reasonCode = "user_approved_confirmation"
                    )
                } else {
                    // Deterministic local planning wins for patterns it knows (compound
                    // open+play, skills, "open X", "play Y", flashlight...): cheaper, and not
                    // hostage to a model that researches instead of acting. The LLM planner
                    // is consulted only for requests the local planner cannot recognize.
                    val local = fallbackDecision(activeGoal, state, workingMemory)
                    if (local.reasonCode != "UNRECOGNIZED_FALLBACK") {
                        local
                    } else if (planner != null) {
                        planner.decide(
                            history = history,
                            goal = activeGoal,
                            state = state,
                            workingMemory = workingMemory,
                            context = context,
                            availableTools = toolsDescription
                        ).getOrElse {
                            Log.w(TAG, "Planner decision error: ${it.message}, using rule fallback")
                            fallbackDecision(activeGoal, state, workingMemory)
                        }
                    } else {
                        local
                    }
                }

                state.lastDecision = decision
                taskState.recordDecision(
                    taskId = taskId,
                    iteration = state.iteration,
                    decisionType = decision.type.name,
                    reasonCode = decision.reasonCode,
                    actionType = decision.action?.type,
                    paramsJson = decision.action?.params?.toString()
                )

                when (decision.type) {
                    DecisionType.ACT -> {
                        val action = decision.action
                        if (action == null) {
                            // A malformed planner decision must never crash the whole task
                            // into the generic "Agent failed" catch (same failure shape as
                            // the old require(rounds < 3) research crash). Log and replan —
                            // the iteration guard bounds a planner that keeps returning
                            // empty ACTs.
                            Log.w(TAG, "[ACT] Decision returned ACT without an action payload; replanning")
                            workingMemory.failedSteps.add("ACT(missing_action)")
                            continue
                        }
                        state.lastAction = action
                        val actionHash = "${action.type}:${action.params.toSortedMap()}"

                        // An identical ACT already ran this task. Re-firing unverified
                        // actions is the classic infinite loop (media search screens,
                        // unverifiable dispatches) — stop honestly instead of letting
                        // the loop guard crash the run. A VERIFIED repeat is skipped
                        // and only re-evaluated (no duplicate side effect).
                        val priorAct = state.executedActs[actionHash]
                        if (priorAct != null) {
                            // No guard.recordToolCall here: this branch executes nothing and
                            // always returns below. Recording a phantom tool call inflated
                            // the tool-call budget AND could throw the same-action require()
                            // (via interleaved RETRY recordings) before the honest handling —
                            // the "infinite repetitive action loop" crash the session log kept
                            // hitting. Repetition is resolved honestly by the branches below.
                            if (priorAct.verification.verified) {
                                state.lastObservation = priorAct.observation
                                workingMemory.lastToolResult = priorAct.result.message
                                val evaluation = goalEvaluator.evaluate(activeGoal, state, priorAct.observation, priorAct.verification)
                                if (evaluation.satisfied) {
                                    if (activeSubGoalIndex != -1) {
                                        workingMemory.completedSubGoals.add(activeSubGoalIndex)
                                    }
                                    if (workingMemory.isAllSubGoalsCompleted()) {
                                        state.goalSatisfied = true
                                        state.status = AgentStatus.COMPLETED
                                        val successResponse = if (workingMemory.subGoals.size > 1) {
                                            "Done. Completed ${workingMemory.subGoals.joinToString(", ")}."
                                        } else {
                                            priorAct.result.message
                                        }
                                        taskState.updateTaskStatus(taskId, "COMPLETED", successResponse)
                                        learnedSkillStore?.learnFromRun(goal, executedVerified, ::riskOf)
                                        onEvent(AgentEvent.Completed(successResponse))
                                        return AgentResult(AgentResultStatus.COMPLETED, successResponse, taskId, state.iteration, stepObservations, verified = true, verifiedActions = executedVerified)
                                    } else {
                                        continue
                                    }
                                }
                            }
                            // The action genuinely EXECUTED (tool succeeded) but the outcome
                            // can't be confirmed (media on screen, external dispatch). Failing
                            // it here punishes a task whose action already ran — instead
                            // complete honestly with a clean message. Only when verification is
                            // UNKNOWN; a FAILED verification still fails the task.
                            if (priorAct.result.success && priorAct.verification.status == VerificationStatus.UNKNOWN) {
                                if (activeSubGoalIndex != -1) {
                                    workingMemory.completedSubGoals.add(activeSubGoalIndex)
                                }
                                if (workingMemory.isAllSubGoalsCompleted()) {
                                    state.status = AgentStatus.COMPLETED
                                    val caveat = priorAct.result.message
                                    taskState.updateTaskStatus(taskId, "COMPLETED", caveat)
                                    onEvent(AgentEvent.Completed(caveat))
                                    return AgentResult(AgentResultStatus.COMPLETED, caveat, taskId, state.iteration, stepObservations, verified = false)
                                } else {
                                    continue
                                }
                            }
                            state.status = AgentStatus.FAILED
                            val failMsg = "Action ${action.type}(${action.params}) was already attempted without confirmable success for goal '$activeGoal'. ${priorAct.verification.message}"
                            taskState.updateTaskStatus(taskId, "FAILED", failMsg)
                            onEvent(AgentEvent.Failed(failMsg))
                            return AgentResult(AgentResultStatus.FAILED, failMsg, taskId, state.iteration, stepObservations, failMsg)
                        }

                        val toolCheck = guard.checkToolCall(action.type, action.params)
                        if (toolCheck is AgentLoopGuard.GuardCheckResult.Exceeded) {
                            val limitMsg = toolCheck.reason
                            Log.w(TAG, "[GUARD] $limitMsg")
                            state.status = AgentStatus.FAILED
                            taskState.updateTaskStatus(taskId, "FAILED", limitMsg)
                            onEvent(AgentEvent.Failed(limitMsg))
                            return AgentResult(AgentResultStatus.FAILED, limitMsg, taskId, state.iteration, stepObservations, limitMsg)
                        }
                        guard.recordToolCall(action.type, action.params)
                        val tool = executor.registry.get(action.type)
                        val isSideEffect = action.type.uppercase() in listOf("WHATSAPP", "WHATSAPP_SEND", "FILE_WRITE") ||
                                           tool?.policy?.idempotent == false

                        // Idempotency claim BEFORE dispatch: a success whose response we missed
                        // (timeout, lost reply) must never fire the same side effect twice.
                        val alreadyExecuted = isSideEffect && checkpointManager?.isActionIdempotentDuplicate(actionHash) == true

                        val toolResult = if (alreadyExecuted) {
                            Log.w(TAG, "[IDEMPOTENCY] Action $actionHash already claimed executed. Skipping to prevent duplicate side effect.")
                            ToolResult.Success("Action '${action.type}' already executed successfully earlier.")
                        } else {
                            if (isSideEffect) {
                                checkpointManager?.recordActionExecution(actionHash, action.type)
                            }
                            state.status = AgentStatus.EXECUTING
                            onEvent(AgentEvent.Executing(action.type))
                            val firstAttempt = executor.execute(action.type, action.params, userApprovalGranted)
                            if (firstAttempt is ToolResult.NeedsConfirmation) {
                                // In 100% autonomous mode, automatically grant approval and execute immediately!
                                Log.i(TAG, "[AUTONOMOUS] Auto-granting approval for action '${action.type}' and executing immediately.")
                                // The unapproved attempt returned before dispatch (policy gate),
                                // so re-assert the idempotency claim before the approved dispatch —
                                // this is the call that actually performs the side effect, and a
                                // lost response must never let it fire twice (duplicate WhatsApp
                                // message / file write). Cleared only if the approved attempt fails.
                                if (isSideEffect) {
                                    checkpointManager?.recordActionExecution(actionHash, action.type)
                                }
                                val approved = executor.execute(action.type, action.params, userApprovalGranted = true)
                                if (!approved.success && isSideEffect) {
                                    checkpointManager?.clearActionExecution(actionHash)
                                }
                                approved
                            } else {
                                if (!firstAttempt.success && isSideEffect) {
                                    checkpointManager?.clearActionExecution(actionHash)
                                }
                                firstAttempt
                            }
                        }

                        state.status = AgentStatus.OBSERVING
                        val observation = AgentObservation(
                            source = "TOOL",
                            success = toolResult.success,
                            message = toolResult.message
                        )
                        state.lastObservation = observation
                        stepObservations.add(observation)
                        workingMemory.lastToolResult = toolResult.message
                        if (toolResult.success) {
                            state.completedSteps++
                            workingMemory.completedSteps.add("${action.type}(${action.params})")
                        } else {
                            workingMemory.failedSteps.add("${action.type}(${action.params})")
                        }

                        taskState.recordObservation(
                            taskId = taskId,
                            stepId = null,
                            source = "TOOL",
                            success = toolResult.success,
                            message = toolResult.message
                        )

                        state.status = AgentStatus.VERIFYING
                        onEvent(AgentEvent.Verifying(action.type))
                        val verification = verifier.verify(action.type, action.params, toolResult)
                        state.executedActs[actionHash] = ExecutedAct(toolResult, observation, verification)
                        if (verification.verified) {
                            executedVerified.add(action to verification)
                            learnedSkillStore?.confirmStepVerified(workingMemory.facts)
                        } else if (workingMemory.facts.containsKey("_learned_skill")) {
                            learnedSkillStore?.abandon(workingMemory.facts)
                        }

                        // Accumulate independently-satisfied requirements: a criterion is
                        // marked satisfied only by the action that satisfies it, when verified.
                        if (verification.verified) {
                            state.lastDecision?.successCriteria?.forEach { crit ->
                                val key = crit.lowercase().trim()
                                if (key.isNotBlank() && key !in state.satisfiedCriteria &&
                                    goalEvaluator.satisfiedBy(action, verification, crit)
                                ) {
                                    state.satisfiedCriteria.add(key)
                                }
                            }
                        }

                        // Only a hard FAILED outcome triggers recovery. UNKNOWN is neither a
                        // success nor a failure: it never satisfies the goal, and it never
                        // burns a recovery budget either.
                        val outComeFailed = !toolResult.success || verification.status == VerificationStatus.FAILED
                        if (outComeFailed) {
                            state.failureCount++
                            val recoveryPlan = recovery.determineRecovery(action, observation, state, workingMemory)
                            onEvent(AgentEvent.Recovering(recoveryPlan.explanation))
                            Log.w(TAG, "[RECOVERY] ${recoveryPlan.explanation}")

                            if (recoveryPlan.strategy == RecoveryStrategy.ABORT) {
                                state.status = AgentStatus.FAILED
                                val failMsg = "Could not accomplish '$activeGoal': ${toolResult.message}"
                                taskState.updateTaskStatus(taskId, "FAILED", failMsg)
                                onEvent(AgentEvent.Failed(failMsg))
                                return AgentResult(AgentResultStatus.FAILED, failMsg, taskId, state.iteration, stepObservations, toolResult.message)
                            } else if (recoveryPlan.modifiedAction != null) {
                                state.lastAction = recoveryPlan.modifiedAction
                            }
                            continue
                        }

                        // Evaluate Goal Satisfaction — UNKNOWN verification can never satisfy here.
                        val evaluation = goalEvaluator.evaluate(activeGoal, state, observation, verification)
                        if (evaluation.satisfied) {
                            if (activeSubGoalIndex != -1) {
                                workingMemory.completedSubGoals.add(activeSubGoalIndex)
                                Log.i(TAG, "[MULTI-TASK] Sub-goal ${activeSubGoalIndex + 1}/${workingMemory.subGoals.size} satisfied: '$activeGoal'")
                            }

                            if (workingMemory.isAllSubGoalsCompleted()) {
                                state.goalSatisfied = true
                                state.status = AgentStatus.COMPLETED
                                val successResponse = if (workingMemory.subGoals.size > 1) {
                                    "Done. Completed ${workingMemory.subGoals.joinToString(" and ")}."
                                } else {
                                    toolResult.message
                                }
                                taskState.updateTaskStatus(taskId, "COMPLETED", successResponse)

                                // Save successful experience
                                experienceManager.recordExperience(
                                    Experience(
                                        taskType = action.type,
                                        goalPattern = goal,
                                        strategy = "Executed ${action.type} with ${action.params}",
                                        action = action.type,
                                        result = toolResult.message,
                                        success = true
                                    )
                                )

                                learnedSkillStore?.learnFromRun(goal, executedVerified, ::riskOf)

                                onEvent(AgentEvent.Completed(successResponse))
                                return AgentResult(AgentResultStatus.COMPLETED, successResponse, taskId, state.iteration, stepObservations, verified = verification.verified, verifiedActions = executedVerified)
                            } else {
                                // Sub-goal satisfied, more sub-goals remaining; advance to next loop iteration
                                continue
                            }
                        }
                    }

                    DecisionType.RESEARCH -> {
                        val query = decision.action?.params?.get("query")?.ifBlank { goal } ?: goal
                        onEvent(AgentEvent.Researching(query))
                        val rounds = workingMemory.facts["research_rounds"]?.toIntOrNull() ?: 0
                        // Research is a finite budget: when spent, fail HONESTLY instead of
                        // throwing — a require() here crashes the task into a generic ERROR.
                        // Checked BEFORE the guard records: a 4th identical RESEARCH query
                        // must return this honest FAILED, never throw "infinite repetitive
                        // action loop" out of recordToolCall.
                        if (rounds >= 3) {
                            val limitMsg = "Research round limit (3) exceeded for query: $query"
                            Log.w(TAG, "[RESEARCH] $limitMsg")
                            state.status = AgentStatus.FAILED
                            taskState.updateTaskStatus(taskId, "FAILED", limitMsg)
                            onEvent(AgentEvent.Failed(limitMsg))
                            return AgentResult(AgentResultStatus.FAILED, limitMsg, taskId, state.iteration, stepObservations, limitMsg)
                        }
                        // Repeated research is a loop like any other: count it in the loop
                        // guard — checked first (non-throwing), then recorded, mirroring ACT.
                        val researchCheck = guard.checkToolCall("RESEARCH", mapOf("query" to query))
                        if (researchCheck is AgentLoopGuard.GuardCheckResult.Exceeded) {
                            val limitMsg = researchCheck.reason
                            Log.w(TAG, "[GUARD] $limitMsg")
                            state.status = AgentStatus.FAILED
                            taskState.updateTaskStatus(taskId, "FAILED", limitMsg)
                            onEvent(AgentEvent.Failed(limitMsg))
                            return AgentResult(AgentResultStatus.FAILED, limitMsg, taskId, state.iteration, stepObservations, limitMsg)
                        }
                        guard.recordToolCall("RESEARCH", mapOf("query" to query))
                        val searchResult = executor.execute("SEARCH_WEB", mapOf("query" to query))
                        workingMemory.candidates.clear()
                        if (searchResult.success && searchResult.message.isNotBlank()) {
                            val lines = searchResult.message.lines().filter { it.isNotBlank() }
                            lines.take(5).forEach { line ->
                                val cleaned = line.trim().trimStart('-', '*', '1', '2', '3', '4', '5', '.', ' ')
                                if (cleaned.isNotBlank()) {
                                    workingMemory.candidates.add(cleaned)
                                }
                            }
                            workingMemory.facts["research_summary"] =
                                workingMemory.candidates.take(3).joinToString("; ")
                        }

                        workingMemory.facts["research_rounds"] = (rounds + 1).toString()

                        if (!searchResult.success || workingMemory.candidates.isEmpty()) {
                            workingMemory.failedSteps.add("RESEARCH($query)")
                            workingMemory.lastToolResult = searchResult.message
                        } else {
                            Log.i(TAG, "[RESEARCH] Gathered ${workingMemory.candidates.size} candidates for query: $query")
                        }
                    }

                    DecisionType.RETRIEVE_MEMORY -> {
                        onEvent(AgentEvent.UsingMemory("Searching personal memories"))
                        val memories = memory.retrieveMag(goal, limit = 3)
                        memories.forEach { m -> workingMemory.facts[m.key] = m.content }
                    }

                    DecisionType.RETRIEVE_KNOWLEDGE -> {
                        onEvent(AgentEvent.UsingMemory("Searching local knowledge"))
                        val knowledge = memory.retrieveRag(goal, limit = 3)
                        knowledge.forEach { k -> workingMemory.facts[k.key] = k.content }
                    }

                    DecisionType.ASK_USER -> {
                        state.status = AgentStatus.WAITING_FOR_USER
                        val question = decision.question ?: "Could you please clarify what you'd like me to do?"
                        taskState.updateTaskStatus(taskId, "WAITING_FOR_USER", question)

                        // Save complete state checkpoint for seamless resumption
                        val stateJson = serializeState(state, workingMemory)
                        checkpointManager?.saveCheckpoint(taskId, state.iteration, stateJson)

                        onEvent(AgentEvent.WaitingForUser(question))
                        return AgentResult(AgentResultStatus.WAITING_FOR_USER, question, taskId, state.iteration, stepObservations)
                    }

                    DecisionType.COMPLETE -> {
                        // A malformed LLM can never force COMPLETED: complete only when the
                        // goal was actually satisfied by a verified action. Otherwise replan.
                        if (state.goalSatisfied) {
                            state.status = AgentStatus.COMPLETED
                            val resp = decision.question ?: "Task completed."
                            taskState.updateTaskStatus(taskId, "COMPLETED", resp)
                            onEvent(AgentEvent.Completed(resp))
                            return AgentResult(AgentResultStatus.COMPLETED, resp, taskId, state.iteration, stepObservations, verified = executedVerified.isNotEmpty())
                        }
                        Log.w(TAG, "[COMPLETE] Rejected completion for goal '$goal' — goal not actually satisfied. Replanning.")
                    }

                    DecisionType.RETRY -> {
                        state.failureCount++
                        onEvent(AgentEvent.Recovering(decision.reasonCode.ifBlank { "Retrying step..." }))
                        // No guard.recordToolCall here: RETRY executes nothing — the actual
                        // re-execution goes through the ACT path next iteration (which
                        // check+records the guard). The phantom recording double-counted the
                        // same-action repetition counter and could throw BEFORE the honest
                        // FAILED check below (crash instead of "Task failed after multiple
                        // retries"). Pure retry loops stay bounded by failureCount.
                        if (state.failureCount > 3) {
                            state.status = AgentStatus.FAILED
                            val failMsg = decision.question ?: "Task failed after multiple retries: $goal"
                            taskState.updateTaskStatus(taskId, "FAILED", failMsg)
                            onEvent(AgentEvent.Failed(failMsg))
                            return AgentResult(AgentResultStatus.FAILED, failMsg, taskId, state.iteration, stepObservations, failMsg)
                        }
                        // Re-execute the last action with an incremented attempt (bounded by the loop guard).
                        val lastAction = state.lastAction
                        if (lastAction != null) {
                            state.lastAction = lastAction.copy(attempt = lastAction.attempt + 1)
                        }
                        workingMemory.facts["last_retry_reason"] = decision.reasonCode
                        Log.w(TAG, "[RETRY] Iteration ${state.iteration}, reason: ${decision.reasonCode}")
                    }

                    DecisionType.RECOVER -> {
                        state.failureCount++
                        val recoveryObjective = decision.question ?: "Attempting recovery strategy"
                        onEvent(AgentEvent.Recovering(recoveryObjective))
                        if (state.failureCount > 3) {
                            state.status = AgentStatus.FAILED
                            val failMsg = "Recovery limit exceeded for goal: $goal"
                            taskState.updateTaskStatus(taskId, "FAILED", failMsg)
                            onEvent(AgentEvent.Failed(failMsg))
                            return AgentResult(AgentResultStatus.FAILED, failMsg, taskId, state.iteration, stepObservations, failMsg)
                        }
                        if (decision.action != null) {
                            val recoveryAction = decision.action
                            val actionHash = "${recoveryAction.type}:${recoveryAction.params.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }}"
                            state.lastAction = recoveryAction
                            val recoveryResult = executor.execute(recoveryAction.type, recoveryAction.params, userApprovalGranted)
                            // The recovery action itself runs through the same
                            // execute -> observe -> verify cycle as a normal ACT.
                            val recoveryVerification = verifier.verify(recoveryAction.type, recoveryAction.params, recoveryResult)
                            val recoverySucceeded = recoveryResult.success && recoveryVerification.status != VerificationStatus.FAILED
                            val recoveryObs = AgentObservation(
                                "RECOVERY",
                                recoverySucceeded,
                                "${recoveryResult.message} [${recoveryVerification.status}] ${recoveryVerification.message}"
                            )
                            stepObservations.add(recoveryObs)
                            state.executedActs[actionHash] = ExecutedAct(recoveryResult, recoveryObs, recoveryVerification)
                            workingMemory.lastToolResult = recoveryResult.message
                            workingMemory.facts["last_recovery_verification"] = recoveryVerification.status.name
                            if (!recoverySucceeded) {
                                workingMemory.failedSteps.add("RECOVERY(${recoveryAction.type})")
                            }
                        }
                        workingMemory.facts["last_recovery_attempt"] = recoveryObjective
                    }

                    DecisionType.FAIL -> {
                        state.status = AgentStatus.FAILED
                        val failMsg = decision.question ?: "Task failed to achieve goal: $goal"
                        taskState.updateTaskStatus(taskId, "FAILED", failMsg)
                        onEvent(AgentEvent.Failed(failMsg))
                        return AgentResult(AgentResultStatus.FAILED, failMsg, taskId, state.iteration, stepObservations, failMsg)
                    }
                }
            }
        } catch (e: CancellationException) {
            state.status = AgentStatus.CANCELLED
            taskState.updateTaskStatus(taskId, "CANCELLED", "Task cancelled by user")
            onEvent(AgentEvent.Cancelled("Task cancelled by user"))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent execution crashed on task $taskId", e)
            state.status = AgentStatus.FAILED
            val err = "Agent failed: ${e.message}"
            taskState.updateTaskStatus(taskId, "FAILED", err)
            onEvent(AgentEvent.Failed(err))
            return AgentResult(AgentResultStatus.FAILED, err, taskId, state.iteration, stepObservations, err)
        }
    }

    internal suspend fun fallbackDecision(goal: String, state: AgentState, workingMemory: AgentWorkingMemory): AgentDecision {
        val lower = goal.lowercase().trim()

        // Replay a previously-verified learned workflow when it still applies,
        // before any rule guessing: cheaper and more reliable than replanning.
        val learnedStep = learnedSkillStore?.takeStep(goal, workingMemory.facts)
        if (learnedStep != null) {
            Log.i(
                TAG,
                "[LEARNED] Step ${learnedStep.stepIndex + 1}/${learnedStep.totalSteps} of '${learnedStep.skillId}' -> ${learnedStep.action.type}"
            )
            return AgentDecision(
                type = DecisionType.ACT,
                action = learnedStep.action,
                reasonCode = "LEARNED_SKILL_STEP",
                successCriteria = listOf("learned step ${learnedStep.stepIndex + 1} completed")
            )
        }

        val isCreation = lower.contains("website") || lower.contains("webapp") || lower.contains("web app") ||
                lower.contains("banao") || lower.contains("bana") || lower.contains("create") ||
                lower.contains("build") || lower.contains("make") || lower.contains("code") || lower.contains("develop") || lower.contains("design")

        // 1. Check for compound multi-step requests like "YouTube kholo aur comedy video chalao"
        val isMultiStep = !isCreation && (lower.contains("kholo") || lower.contains("open")) &&
                (lower.contains("chalao") || lower.contains("play") || lower.contains("bajao"))

        if (isMultiStep) {
            val appOpened = state.completedSteps > 0 || workingMemory.completedSteps.any { it.startsWith("OPEN_APP") }
            if (!appOpened) {
                // If OPEN_APP previously failed, do not falsely advance to media playback!
                val appOpenFailed = workingMemory.failedSteps.any { it.startsWith("OPEN_APP") }
                if (appOpenFailed) {
                    val targetApp = if (lower.contains("youtube")) "YouTube" else "requested app"
                    return AgentDecision(
                        type = DecisionType.FAIL,
                        reasonCode = "OPEN_APP_FAILED",
                        question = "Could not open $targetApp. Please check that the app is installed and try again."
                    )
                }
                val app = if (lower.contains("youtube")) "youtube" else "music"
                return AgentDecision(
                    type = DecisionType.ACT,
                    action = AgentAction("OPEN_APP", mapOf("app" to app)),
                    reasonCode = "STEP_1_OPEN_APP",
                    successCriteria = listOf(if (lower.contains("youtube")) "YouTube open" else "music open")
                )
            } else {
                // Step 2: Play the requested media
                val extractedQuery = lower
                    .replace(Regex("\\b(open|kholo|youtube|music|aur|and|then|play|chalao|bajao|video|song|gaana|sunao)\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val candidateQuery = workingMemory.facts["selected_target"]
                    ?: workingMemory.candidates.firstOrNull()?.substringBefore("(")?.trim()
                    ?: if (extractedQuery.isNotBlank()) extractedQuery else if (lower.contains("comedy")) "popular standup comedy hindi" else "top trending songs"
                return AgentDecision(
                    type = DecisionType.ACT,
                    action = AgentAction("YOUTUBE_PLAY", mapOf("query" to candidateQuery)),
                    reasonCode = "STEP_2_PLAY_MEDIA",
                    successCriteria = listOf(if (lower.contains("youtube")) "YouTube open" else "music open", "$candidateQuery playing")
                )
            }
        }

        // 1b. Check for compound open + scroll requests like "YouTube kholo aur scroll karo"
        val isMultiStepScroll = !isCreation && (lower.contains("kholo") || lower.contains("open")) &&
                (lower.contains("scroll") || lower.contains("swipe"))
        if (isMultiStepScroll) {
            val appOpened = state.completedSteps > 0 || workingMemory.completedSteps.any { it.startsWith("OPEN_APP") }
            if (!appOpened) {
                val app = when {
                    lower.contains("youtube") -> "youtube"
                    lower.contains("insta") -> "instagram"
                    lower.contains("chrome") -> "chrome"
                    else -> "youtube"
                }
                return AgentDecision(
                    type = DecisionType.ACT,
                    action = AgentAction("OPEN_APP", mapOf("app" to app)),
                    reasonCode = "STEP_1_OPEN_APP",
                    successCriteria = listOf("$app open", "feed scrolled")
                )
            } else {
                val dir = if (lower.contains("up") || lower.contains("uper")) "up" else "down"
                return AgentDecision(
                    type = DecisionType.ACT,
                    action = AgentAction("UI_SCROLL", mapOf("direction" to dir)),
                    reasonCode = "STEP_2_SCROLL",
                    successCriteria = listOf("feed scrolled")
                )
            }
        }

        // 2. Check registered Skills
        val skillContext = SkillContext(
            goal = goal,
            workingMemory = workingMemory,
            userPreferences = workingMemory.facts
        )
        val matchedSkill = skillRegistry.findSkill(goal, skillContext)
        if (matchedSkill != null) {
            val skillResult = matchedSkill.execute(goal, skillContext)
            if (skillResult.handled && skillResult.proposedAction != null) {
                // If research candidates were suggested, populate workingMemory
                if (skillResult.candidateQueries.isNotEmpty() && workingMemory.candidates.isEmpty()) {
                    workingMemory.candidates.addAll(skillResult.candidateQueries)
                }
                Log.i(TAG, "[SKILL] Matched skill '${matchedSkill.name}': ${skillResult.explanation}")
                val criteria = when (skillResult.proposedAction.type.uppercase()) {
                    "YOUTUBE_PLAY", "MUSIC_PLAY", "YMUSIC_PLAY", "PLAY_MUSIC", "SPOTIFY_PLAY" ->
                        listOf(if (lower.contains("video") || lower.contains("comedy")) "video playing" else "media playing")
                    "OPEN_APP" ->
                        listOf("${skillResult.proposedAction.params["app"] ?: "app"} open")
                    "CLOSE_APP", "APPS_CLOSE_ALL" ->
                        listOf("app closed")
                    "TELEPHONY_CONTROL" ->
                        listOf("call dispatched")
                    "WHATSAPP", "WHATSAPP_SEND" ->
                        listOf("message dispatched")
                    "SEARCH_WEB", "SEARCH" ->
                        listOf("search completed")
                    else ->
                        listOf(skillResult.explanation.ifBlank { "${skillResult.proposedAction.type} completed" })
                }
                return AgentDecision(
                    type = DecisionType.ACT,
                    action = skillResult.proposedAction,
                    reasonCode = "SKILL_MATCH",
                    confidence = 0.9f,
                    successCriteria = criteria
                )
            }
        }

        // 3. Fallback rule-based matching
        return matchFallbackRules(goal, lower)
    }

    private fun riskOf(actionType: String): com.jarvis.foundation.RiskLevel =
        executor.registry.get(actionType)?.policy?.riskLevel ?: com.jarvis.foundation.RiskLevel.LOW

    companion object {
        private const val TAG = "AgentKernel"

        /**
         * Deterministic fallback rule matching for common direct actions without LLM dependency.
         */
        fun matchFallbackRules(goal: String, lower: String = goal.lowercase().trim()): AgentDecision {
            val isCreation = lower.contains("website") || lower.contains("webapp") || lower.contains("web app") ||
                    lower.contains("banao") || lower.contains("bana") || lower.contains("create") ||
                    lower.contains("build") || lower.contains("make") || lower.contains("code") || lower.contains("develop") || lower.contains("design")

            return when {
                // Flashlight first so "stop torch", "torch band karo" reach it before media-stop.
                lower.contains("torch") || lower.contains("flashlight") || Regex("\\b(torch|flashlight)\\b").containsMatchIn(lower) ||
                    (Regex("\\blight\\b").containsMatchIn(lower) && !lower.contains("flight")) -> {
                    // Bare phrases default to ON; explicit off-words override. Word-bounded
                    // "off" so "office light" isn't read as off, and word-bounded "light"
                    // isn't pulled out of "flight mode".
                    val off = Regex("\\b(off|band|bujha|stop|hata)\\b").containsMatchIn(lower)
                    AgentDecision(
                        DecisionType.ACT,
                        AgentAction("FLASHLIGHT", mapOf("mode" to if (off) "off" else "on")),
                        successCriteria = listOf(if (off) "flashlight off" else "flashlight on")
                    )
                }
                !lower.contains("stopwatch") && !lower.contains("stop watch") && !lower.contains("bus stop") &&
                    (Regex("\\b(stop|pause|band karo|ruk jao)\\b").containsMatchIn(lower) &&
                        (lower.contains("music") || lower.contains("video") || lower.contains("song") || lower.contains("gaana") ||
                         lower.contains("media") || lower == "stop" || lower == "pause" || lower == "band karo" || lower == "ruk jao")) ->
                    AgentDecision(DecisionType.ACT, AgentAction("MEDIA_STOP", emptyMap()), successCriteria = listOf("media stopped"))
                !isCreation && (lower.contains("play") || lower.contains("chalao") || lower.contains("bajao") || lower.contains("gaana") || lower.contains("song")) &&
                    !lower.contains("stop") && !lower.contains("pause") && !lower.contains("band") -> {
                    val isYmusic = lower.contains("ymusic")
                    val q = lower
                        .replace(Regex("\\b(youtube|ymusic|pe|par|play|chalao|bajao|gaana|gana|song|music|sunao|laga|lagao|video)\\b", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { if (lower.contains("comedy")) "popular standup comedy hindi" else "trending songs" }
                    val toolType = if (isYmusic) "YMUSIC_PLAY" else "YOUTUBE_PLAY"
                    AgentDecision(
                        DecisionType.ACT,
                        AgentAction(toolType, mapOf("query" to q)),
                        successCriteria = listOf(if (lower.contains("video") || lower.contains("comedy")) "video playing" else "media playing")
                    )
                }
                Regex("\\b(instagram|insta)\\b").containsMatchIn(lower) -> {
                    val isMessages = lower.contains("message") || lower.contains("massage") || lower.contains("dm") || lower.contains("chat") || lower.contains("unread")
                    val isReels = lower.contains("reel") || lower.contains("reels")
                    val action = when {
                        isMessages -> "check_unread"
                        isReels -> "open_reels"
                        else -> "open"
                    }
                    AgentDecision(DecisionType.ACT, AgentAction("INSTAGRAM", mapOf("action" to action)), successCriteria = listOf("Instagram opened"))
                }
                lower.contains("ymusic") -> {
                    AgentDecision(DecisionType.ACT, AgentAction("OPEN_APP", mapOf("app" to "ymusic")), successCriteria = listOf("ymusic open"))
                }
                !isCreation && (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("kholo ") ||
                    lower.endsWith(" kholo") || lower.endsWith(" open") || lower.endsWith(" chalu karo") || lower.endsWith(" launch karo")) -> {
                    val appRaw = lower
                        .replace(Regex("\\b(open|launch|kholo|chalu karo|start|app|launch karo)\\b", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    val targetApp = when {
                        appRaw.contains("youtube") -> "youtube"
                        appRaw.contains("whatsapp") -> "whatsapp"
                        Regex("\\b(instagram|insta)\\b").containsMatchIn(appRaw) -> "instagram"
                        appRaw.contains("chrome") -> "chrome"
                        appRaw.contains("camera") -> "camera"
                        appRaw.contains("settings") -> "settings"
                        appRaw.contains("ymusic") -> "ymusic"
                        appRaw.contains("music") -> "music"
                        else -> appRaw.split(" ").firstOrNull().orEmpty().ifBlank { "app" }
                    }
                    AgentDecision(DecisionType.ACT, AgentAction("OPEN_APP", mapOf("app" to targetApp)), successCriteria = listOf("$targetApp open"))
                }
                lower.startsWith("search ") -> {
                    val q = lower.substringAfter("search ").trim()
                    AgentDecision(DecisionType.ACT, AgentAction("SEARCH_WEB", mapOf("query" to q)))
                }
                (lower.startsWith("call ") || lower.startsWith("dial ") || lower.contains("phone lagao") || lower.contains("call karo") ||
                    lower.contains("answer call") || lower.contains("phone uthao") || lower.contains("reject call") || lower.contains("call kaat") ||
                    lower.contains("send sms") || (lower.startsWith("sms ") && !lower.contains("read")) || lower.contains("otp") ||
                    lower.contains("sms padho") || lower.contains("read sms") || lower.contains("kiska sms")) -> {
                    val action = when {
                        lower.contains("answer") || lower.contains("uthao") || lower.contains("pick up") -> "answer_call"
                        lower.contains("reject") || lower.contains("kaat") || lower.contains("kato") || lower.contains("decline") -> "reject_call"
                        lower.contains("otp") || lower.contains("read sms") || lower.contains("sms padho") || lower.contains("kiska sms") -> "sms_read"
                        lower.contains("send sms") -> "sms_draft"
                        else -> "call"
                    }
                    val recipient = when (action) {
                        "sms_read" -> lower.substringAfter("from ").trim().ifBlank { "" }
                        else -> lower
                            .replace(Regex("^\\s*(call|dial|send sms|sms)\\s+"), " ")
                            .replace(Regex("\\b(to|ko)\\b\\s*"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }
                    AgentDecision(DecisionType.ACT, AgentAction("TELEPHONY_CONTROL", mapOf("action" to action, "recipient" to recipient)))
                }
                !lower.contains("mausam") && !lower.contains("weather") && !lower.contains("temp") &&
                    (lower.contains("agenda") || lower.contains("schedule") || lower.contains("calendar") || lower.contains("meeting schedule") ||
                     (Regex("\\b(aaj ka)\\b").containsMatchIn(lower) && (lower.contains("plan") || lower.contains("event") || lower.contains("meeting") || lower.contains("agenda")))) -> {
                    val action = if (lower.contains("add") || lower.contains("create") || lower.contains("banao")) "add_event" else "today_agenda"
                    AgentDecision(DecisionType.ACT, AgentAction("CALENDAR_MANAGE", mapOf("action" to action)))
                }
                lower.contains("storage") || lower.contains("find file") || lower.contains("search file") || lower.contains("file dhundo") || lower.contains("file search") -> {
                    val action = when {
                        lower.contains("breakdown") || lower.contains("check storage") || lower == "storage" -> "storage_breakdown"
                        lower.contains("find file") || lower.contains("search file") -> "search_file"
                        else -> "storage_breakdown"
                    }
                    val query = if (action == "search_file") lower.substringAfter("file ").trim() else ""
                    AgentDecision(DecisionType.ACT, AgentAction("FILE_MANAGER", mapOf("action" to action, "query" to query)))
                }
                lower.contains("permission") || lower.contains("privacy") || lower.contains("security audit") || lower.contains("scan security") || lower.contains("camera and mic") -> {
                    val isGrantOrOpen = lower.contains("give") || lower.contains("grant") || lower.contains("allow") || lower.contains("open") || lower.contains("do") || lower.contains("storage")
                    if (isGrantOrOpen && (lower.contains("storage") || lower.contains("settings") || lower.contains("permission"))) {
                        AgentDecision(DecisionType.ACT, AgentAction("DEVICE_SETTINGS", mapOf("action" to "storage_permission")), successCriteria = listOf("permission settings opened"))
                    } else {
                        val action = when {
                            lower.contains("score") -> "privacy_score"
                            lower.contains("camera") || lower.contains("mic") -> "scan_camera_mic_apps"
                            else -> "audit_permissions"
                        }
                        AgentDecision(DecisionType.ACT, AgentAction("SECURITY_AUDIT", mapOf("action" to action)))
                    }
                }
                lower.contains("log") || lower.contains("logcat") || lower.contains("telemetry") || lower.contains("error dekho") || lower.contains("error check") -> {
                    AgentDecision(DecisionType.ACT, AgentAction("LOGS_READ", mapOf("lines" to "20")), successCriteria = listOf("logs read"))
                }
                lower.contains("vault") || lower.contains("search notes") || lower.contains("search documents") || lower.contains("notes me") || lower.contains("rag") -> {
                    val q = lower.replace(Regex("^\\s*(search|for|in|notes|documents|vault|knowledge|me|rag)\\s+"), " ").trim()
                    AgentDecision(DecisionType.ACT, AgentAction("RAG_RETRIEVE", mapOf("query" to q)), successCriteria = listOf("vault retrieved"))
                }
                lower.contains("whatsapp") || lower.contains("wa ") -> {
                    val isSend = lower.contains("send") || lower.contains("bhejo") || lower.contains("bhej do")
                    if (isSend) {
                        val recipient = lower.substringBefore(" ko").substringAfter("whatsapp pe ").trim()
                        val msg = lower.substringAfter("ko ").substringBefore(" send").substringBefore(" bhejo").trim()
                        AgentDecision(DecisionType.ACT, AgentAction("WHATSAPP", mapOf("action" to "send_message", "recipient" to recipient, "message" to msg)), successCriteria = listOf("WhatsApp sent"))
                    } else {
                        AgentDecision(DecisionType.ACT, AgentAction("WHATSAPP", mapOf("action" to "read_messages")), successCriteria = listOf("WhatsApp read"))
                    }
                }
                // Volume Up / Down / Mute / Unmute
                lower.contains("volume") || lower.contains("awaaz") || lower.contains("awaz") || lower == "louder" || lower == "quieter" || lower == "mute" || lower == "unmute" -> {
                    val isUp = lower.contains("up") || lower.contains("badhao") || lower.contains("tez") || lower.contains("increase") || lower.contains("raise") || lower == "louder"
                    val isDown = lower.contains("down") || lower.contains("kam") || lower.contains("decrease") || lower.contains("lower") || lower.contains("dheeme") || lower == "quieter"
                    val isMute = lower == "mute" || lower.contains("silent") || lower.contains("band karo")
                    val isUnmute = lower == "unmute" || lower.contains("chalu karo")
                    when {
                        isUp -> AgentDecision(DecisionType.ACT, AgentAction("MEDIA_CONTROL", mapOf("action" to "volume_up")), successCriteria = listOf("volume increased"))
                        isDown -> AgentDecision(DecisionType.ACT, AgentAction("MEDIA_CONTROL", mapOf("action" to "volume_down")), successCriteria = listOf("volume decreased"))
                        isMute -> AgentDecision(DecisionType.ACT, AgentAction("DEVICE_SETTINGS", mapOf("action" to "mute")), successCriteria = listOf("audio muted"))
                        isUnmute -> AgentDecision(DecisionType.ACT, AgentAction("DEVICE_SETTINGS", mapOf("action" to "unmute")), successCriteria = listOf("audio unmuted"))
                        else -> AgentDecision(DecisionType.ACT, AgentAction("MEDIA_CONTROL", mapOf("action" to "volume_up")), successCriteria = listOf("volume adjusted"))
                    }
                }
                // Screen Lock / Unlock
                lower.contains("lock") || lower.contains("unlock") || lower.contains("screen on") || lower.contains("screen band") || lower.contains("phone band") || lower.contains("screen chalu") -> {
                    val isUnlock = lower.contains("unlock") || lower.contains("screen on") || lower.contains("screen chalu")
                    val action = if (isUnlock) "unlock" else "lock"
                    val tool = if (isUnlock) "SCREEN_UNLOCK" else "SCREEN_LOCK"
                    AgentDecision(DecisionType.ACT, AgentAction(tool, mapOf("action" to action)), successCriteria = listOf(if (isUnlock) "screen unlocked" else "screen locked"))
                }
                // Close All Apps / Clear Recents
                lower.contains("close all") || lower.contains("clear recents") || lower.contains("clear all apps") || lower.contains("sab apps band") || lower.contains("apps close") -> {
                    AgentDecision(DecisionType.ACT, AgentAction("APPS_CLOSE_ALL", mapOf("action" to "close_all")), successCriteria = listOf("all apps closed"))
                }
                // Wi-Fi / Bluetooth Switchboard
                lower.contains("wifi") || lower.contains("bluetooth") -> {
                    val isBt = lower.contains("bluetooth")
                    val off = lower.contains("off") || lower.contains("band") || lower.contains("stop") || lower.contains("disable")
                    val action = if (isBt) "set_bluetooth" else "set_wifi"
                    val stateStr = if (off) "off" else "on"
                    AgentDecision(DecisionType.ACT, AgentAction("SYSTEM_SWITCHBOARD", mapOf("action" to action, "state" to stateStr)), successCriteria = listOf("${if (isBt) "bluetooth" else "wifi"} $stateStr"))
                }
                // Battery & Device Health
                lower.contains("battery") -> {
                    AgentDecision(DecisionType.ACT, AgentAction("BATTERY_CHECK", emptyMap()), successCriteria = listOf("battery checked"))
                }
                // Where Am I / Location
                lower.contains("where am i") || lower.contains("my location") || lower.contains("kahan hu") || lower.contains("meri location") -> {
                    AgentDecision(DecisionType.ACT, AgentAction("LOCATION", mapOf("action" to "where_am_i")), successCriteria = listOf("location fetched"))
                }
                // Time & Date Queries
                !isCreation && (lower.contains("time") || lower.contains("samay") || lower.contains("baje") || lower.contains("date") || lower.contains("din hai") || lower.contains("aaj konsa")) -> {
                    val action = if (lower.contains("date") || lower.contains("din") || lower.contains("aaj")) "current_date" else "current_time"
                    AgentDecision(DecisionType.ACT, AgentAction("CLOCK", mapOf("action" to action)), successCriteria = listOf("time/date checked"))
                }
                // Calculator
                lower.startsWith("calculate ") || lower.startsWith("hisab ") || lower.startsWith("hisaab ") || (lower.contains("+") || lower.contains("-") || lower.contains("*") || lower.contains("/")) && (lower.contains("kitna") || lower.contains("equals") || lower.contains("calculate")) -> {
                    val expr = lower.replace(Regex("^(?:calculate|hisab karo|hisaab karo|compute)\\s+"), "").trim()
                    AgentDecision(DecisionType.ACT, AgentAction("CALCULATE", mapOf("expression" to expr)), successCriteria = listOf("calculated"))
                }
                // Weather
                lower.contains("weather") || lower.contains("mausam") || lower.contains("temperature") || lower.contains("tapman") -> {
                    val loc = lower.replace(Regex("\\b(weather|mausam|temperature|tapman|in|of|kaisa hai|batao|today|aaj ka)\\b"), "").trim()
                    AgentDecision(DecisionType.ACT, AgentAction("WEATHER", mapOf("location" to loc)), successCriteria = listOf("weather retrieved"))
                }
                // Translator
                lower.startsWith("translate ") || lower.contains("anuvad") -> {
                    val textToTranslate = lower.substringAfter("translate ").substringBefore(" to ").trim()
                    val targetLang = if (lower.contains(" to ")) lower.substringAfter(" to ").trim() else "hindi"
                    AgentDecision(DecisionType.ACT, AgentAction("TRANSLATOR", mapOf("text" to textToTranslate, "target_lang" to targetLang)), successCriteria = listOf("translated"))
                }
                else ->
                    AgentDecision(
                        DecisionType.ASK_USER,
                        question = "Sorry, I could not understand \"$goal\". Could you rephrase your request?",
                        reasonCode = "UNRECOGNIZED_FALLBACK"
                    )
            }
        }

        /**
         * Serializes the resumable agent state to JSON for the ASK_USER checkpoint.
         * `executedActs` and `pendingApproval` are included so a clarify-resume
         * restores the repeat-detection memory: without them every action that
         * already ran is forgotten after a resume and gets re-fired (the
         * repeated-action bug across resumes).
         */
        internal fun serializeState(state: AgentState, memory: AgentWorkingMemory): String {
            val root = JSONObject()
            root.put("taskId", state.taskId)
            root.put("goal", state.goal)
            root.put("iteration", state.iteration)
            root.put("completedSteps", state.completedSteps)
            root.put("failureCount", state.failureCount)

            val satArr = JSONArray()
            state.satisfiedCriteria.toSortedSet().forEach { satArr.put(it) }
            root.put("satisfiedCriteria", satArr)

            val factsObj = JSONObject()
            memory.facts.forEach { (k, v) -> factsObj.put(k, v) }
            root.put("facts", factsObj)

            val candArr = JSONArray()
            memory.candidates.forEach { candArr.put(it) }
            root.put("candidates", candArr)

            val compArr = JSONArray()
            memory.completedSteps.forEach { compArr.put(it) }
            root.put("completedStepsList", compArr)

            val failArr = JSONArray()
            memory.failedSteps.forEach { failArr.put(it) }
            root.put("failedStepsList", failArr)

            val subGoalsArr = JSONArray()
            memory.subGoals.forEach { subGoalsArr.put(it) }
            root.put("subGoals", subGoalsArr)

            val compSubGoalsArr = JSONArray()
            memory.completedSubGoals.toSortedSet().forEach { compSubGoalsArr.put(it) }
            root.put("completedSubGoals", compSubGoalsArr)

            // Actions already executed this task: {hash, toolResult, observation, verification}.
            val actsArr = JSONArray()
            state.executedActs.forEach { (hash, act) ->
                val actObj = JSONObject()
                actObj.put("hash", hash)
                actObj.put("success", act.result.success)
                actObj.put("message", act.result.message)
                actObj.put("obsSuccess", act.observation.success)
                actObj.put("obsMessage", act.observation.message)
                actObj.put("verStatus", act.verification.status.name)
                actObj.put("verMessage", act.verification.message)
                actsArr.put(actObj)
            }
            root.put("executedActs", actsArr)

            // Confirmation-gated action awaiting user consent (if any).
            state.pendingApproval?.let { pending ->
                val pendingObj = JSONObject()
                pendingObj.put("type", pending.type)
                val pendingParams = JSONObject()
                pending.params.forEach { (k, v) -> pendingParams.put(k, v) }
                pendingObj.put("params", pendingParams)
                root.put("pendingApproval", pendingObj)
            }

            return root.toString()
        }

        internal fun restoreState(jsonStr: String): Pair<AgentState, AgentWorkingMemory>? {
            return try {
                val root = JSONObject(jsonStr)
                val taskId = root.getLong("taskId")
                val goal = root.getString("goal")
                val state = AgentState(
                    taskId = taskId,
                    goal = goal,
                    iteration = root.optInt("iteration", 0),
                    completedSteps = root.optInt("completedSteps", 0),
                    failureCount = root.optInt("failureCount", 0)
                )
                val satArr = root.optJSONArray("satisfiedCriteria")
                if (satArr != null) {
                    for (i in 0 until satArr.length()) state.satisfiedCriteria.add(satArr.getString(i))
                }
                val memory = AgentWorkingMemory(goal = goal)
                val factsObj = root.optJSONObject("facts")
                factsObj?.keys()?.forEach { k -> memory.facts[k] = factsObj.getString(k) }

                val candArr = root.optJSONArray("candidates")
                if (candArr != null) {
                    for (i in 0 until candArr.length()) memory.candidates.add(candArr.getString(i))
                }

                val compArr = root.optJSONArray("completedStepsList")
                if (compArr != null) {
                    for (i in 0 until compArr.length()) memory.completedSteps.add(compArr.getString(i))
                }

                val failArr = root.optJSONArray("failedStepsList")
                if (failArr != null) {
                    for (i in 0 until failArr.length()) memory.failedSteps.add(failArr.getString(i))
                }

                val subGoalsArr = root.optJSONArray("subGoals")
                if (subGoalsArr != null) {
                    for (i in 0 until subGoalsArr.length()) memory.subGoals.add(subGoalsArr.getString(i))
                }

                val compSubGoalsArr = root.optJSONArray("completedSubGoals")
                if (compSubGoalsArr != null) {
                    for (i in 0 until compSubGoalsArr.length()) memory.completedSubGoals.add(compSubGoalsArr.getInt(i))
                }

                // Restore the repeat-detection memory: an action that already ran this
                // task must never re-fire after a clarify-resume.
                val actsArr = root.optJSONArray("executedActs")
                if (actsArr != null) {
                    for (i in 0 until actsArr.length()) {
                        try {
                            val actObj = actsArr.getJSONObject(i)
                            val toolResult = if (actObj.optBoolean("success")) {
                                ToolResult.Success(actObj.optString("message"))
                            } else {
                                ToolResult.Failed(actObj.optString("message"))
                            }
                            val observation = AgentObservation(
                                source = "TOOL",
                                success = actObj.optBoolean("obsSuccess"),
                                message = actObj.optString("obsMessage")
                            )
                            val verStatus = try {
                                VerificationStatus.valueOf(actObj.optString("verStatus", "UNKNOWN"))
                            } catch (e: IllegalArgumentException) {
                                VerificationStatus.UNKNOWN
                            }
                            val verification = VerificationResult(verStatus, actObj.optString("verMessage"))
                            state.executedActs[actObj.getString("hash")] = ExecutedAct(toolResult, observation, verification)
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping malformed executedAct entry at index $i", e)
                        }
                    }
                }

                root.optJSONObject("pendingApproval")?.let { pendingObj ->
                    val pendingParams = pendingObj.optJSONObject("params")
                    val params = mutableMapOf<String, String>()
                    if (pendingParams != null) {
                        pendingParams.keys().forEach { k -> params[k] = pendingParams.optString(k) }
                    }
                    state.pendingApproval = AgentAction(pendingObj.optString("type"), params)
                }

                Pair(state, memory)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deserialize state checkpoint", e)
                null
            }
        }
    }
}
