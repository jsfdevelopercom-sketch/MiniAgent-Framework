package com.miniagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result wrapper for a model/thought-stage call.
 */
public class ThoughtCallResult<T> {

    private final boolean success;
    private final T value;
    private final String modelUsed;
    private final List<ThoughtFailureRecord> failures;

    private ThoughtCallResult(
            boolean success,
            T value,
            String modelUsed,
            List<ThoughtFailureRecord> failures) {
        this.success = success;
        this.value = value;
        this.modelUsed = modelUsed == null ? "" : modelUsed;
        this.failures = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
    }

    public static <T> ThoughtCallResult<T> success(T value, String modelUsed,
            List<ThoughtFailureRecord> priorFailures) {
        return new ThoughtCallResult<>(true, value, modelUsed, priorFailures);
    }

    public static <T> ThoughtCallResult<T> failure(List<ThoughtFailureRecord> failures) {
        return new ThoughtCallResult<>(false, null, "", failures);
    }

    public boolean isSuccess() {
        return success;
    }

    public T getValue() {
        return value;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public List<ThoughtFailureRecord> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    public ThoughtFailureRecord getLastFailure() {
        if (failures.isEmpty()) {
            return null;
        }
        return failures.get(failures.size() - 1);
    }

    public String compactFailureText() {
        if (failures.isEmpty()) {
            return "No failures.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < failures.size(); i++) {
            sb.append(i + 1)
                    .append(". ")
                    .append(failures.get(i).toRepairLine())
                    .append("\n");
        }
        return sb.toString().trim();
    }
}