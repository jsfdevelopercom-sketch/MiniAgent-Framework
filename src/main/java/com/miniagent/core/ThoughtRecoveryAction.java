package com.miniagent.core;

public enum ThoughtRecoveryAction {
    CONTINUE,
    RETRY_WITH_FALLBACK_MODEL,
    REPAIR_FROM_BEST,
    REPLAN_FROM_SCRATCH,
    COMPRESS_CONTEXT_AND_RETRY,
    ACCEPT_PARTIAL,
    HARD_STOP
}