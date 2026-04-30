package com.miniagent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes agent traces as JSONL files.
 *
 * One run = one file:
 * traces/{runId}.jsonl
 *
 * JSONL is intentionally chosen because:
 * - append-friendly
 * - easy to stream
 * - easy to inspect manually
 * - robust if the process crashes mid-run
 */
public class JsonlAgentTraceLogger implements AgentTraceLogger {

    private final Path traceDirectory;
    private final ObjectMapper mapper;
    private final int maxStringLength;
    private final int maxCollectionItems;

    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, Object> runLocks = new ConcurrentHashMap<>();

    public JsonlAgentTraceLogger(Path traceDirectory, ObjectMapper mapper) {
        this(traceDirectory, mapper, 4000, 50);
    }

    public JsonlAgentTraceLogger(
            Path traceDirectory,
            ObjectMapper mapper,
            int maxStringLength,
            int maxCollectionItems) {
        if (traceDirectory == null) {
            throw new IllegalArgumentException("traceDirectory cannot be null.");
        }

        this.traceDirectory = traceDirectory;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.maxStringLength = Math.max(500, maxStringLength);
        this.maxCollectionItems = Math.max(5, maxCollectionItems);

        try {
            Files.createDirectories(traceDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create trace directory: " + traceDirectory, e);
        }
    }

    @Override
    public void log(AgentTraceEvent event) {
        if (event == null) {
            return;
        }

        try {
            AgentTraceEvent safeEvent = sanitizeEvent(event);
            safeEvent.setSequence(sequenceCounter.incrementAndGet());

            String runId = safeFileComponent(safeEvent.getRunId());
            Path file = traceDirectory.resolve(runId + ".jsonl");

            String jsonLine = mapper.writeValueAsString(safeEvent) + System.lineSeparator();

            Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());

            synchronized (lock) {
                Files.writeString(
                        file,
                        jsonLine,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("[TRACE LOGGER WARNING] Failed to write trace event: " + e.getMessage());
        }
    }

    public Path getTraceDirectory() {
        return traceDirectory;
    }

    private AgentTraceEvent sanitizeEvent(AgentTraceEvent original) {
        AgentTraceEvent safe = new AgentTraceEvent();

        safe.setEventId(original.getEventId());
        safe.setRunId(original.getRunId());
        safe.setUserId(redactScalar(original.getUserId()));
        safe.setSequence(original.getSequence());
        safe.setTimestampIso(original.getTimestampIso());

        safe.setType(original.getType());
        safe.setStage(redactScalar(original.getStage()));
        safe.setModel(redactScalar(original.getModel()));
        safe.setMessage(redactScalar(original.getMessage()));

        safe.setSuccess(original.isSuccess());
        safe.setDurationMs(original.getDurationMs());
        safe.setEstimatedInputTokens(original.getEstimatedInputTokens());
        safe.setEstimatedOutputTokens(original.getEstimatedOutputTokens());

        safe.setErrorType(redactScalar(original.getErrorType()));
        safe.setErrorMessage(redactScalar(original.getErrorMessage()));

        Map<String, Object> sanitizedData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : original.getData().entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSensitiveKey(key)) {
                sanitizedData.put(key, "[REDACTED]");
            } else {
                sanitizedData.put(key, sanitizeValue(value, 0));
            }
        }

        safe.setData(sanitizedData);

        return safe;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }

        if (depth > 5) {
            return "[MAX_DEPTH_REACHED]";
        }

        if (value instanceof CharSequence text) {
            return redactScalar(text.toString());
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= maxCollectionItems) {
                    result.put("_truncated", true);
                    break;
                }

                String key = String.valueOf(entry.getKey());

                if (isSensitiveKey(key)) {
                    result.put(key, "[REDACTED]");
                } else {
                    result.put(key, sanitizeValue(entry.getValue(), depth + 1));
                }

                count++;
            }

            return result;
        }

        if (value instanceof Collection<?> collection) {
            ArrayList<Object> result = new ArrayList<>();
            int count = 0;

            for (Object item : collection) {
                if (count >= maxCollectionItems) {
                    result.add("[TRUNCATED]");
                    break;
                }

                result.add(sanitizeValue(item, depth + 1));
                count++;
            }

            return result;
        }

        if (value.getClass().isArray()) {
            ArrayList<Object> result = new ArrayList<>();
            int length = Array.getLength(value);
            int limit = Math.min(length, maxCollectionItems);

            for (int i = 0; i < limit; i++) {
                result.add(sanitizeValue(Array.get(value, i), depth + 1));
            }

            if (length > limit) {
                result.add("[TRUNCATED]");
            }

            return result;
        }

        try {
            Map<?, ?> converted = mapper.convertValue(value, Map.class);
            return sanitizeValue(converted, depth + 1);
        } catch (Exception ignored) {
            return redactScalar(String.valueOf(value));
        }
    }

    private String redactScalar(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.replaceAll("\\s+", " ").trim();

        cleaned = cleaned.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer [REDACTED]");
        cleaned = cleaned.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9._\\-]+", "$1[REDACTED]");
        cleaned = cleaned.replaceAll("(?i)(secret\\s*[:=]\\s*)[A-Za-z0-9._\\-]+", "$1[REDACTED]");
        cleaned = cleaned.replaceAll("(?i)(password\\s*[:=]\\s*)\\S+", "$1[REDACTED]");

        if (cleaned.length() > maxStringLength) {
            return cleaned.substring(0, maxStringLength) + "...[TRUNCATED]";
        }

        return cleaned;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }

        String lower = key.toLowerCase(Locale.ROOT);

        return lower.contains("apikey") ||
                lower.contains("api_key") ||
                lower.contains("api-key") ||
                lower.equals("key") ||
                lower.contains("secret") ||
                lower.contains("token") ||
                lower.contains("authorization") ||
                lower.contains("password") ||
                lower.contains("credential") ||
                lower.contains("cookie");
    }

    private String safeFileComponent(String value) {
        if (value == null || value.isBlank()) {
            return "unknown-run";
        }

        String cleaned = value.trim().replaceAll("[^a-zA-Z0-9._\\-]", "_");

        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }

        return cleaned.isBlank() ? "unknown-run" : cleaned;
    }
}