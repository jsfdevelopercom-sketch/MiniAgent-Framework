package com.miniagent.trace;

import java.util.Map;

/**
 * Logging interface for agent traces.
 *
 * Important rule:
 * Trace logging must never crash the main agent.
 * Implementations should fail silently or log to stderr internally.
 */
public interface AgentTraceLogger {

    void log(AgentTraceEvent event);

    default void runStarted(String runId, String userId, String taskPreview, Map<String, Object> data) {
        log(AgentTraceEvent.of(
                runId,
                userId,
                AgentTraceEventType.RUN_STARTED,
                "run",
                "Agent run started.")
                .putData("taskPreview", taskPreview)
                .putAllData(data));
    }

    default void stage(
            String runId,
            String userId,
            AgentTraceEventType type,
            String stage,
            String message,
            Map<String, Object> data) {
        log(AgentTraceEvent.of(runId, userId, type, stage, message).putAllData(data));
    }

    default void modelEvent(
            String runId,
            String userId,
            AgentTraceEventType type,
            String stage,
            String model,
            String message,
            long durationMs,
            int inputTokens,
            int outputTokens,
            Map<String, Object> data) {
        log(AgentTraceEvent.of(runId, userId, type, stage, message)
                .withModel(model)
                .withDurationMs(durationMs)
                .withEstimatedTokens(inputTokens, outputTokens)
                .withSuccess(true)
                .putAllData(data));
    }

    default void warning(String runId, String userId, String stage, String message, Map<String, Object> data) {
        log(AgentTraceEvent.of(
                runId,
                userId,
                AgentTraceEventType.WARNING,
                stage,
                message)
                .withSuccess(false)
                .putAllData(data));
    }

    default void error(String runId, String userId, String stage, String message, Throwable throwable) {
        log(AgentTraceEvent.of(
                runId,
                userId,
                AgentTraceEventType.ERROR,
                stage,
                message)
                .withError(throwable));
    }

    default void runFinished(
            String runId,
            String userId,
            boolean success,
            String stopReason,
            Map<String, Object> data) {
        log(AgentTraceEvent.of(
                runId,
                userId,
                AgentTraceEventType.RUN_FINISHED,
                "run",
                stopReason == null ? "Agent run finished." : stopReason)
                .withSuccess(success)
                .putAllData(data));
    }
}