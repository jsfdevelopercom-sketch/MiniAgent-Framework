package com.miniagent.core;

/**
 * Represents a decision made by the ThoughtRecoveryPolicy after a thought failure.
 */
public class ThoughtRecoveryDecision {

    private final ThoughtRecoveryAction action;
    private final String reason;

    private ThoughtRecoveryDecision(ThoughtRecoveryAction action, String reason) {
        this.action = action;
        this.reason = reason;
    }

    public ThoughtRecoveryAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public static ThoughtRecoveryDecision hardStop(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.HARD_STOP, reason);
    }

    public static ThoughtRecoveryDecision continueRun(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.CONTINUE, reason);
    }

    public static ThoughtRecoveryDecision acceptPartial(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.ACCEPT_PARTIAL, reason);
    }

    public static ThoughtRecoveryDecision replanFromScratch(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.REPLAN_FROM_SCRATCH, reason);
    }

    public static ThoughtRecoveryDecision compressContextAndRetry(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.COMPRESS_CONTEXT_AND_RETRY, reason);
    }

    public static ThoughtRecoveryDecision retryFallback(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.RETRY_WITH_FALLBACK_MODEL, reason);
    }

    public static ThoughtRecoveryDecision repairFromBest(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.REPAIR_FROM_BEST, reason);
    }
}
