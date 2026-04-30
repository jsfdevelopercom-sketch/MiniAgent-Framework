package com.miniagent.trace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One structured event inside an agent run.
 *
 * Stored as JSONL by JsonlAgentTraceLogger:
 * one JSON object per line.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTraceEvent {

    private String eventId;
    private String runId;
    private String userId;

    private long sequence;
    private String timestampIso;

    private AgentTraceEventType type;
    private String stage;
    private String model;
    private String message;

    private boolean success;
    private long durationMs;

    private int estimatedInputTokens;
    private int estimatedOutputTokens;

    private String errorType;
    private String errorMessage;

    private Map<String, Object> data = new LinkedHashMap<>();

    public AgentTraceEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestampIso = java.time.Instant.now().toString();
    }

    public static AgentTraceEvent of(
            String runId,
            String userId,
            AgentTraceEventType type,
            String stage,
            String message) {
        AgentTraceEvent event = new AgentTraceEvent();
        event.setRunId(runId);
        event.setUserId(userId);
        event.setType(type);
        event.setStage(stage);
        event.setMessage(message);
        return event;
    }

    public AgentTraceEvent withModel(String model) {
        this.model = model;
        return this;
    }

    public AgentTraceEvent withSuccess(boolean success) {
        this.success = success;
        return this;
    }

    public AgentTraceEvent withDurationMs(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
        return this;
    }

    public AgentTraceEvent withEstimatedTokens(int inputTokens, int outputTokens) {
        this.estimatedInputTokens = Math.max(0, inputTokens);
        this.estimatedOutputTokens = Math.max(0, outputTokens);
        return this;
    }

    public AgentTraceEvent withError(Throwable throwable) {
        if (throwable == null) {
            return this;
        }

        this.success = false;
        this.errorType = throwable.getClass().getName();
        this.errorMessage = throwable.getMessage() == null ? "" : throwable.getMessage();
        return this;
    }

    public AgentTraceEvent putData(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }

        if (this.data == null) {
            this.data = new LinkedHashMap<>();
        }

        this.data.put(key, value);
        return this;
    }

    public AgentTraceEvent putAllData(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }

        if (this.data == null) {
            this.data = new LinkedHashMap<>();
        }

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry != null && entry.getKey() != null && !entry.getKey().isBlank()) {
                this.data.put(entry.getKey(), entry.getValue());
            }
        }

        return this;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId == null || eventId.isBlank()
                ? UUID.randomUUID().toString()
                : eventId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId == null || runId.isBlank() ? "unknown-run" : runId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = Math.max(0, sequence);
    }

    public String getTimestampIso() {
        return timestampIso;
    }

    public void setTimestampIso(String timestampIso) {
        this.timestampIso = timestampIso == null || timestampIso.isBlank()
                ? java.time.Instant.now().toString()
                : timestampIso;
    }

    public AgentTraceEventType getType() {
        return type;
    }

    public void setType(AgentTraceEventType type) {
        this.type = Objects.requireNonNullElse(type, AgentTraceEventType.WARNING);
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage == null ? "" : stage.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null ? "" : message.trim();
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
    }

    public int getEstimatedInputTokens() {
        return estimatedInputTokens;
    }

    public void setEstimatedInputTokens(int estimatedInputTokens) {
        this.estimatedInputTokens = Math.max(0, estimatedInputTokens);
    }

    public int getEstimatedOutputTokens() {
        return estimatedOutputTokens;
    }

    public void setEstimatedOutputTokens(int estimatedOutputTokens) {
        this.estimatedOutputTokens = Math.max(0, estimatedOutputTokens);
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType == null ? "" : errorType.trim();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }

    public Map<String, Object> getData() {
        if (data == null) {
            data = new LinkedHashMap<>();
        }
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }
}