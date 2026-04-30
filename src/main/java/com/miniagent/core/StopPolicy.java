package com.miniagent.core;

import com.miniagent.model.EvaluationResult;

import java.time.Duration;
import java.time.Instant;

/**
 * StopPolicy prevents recursive token bleeding.
 *
 * It owns the decision:
 * continue, success, partial stop, or hard stop.
 */
public class StopPolicy {

    private final TokenCostManager costManager;

    public StopPolicy(TokenCostManager costManager) {
        this.costManager = costManager;
    }

    public StopDecision decide(AgentRunPlan plan, AgentRunState state) {
        if (plan == null) {
            return StopDecision.hardStop("Missing AgentRunPlan.");
        }

        if (state == null) {
            return StopDecision.hardStop("Missing AgentRunState.");
        }

        if (state.isCompleted()) {
            return state.isSuccessful()
                    ? StopDecision.success(state.getStopReason())
                    : StopDecision.partialStop(state.getStopReason());
        }

        Duration elapsed = Duration.between(state.getStartedAt(), Instant.now());
        if (elapsed.compareTo(plan.getMaxWallClockTime()) > 0) {
            return StopDecision.partialStop("Maximum wall-clock time exceeded.");
        }

        if (costManager != null && costManager.isQuotaExceeded(state.getUserId())) {
            return StopDecision.partialStop("Token cost quota exceeded.");
        }

        EvaluationResult latest = state.getLatestEvaluation();
        if (latest != null) {
            int combinedScore = state.combinedScore(latest);

            if (latest.isPass() && combinedScore >= plan.getSuccessThreshold() * 10) {
                return StopDecision.success("Critic approved output.");
            }

            if (combinedScore >= plan.getSuccessThreshold() * 10) {
                return StopDecision.success("Success threshold reached.");
            }
        }

        if (state.getAttemptNumber() >= plan.getMaxAttempts()) {
            if (state.getBestDraft() != null) {
                return StopDecision.partialStop("Maximum attempts reached. Returning best draft.");
            }
            return StopDecision.hardStop("Maximum attempts reached without usable draft.");
        }

        if (state.getNoImprovementCount() >= 2) {
            return StopDecision.partialStop("No improvement across repeated attempts.");
        }

        return StopDecision.continueRun();
    }

    public static class StopDecision {

        public enum Action {
            CONTINUE,
            SUCCESS,
            PARTIAL_STOP,
            HARD_STOP
        }

        private final Action action;
        private final String reason;

        private StopDecision(Action action, String reason) {
            this.action = action;
            this.reason = reason == null ? "" : reason;
        }

        public static StopDecision continueRun() {
            return new StopDecision(Action.CONTINUE, "Continue.");
        }

        public static StopDecision success(String reason) {
            return new StopDecision(Action.SUCCESS, reason);
        }

        public static StopDecision partialStop(String reason) {
            return new StopDecision(Action.PARTIAL_STOP, reason);
        }

        public static StopDecision hardStop(String reason) {
            return new StopDecision(Action.HARD_STOP, reason);
        }

        public Action getAction() {
            return action;
        }

        public String getReason() {
            return reason;
        }

        public boolean shouldContinue() {
            return action == Action.CONTINUE;
        }
    }
}