package com.miniagent.core;

import com.miniagent.model.EvaluationResult;

import java.time.Duration;
import java.time.Instant;

/**
 * StopPolicy decides whether a MiniAgent run should continue, succeed, return a
 * partial answer, or hard-stop.
 *
 * It is deliberately small and deterministic. It does not generate text, does
 * not call providers, and does not classify the task. It only observes:
 *
 * - AgentRunPlan: maxAttempts, successThreshold, wall-clock budget
 * - AgentRunState: attempts completed, best draft, latest critic result
 * - TokenCostManager: optional user quota
 *
 * This class is the final guard against recursive token bleeding. If Worker,
 * Critic, or Repair repeatedly fail to improve, StopPolicy must stop the loop
 * and return the best safe result instead of letting the agent spin.
 */
public class StopPolicy {

    private final TokenCostManager costManager;

    /**
     * Creates a stop policy with optional cost/quota enforcement.
     *
     * costManager may be null in tests or lightweight integrations. In that case
     * StopPolicy still enforces attempts and wall-clock limits.
     */
    public StopPolicy(TokenCostManager costManager) {
        this.costManager = costManager;
    }

    /**
     * Decides the next action for the current run.
     *
     * This method is called before and after each attempt. Before an attempt it
     * usually returns CONTINUE unless time/quota/attempt limits are already hit.
     * After an attempt it may return SUCCESS or PARTIAL_STOP depending on the
     * critic result and plan limits.
     */
    public StopDecision decide(AgentRunPlan plan, AgentRunState state) {
        if (plan == null) {
            return StopDecision.hardStop("Missing AgentRunPlan.");
        }

        if (state == null) {
            return StopDecision.hardStop("Missing AgentRunState.");
        }

        if (state.isCompleted()) {
            /*
             * If another part of the run has already marked the state complete,
             * respect that marker. This avoids repairing after an explicit stop.
             */
            return state.isSuccessful()
                    ? StopDecision.success(state.getStopReason())
                    : StopDecision.partialStop(state.getStopReason());
        }

        Duration elapsed = elapsedSinceStart(state);
        Duration maxWallClock = safeWallClock(plan);

        if (elapsed.compareTo(maxWallClock) > 0) {
            /*
             * Wall-clock stop should be partial when a draft exists. Agent.finish...
             * will decide whether to return the best draft or a failure object.
             */
            return StopDecision.partialStop("Maximum wall-clock time exceeded.");
        }

        if (costManager != null && costManager.isQuotaExceeded(state.getUserId())) {
            return StopDecision.partialStop("Token cost quota exceeded.");
        }

        EvaluationResult latest = state.getLatestEvaluation();
        if (latest != null) {
            int combinedScore = state.combinedScore(latest);
            int targetScore = plan.getSuccessThreshold() * 10;

            /*
             * A passing critic plus threshold score is the strongest success signal.
             * The second branch permits success when the aggregate score is high
             * even if a critic did not set pass=true due to minor issues.
             */
            if (latest.isPass() && combinedScore >= targetScore) {
                return StopDecision.success("Critic approved output.");
            }

            if (combinedScore >= targetScore) {
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

    /**
     * Computes elapsed time defensively.
     *
     * AgentRunState should always have a start time. This helper prevents a bad
     * state object from crashing the whole run if that timestamp is missing.
     */
    private Duration elapsedSinceStart(AgentRunState state) {
        Instant startedAt = state.getStartedAt();

        if (startedAt == null) {
            return Duration.ZERO;
        }

        return Duration.between(startedAt, Instant.now());
    }

    /**
     * Returns the wall-clock budget from the plan with a safe fallback.
     */
    private Duration safeWallClock(AgentRunPlan plan) {
        Duration maxWallClock = plan.getMaxWallClockTime();

        if (maxWallClock == null || maxWallClock.isZero() || maxWallClock.isNegative()) {
            return Duration.ofMinutes(20);
        }

        return maxWallClock;
    }

    /**
     * Immutable decision value returned by StopPolicy.
     */
    public static class StopDecision {

        /**
         * Allowed high-level actions for the Agent loop.
         */
        public enum Action {
            CONTINUE,
            SUCCESS,
            PARTIAL_STOP,
            HARD_STOP
        }

        private final Action action;
        private final String reason;

        /**
         * Creates a stop decision.
         *
         * Static factory methods below should be preferred so every call site reads
         * like an English control-flow decision.
         */
        private StopDecision(Action action, String reason) {
            this.action = action == null ? Action.HARD_STOP : action;
            this.reason = reason == null ? "" : reason;
        }

        /**
         * Tells Agent to continue to the next generation/evaluation step.
         */
        public static StopDecision continueRun() {
            return new StopDecision(Action.CONTINUE, "Continue.");
        }

        /**
         * Tells Agent to return a successful final answer.
         */
        public static StopDecision success(String reason) {
            return new StopDecision(Action.SUCCESS, reason);
        }

        /**
         * Tells Agent to return the best available partial answer.
         */
        public static StopDecision partialStop(String reason) {
            return new StopDecision(Action.PARTIAL_STOP, reason);
        }

        /**
         * Tells Agent that no usable output is available or continuing is unsafe.
         */
        public static StopDecision hardStop(String reason) {
            return new StopDecision(Action.HARD_STOP, reason);
        }

        /**
         * Returns the action enum.
         */
        public Action getAction() {
            return action;
        }

        /**
         * Returns the human-readable reason logged by Agent.
         */
        public String getReason() {
            return reason;
        }

        /**
         * Convenience predicate used by the main Agent loop.
         */
        public boolean shouldContinue() {
            return action == Action.CONTINUE;
        }

        /**
         * Log-friendly string representation.
         */
        @Override
        public String toString() {
            return "StopDecision{" +
                    "action=" + action +
                    ", reason='" + reason + '\'' +
                    '}';
        }
    }
}