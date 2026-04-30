package com.miniagent.core;

public class ThoughtRecoveryDecision {

    private final ThoughtRecoveryAction action;
    private final String reason;
    private final boolean terminal;

    private ThoughtRecoveryDecision(ThoughtRecoveryAction action, String reason, boolean terminal) {
        this.action = action == null ? ThoughtRecoveryAction.HARD_STOP : action;
        this.reason = reason == null ? "" : reason;
        this.terminal = terminal;
    }

    public static ThoughtRecoveryDecision continueRun(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.CONTINUE, reason, false);
    }

    public static ThoughtRecoveryDecision retryFallback(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.RETRY_WITH_FALLBACK_MODEL, reason, false);
    }

    public static ThoughtRecoveryDecision repairFromBest(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.REPAIR_FROM_BEST, reason, false);
    }

    public static ThoughtRecoveryDecision replanFromScratch(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.REPLAN_FROM_SCRATCH, reason, false);
    }

    public static ThoughtRecoveryDecision compressContextAndRetry(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.COMPRESS_CONTEXT_AND_RETRY, reason, false);
    }

    public static ThoughtRecoveryDecision acceptPartial(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.ACCEPT_PARTIAL, reason, true);
    }

    public static ThoughtRecoveryDecision hardStop(String reason) {
        return new ThoughtRecoveryDecision(ThoughtRecoveryAction.HARD_STOP, reason, true);
    }

    public ThoughtRecoveryAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean shouldContinue() {
        return !terminal;
    }

    @Override
    public String toString() {
        return "ThoughtRecoveryDecision{" +
                "action=" + action +
                ", reason='" + reason + '\'' +
                ", terminal=" + terminal +
                '}';
    }
}