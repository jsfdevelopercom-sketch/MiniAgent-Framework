package com.miniagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A compact, durable record of something that went wrong during thinking.
 *
 * This is used for:
 * - repair memory
 * - repeated-failure detection
 * - tracing/debugging
 * - recovery decisions
 */
public class ThoughtFailureRecord {

    private final ThoughtFailureType type;
    private final String stage;
    private final String model;
    private final int attemptNumber;
    private final String message;
    private final String fixHint;
    private final String signature;
    private final int severity;
    private final boolean recoverable;
    private final Instant createdAt;
    private final Map<String, Object> details;

    public ThoughtFailureRecord(
            ThoughtFailureType type,
            String stage,
            String model,
            int attemptNumber,
            String message,
            String fixHint,
            int severity,
            boolean recoverable,
            Map<String, Object> details) {
        this.type = type == null ? ThoughtFailureType.UNKNOWN : type;
        this.stage = clean(stage, "unknown-stage");
        this.model = clean(model, "unknown-model");
        this.attemptNumber = Math.max(0, attemptNumber);
        this.message = trim(clean(message, "Unknown thought failure."), 800);
        this.fixHint = trim(clean(fixHint, ""), 800);
        this.severity = Math.max(1, Math.min(10, severity));
        this.recoverable = recoverable;
        this.createdAt = Instant.now();
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        this.signature = buildSignature(this.type, this.stage, this.message);
    }

    public static ThoughtFailureRecord of(
            ThoughtFailureType type,
            String stage,
            String model,
            int attemptNumber,
            String message,
            String fixHint,
            int severity,
            boolean recoverable) {
        return new ThoughtFailureRecord(
                type,
                stage,
                model,
                attemptNumber,
                message,
                fixHint,
                severity,
                recoverable,
                null);
    }

    public static ThoughtFailureRecord fromException(
            Throwable throwable,
            String stage,
            String model,
            int attemptNumber) {
        ThoughtFailureType type = classifyThrowable(throwable);
        String message = throwable == null ? "Unknown exception." : throwable.getMessage();
        String throwableName = throwable == null ? "Throwable" : throwable.getClass().getSimpleName();

        String fixHint = switch (type) {
            case MODEL_AUTH_ERROR -> "Check API key/configuration or route to another configured provider.";
            case MODEL_RATE_LIMITED -> "Retry with fallback provider or reduce model calls.";
            case MODEL_CONTEXT_TOO_LARGE -> "Compress prompt/history and retry.";
            case MODEL_TIMEOUT -> "Retry with cheaper/faster model or smaller prompt.";
            case MODEL_SAFETY_BLOCKED -> "Remove unsafe/sensitive prompt material and retry safely.";
            case MODEL_SERVER_ERROR -> "Retry with fallback model.";
            default -> "Retry with fallback model or return best available draft.";
        };

        return new ThoughtFailureRecord(
                type,
                stage,
                model,
                attemptNumber,
                throwableName + ": " + (message == null ? "" : message),
                fixHint,
                defaultSeverity(type),
                defaultRecoverable(type),
                Map.of("exceptionClass", throwableName));
    }

