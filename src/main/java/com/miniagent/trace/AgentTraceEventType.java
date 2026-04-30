package com.miniagent.trace;

/**
 * High-level event categories for MiniAgent trace logging.
 *
 * These are intentionally broad. The detailed stage/message/payload fields
 * inside AgentTraceEvent carry the finer details.
 */
public enum AgentTraceEventType {
    RUN_STARTED,
    RUN_FINISHED,

    CLASSIFICATION_STARTED,
    CLASSIFICATION_FINISHED,

    MODEL_ROUTE_SELECTED,
    RUN_PLAN_CREATED,

    ATTEMPT_STARTED,
    GENERATION_STARTED,
    GENERATION_FINISHED,

    EVALUATION_STARTED,
    EVALUATION_FINISHED,

    REPAIR_STARTED,
    REPAIR_FINISHED,

    STOP_DECISION,
    SYNTHESIS_STARTED,
    SYNTHESIS_FINISHED,

    TOOL_LOOP_STARTED,
    TOOL_DECISION,
    TOOL_CALL_STARTED,
    TOOL_CALL_FINISHED,
    TOOL_LOOP_FINISHED,

    WARNING,
    ERROR
}