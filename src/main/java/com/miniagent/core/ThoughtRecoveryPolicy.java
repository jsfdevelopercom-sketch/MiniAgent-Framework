package com.miniagent.core;

import java.util.List;

/**
 * Decides how the agent should recover when a thought-stage fails.
 *
 * This policy is intentionally deterministic. The model should not decide
 * whether the agent keeps burning tokens forever.
 */
public class ThoughtRecoveryPolicy {

    public ThoughtRecoveryDecision decideAfterFailure(
            AgentRunPlan plan,
            AgentRunState state,
            List<ThoughtFailureRecord> failures,
            RepairMemoryCompressor repairMemory) {
        if (plan == null) {
            return ThoughtRecoveryDecision.hardStop("Missing run plan.");
        }

        if (state == null) {
            return ThoughtRecoveryDecision.hardStop("Missing run state.");
        }

        if (failures == null || failures.isEmpty()) {
            return ThoughtRecoveryDecision.continueRun("No concrete failure recorded.");
        }

        ThoughtFailureRecord last = failures.get(failures.size() - 1);

        if (!last.isRecoverable()) {
            if (state.getBestDraft() != null) {
                return ThoughtRecoveryDecision.acceptPartial(
                        "Unrecoverable thought failure, returning best draft: " + last.getMessage());
            }
            return ThoughtRecoveryDecision.hardStop("Unrecoverable thought failure: " + last.getMessage());
        }

        if (state.getAttemptNumber() >= plan.getMaxAttempts()) {
            if (state.getBestDraft() != null) {
                return ThoughtRecoveryDecision.acceptPartial(
                        "Maximum attempts reached after thought failure. Returning best draft.");
            }
            return ThoughtRecoveryDecision.hardStop("Maximum attempts reached without usable draft.");
        }

        if (repairMemory != null && repairMemory.hasRepeatedFailure(3)) {
            ThoughtFailureRecord repeated = repairMemory.mostRepeatedFailure();
            String reason = repeated == null
                    ? "Repeated failure detected."
                    : "Repeated failure detected: " + repeated.getMessage();

            if (state.getAttemptNumber() + 1 < plan.getMaxAttempts()) {
                return ThoughtRecoveryDecision.replanFromScratch(reason);
            }

            if (state.getBestDraft() != null) {
                return ThoughtRecoveryDecision.acceptPartial(reason + " Returning best draft.");
            }

            return ThoughtRecoveryDecision.hardStop(reason);
        }

        return switch (last.getType()) {
            case MODEL_CONTEXT_TOO_LARGE -> ThoughtRecoveryDecision.compressContextAndRetry(
                    "Context too large. Compress prompt/history and retry.");

            case MODEL_TIMEOUT, MODEL_RATE_LIMITED, MODEL_SERVER_ERROR, MODEL_EXCEPTION ->
                ThoughtRecoveryDecision.retryFallback(
                        "Model call failed. Retry with fallback model.");

            case EMPTY_OUTPUT, EMPTY_SUMMARY, MALFORMED_JSON ->
                state.getBestDraft() == null
                        ? ThoughtRecoveryDecision.retryFallback("No usable draft. Retry with fallback model.")
                        : ThoughtRecoveryDecision.repairFromBest("Malformed/empty output. Repair from best draft.");

            case CRITIC_EXCEPTION, CRITIC_MALFORMED ->
                state.getBestDraft() == null
                        ? ThoughtRecoveryDecision.retryFallback("Critic failed before best draft existed.")
                        : ThoughtRecoveryDecision
                                .repairFromBest("Critic failed. Continue conservatively from best draft.");

            case REPAIR_FAILED, REPAIR_WORSENED_OUTPUT, NO_IMPROVEMENT ->
                state.getBestDraft() != null
                        ? ThoughtRecoveryDecision
                                .replanFromScratch("Repair failed or worsened output. Replan from scratch.")
                        : ThoughtRecoveryDecision.retryFallback("Repair failed without best draft. Retry fallback.");

            case MODEL_AUTH_ERROR ->
                ThoughtRecoveryDecision
                        .retryFallback("Provider authentication failed. Try another configured provider.");

            case MODEL_SAFETY_BLOCKED, UNSAFE_OUTPUT ->
                state.getBestDraft() != null
                        ? ThoughtRecoveryDecision.acceptPartial("Safety block encountered. Returning best safe draft.")
                        : ThoughtRecoveryDecision.hardStop("Safety block encountered and no safe draft exists.");

            default ->
                state.getBestDraft() != null
                        ? ThoughtRecoveryDecision.repairFromBest("Generic recoverable failure. Repair from best draft.")
                        : ThoughtRecoveryDecision
                                .retryFallback("Generic recoverable failure. Retry with fallback model.");
        };
    }
}