    public static ThoughtFailureType classifyThrowable(Throwable throwable) {
        if (throwable == null) {
            return ThoughtFailureType.UNKNOWN;
        }

        String text = String.valueOf(throwable.getMessage()).toLowerCase(Locale.ROOT);
        String className = throwable.getClass().getName().toLowerCase(Locale.ROOT);
        String combined = className + " " + text;

        if (combined.contains("timeout") || combined.contains("timed out")) {
            return ThoughtFailureType.MODEL_TIMEOUT;
        }

        if (combined.contains("401") ||
                combined.contains("403") ||
                combined.contains("unauthorized") ||
                combined.contains("forbidden") ||
                combined.contains("api key") ||
                combined.contains("authentication")) {
            return ThoughtFailureType.MODEL_AUTH_ERROR;
        }

        if (combined.contains("429") ||
                combined.contains("rate limit") ||
                combined.contains("quota")) {
            return ThoughtFailureType.MODEL_RATE_LIMITED;
        }

        if (combined.contains("context") && combined.contains("length")) {
            return ThoughtFailureType.MODEL_CONTEXT_TOO_LARGE;
        }

        if (combined.contains("too many tokens") ||
                combined.contains("maximum context") ||
                combined.contains("input is too long")) {
            return ThoughtFailureType.MODEL_CONTEXT_TOO_LARGE;
        }

        if (combined.contains("safety") ||
                combined.contains("blocked") ||
                combined.contains("harm_category")) {
            return ThoughtFailureType.MODEL_SAFETY_BLOCKED;
        }

        if (combined.contains("500") ||
                combined.contains("502") ||
                combined.contains("503") ||
                combined.contains("504") ||
                combined.contains("server error") ||
                combined.contains("overloaded")) {
            return ThoughtFailureType.MODEL_SERVER_ERROR;
        }

        return ThoughtFailureType.MODEL_EXCEPTION;
    }

    public static int defaultSeverity(ThoughtFailureType type) {
        if (type == null) {
            return 5;
        }

        return switch (type) {
            case NONE -> 1;
            case EMPTY_TASK -> 10;
            case EMPTY_OUTPUT, EMPTY_SUMMARY, MALFORMED_JSON -> 7;
            case MODEL_AUTH_ERROR -> 9;
            case MODEL_CONTEXT_TOO_LARGE -> 8;
            case MODEL_SAFETY_BLOCKED -> 9;
            case MODEL_TIMEOUT, MODEL_RATE_LIMITED, MODEL_SERVER_ERROR -> 6;
            case CRITIC_EXCEPTION, CRITIC_MALFORMED -> 6;
            case REPEATED_FAILURE, NO_IMPROVEMENT -> 7;
            case TOKEN_BUDGET_EXCEEDED, WALL_CLOCK_EXCEEDED -> 8;
            case UNSAFE_OUTPUT -> 10;
            case STRUCTURAL_FAILURE, INSTRUCTION_NON_ADHERENCE, HALLUCINATION_RISK -> 7;
            default -> 5;
        };
    }

    public static boolean defaultRecoverable(ThoughtFailureType type) {
        if (type == null) {
            return true;
        }

        return switch (type) {
            case EMPTY_TASK, UNSAFE_OUTPUT, TOKEN_BUDGET_EXCEEDED, WALL_CLOCK_EXCEEDED -> false;
            case MODEL_AUTH_ERROR -> true;
            case MODEL_SAFETY_BLOCKED -> false;
            default -> true;
        };
    }

    private static String buildSignature(ThoughtFailureType type, String stage, String message) {
        String normalized = (type + "|" + stage + "|" + message)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9| ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.length() > 160) {
            normalized = normalized.substring(0, 160);
        }

        return normalized;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public ThoughtFailureType getType() {
        return type;
    }

    public String getStage() {
        return stage;
    }

    public String getModel() {
        return model;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getMessage() {
        return message;
    }

    public String getFixHint() {
        return fixHint;
    }

    public String getSignature() {
        return signature;
    }

    public int getSeverity() {
        return severity;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getDetails() {
        return new LinkedHashMap<>(details);
    }

    public String toRepairLine() {
        if (fixHint == null || fixHint.isBlank()) {
            return type + " at " + stage + ": " + message;
        }
        return type + " at " + stage + ": " + message + " Fix: " + fixHint;
    }

    @Override
    public String toString() {
        return "ThoughtFailureRecord{" +
                "type=" + type +
                ", stage='" + stage + '\'' +
                ", model='" + model + '\'' +
                ", attemptNumber=" + attemptNumber +
                ", message='" + message + '\'' +
                ", fixHint='" + fixHint + '\'' +
                ", severity=" + severity +
                ", recoverable=" + recoverable +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ThoughtFailureRecord that)) {
            return false;
        }
        return Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signature);
    }
}