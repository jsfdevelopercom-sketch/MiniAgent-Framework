package com.miniagent.core;

/**
 * Categorizes failures inside the thinking loop.
 *
 * This is deliberately broader than HTTP/model errors because an agent can fail
 * even when the model call technically succeeded.
 */
public enum ThoughtFailureType {
    NONE,

    EMPTY_TASK,
    EMPTY_OUTPUT,
    EMPTY_SUMMARY,
    MALFORMED_JSON,

    MODEL_EXCEPTION,
    MODEL_TIMEOUT,
    MODEL_AUTH_ERROR,
    MODEL_RATE_LIMITED,
    MODEL_SERVER_ERROR,
    MODEL_CONTEXT_TOO_LARGE,
    MODEL_SAFETY_BLOCKED,

    CRITIC_EXCEPTION,
    CRITIC_MALFORMED,
    CRITIC_REJECTED_OUTPUT,

    REPAIR_FAILED,
    REPAIR_WORSENED_OUTPUT,

    REPEATED_FAILURE,
    NO_IMPROVEMENT,
    TOKEN_BUDGET_EXCEEDED,
    WALL_CLOCK_EXCEEDED,

    UNSAFE_OUTPUT,
    STRUCTURAL_FAILURE,
    INSTRUCTION_NON_ADHERENCE,
    HALLUCINATION_RISK,

    UNKNOWN
}