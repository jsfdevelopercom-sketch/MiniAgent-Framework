package com.miniagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable response DTO used across MiniAgent.
 *
 * This class is deliberately tolerant:
 * - accepts snake_case and camelCase aliases
 * - ignores unknown fields
 * - never returns null from public getters
 * - can normalize partially malformed model responses
 *
 * Required logical fields:
 * - thought_process: brief public reasoning summary, not hidden
 * chain-of-thought
 * - summary: main user-facing answer
 * - convo: short follow-up text
 * - spoken_summary: short TTS-safe summary
 * - raw: raw model output or internal debug payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StructuredResponse {

    @JsonProperty("thought_process")
    @JsonAlias({
            "thoughtProcess",
            "thought",
            "reasoning",
            "reasoning_summary",
            "reasoningSummary",
            "public_reasoning",
            "publicReasoning"
    })
    private String thought_process = "";

    @JsonProperty("summary")
    @JsonAlias({
            "answer",
            "response",
            "content",
            "final",
            "final_answer",
            "finalAnswer",
            "output",
            "markdown"
    })
    private String summary = "";

    @JsonProperty("convo")
    @JsonAlias({
            "conversation",
            "follow_up",
            "followUp",
            "message",
            "assistant_message",
            "assistantMessage"
    })
    private String convo = "";

    @JsonProperty("spoken_summary")
    @JsonAlias({
            "spokenSummary",
            "tts",
            "tts_summary",
            "ttsSummary",
            "voice",
            "voice_summary",
            "voiceSummary"
    })
    private String spoken_summary = "";

    @JsonProperty("raw")
    @JsonAlias({
            "raw_output",
            "rawOutput",
            "debug",
            "debug_payload",
            "debugPayload"
    })
    private String raw = "";

    /**
     * Optional metadata for trace/debug usage.
     * This is not required by the frontend but is useful internally.
     */
    @JsonProperty("meta")
    @JsonAlias({
            "metadata",
            "debug_meta",
            "debugMeta"
    })
    private Map<String, Object> meta = new LinkedHashMap<>();

    public StructuredResponse() {
    }

    public StructuredResponse(String summary) {
        setSummary(summary);
        setSpoken_summary(buildDefaultSpokenSummary(summary));
    }

    public StructuredResponse(
            String thoughtProcess,
            String summary,
            String convo,
            String spokenSummary,
            String raw) {
        setThought_process(thoughtProcess);
        setSummary(summary);
        setConvo(convo);
        setSpoken_summary(spokenSummary);
        setRaw(raw);
    }

    public static StructuredResponse fromSummary(String summary) {
        return new StructuredResponse(summary);
    }

    public static StructuredResponse failure(String userMessage, String rawReason) {
        StructuredResponse response = new StructuredResponse();
        response.setThought_process("Failure response generated safely.");
        response.setSummary(userMessage == null || userMessage.isBlank()
                ? "The agent could not complete the task."
                : userMessage);
        response.setConvo("");
        response.setSpoken_summary(buildDefaultSpokenSummary(response.getSummary()));
        response.setRaw(rawReason == null ? "" : rawReason);
        return response;
    }

    public static StructuredResponse empty() {
        StructuredResponse response = new StructuredResponse();
        response.setThought_process("");
        response.setSummary("");
        response.setConvo("");
        response.setSpoken_summary("");
        response.setRaw("");
        return response;
    }

    public String getThought_process() {
        return safe(thought_process);
    }

    public void setThought_process(String thought_process) {
        this.thought_process = sanitizeSingleLineish(thought_process, 3000);
    }

    /**
     * CamelCase convenience getter.
     */
    @JsonIgnore
    public String getThoughtProcess() {
        return getThought_process();
    }

    /**
     * CamelCase convenience setter.
     */
    public void setThoughtProcess(String thoughtProcess) {
        setThought_process(thoughtProcess);
    }

    public String getSummary() {
        return safe(summary);
    }

    public void setSummary(String summary) {
        this.summary = sanitizeBlock(summary, 250_000);
    }

    public String getConvo() {
        return safe(convo);
    }

    public void setConvo(String convo) {
        this.convo = sanitizeBlock(convo, 20_000);
    }

    public String getSpoken_summary() {
        return safe(spoken_summary);
    }

    public void setSpoken_summary(String spoken_summary) {
        this.spoken_summary = sanitizeSingleLineish(spoken_summary, 2000);
    }

    /**
     * CamelCase convenience getter.
     */
    @JsonIgnore
    public String getSpokenSummary() {
        return getSpoken_summary();
    }

    /**
     * CamelCase convenience setter.
     */
    public void setSpokenSummary(String spokenSummary) {
        setSpoken_summary(spokenSummary);
    }

    public String getRaw() {
        return safe(raw);
    }

    public void setRaw(String raw) {
        this.raw = sanitizeBlock(raw, 500_000);
    }

    public Map<String, Object> getMeta() {
        if (meta == null) {
            meta = new LinkedHashMap<>();
        }
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(meta);
    }

    public void putMeta(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        getMeta().put(key, value);
    }

    @JsonIgnore
    public boolean hasUsableSummary() {
        return !getSummary().isBlank();
    }

    @JsonIgnore
    public boolean isEffectivelyEmpty() {
        return getSummary().isBlank() &&
                getConvo().isBlank() &&
                getSpoken_summary().isBlank() &&
                getRaw().isBlank();
    }

    /**
     * Normalizes a response after model parsing.
     *
     * This should be called after reading model output into StructuredResponse.
     */
    public StructuredResponse normalize() {
        if (getSummary().isBlank()) {
            String extracted = firstNonBlank(getConvo(), getSpoken_summary(), getRaw());
            if (!extracted.isBlank()) {
                setSummary(extracted);
            }
        }

        if (getSpoken_summary().isBlank() && !getSummary().isBlank()) {
            setSpoken_summary(buildDefaultSpokenSummary(getSummary()));
        }

        if (getThought_process().isBlank()) {
            setThought_process("Generated and normalized response.");
        }

        if (getConvo().isBlank()) {
            setConvo("");
        }

        return this;
    }

    /**
     * Creates a safe public copy.
     *
     * Keeps summary/convo/spoken summary, but removes bulky raw debug data.
     */
    public StructuredResponse publicCopy() {
        StructuredResponse copy = new StructuredResponse();
        copy.setThought_process(getThought_process());
        copy.setSummary(getSummary());
        copy.setConvo(getConvo());
        copy.setSpoken_summary(getSpoken_summary());
        copy.setRaw("");
        copy.setMeta(new LinkedHashMap<>(getMeta()));
        return copy;
    }

    /**
     * Creates a short preview for logs/traces.
     */
    @JsonIgnore
    public String preview(int maxChars) {
        String text = firstNonBlank(getSummary(), getConvo(), getRaw());
        if (text.isBlank()) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);
        if (text.length() <= safeMax) {
            return text;
        }

        return text.substring(0, safeMax) + "...[TRUNCATED]";
    }

    private static String buildDefaultSpokenSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }

        String cleaned = summary
                .replaceAll("(?s)```.*?```", " I have prepared the requested code block on screen. ")
                .replaceAll("[#*_`>\\[\\]{}|]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.length() <= 220) {
            return cleaned;
        }

        return "I have prepared the response on screen. Please review it, and tell me which part you want to refine.";
    }

    private static String sanitizeSingleLineish(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        String cleaned = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .trim();

        while (cleaned.contains("\n\n\n")) {
            cleaned = cleaned.replace("\n\n\n", "\n\n");
        }

        return trimToMax(cleaned, maxChars);
    }

    private static String sanitizeBlock(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        String cleaned = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();

        while (cleaned.contains("\n\n\n\n")) {
            cleaned = cleaned.replace("\n\n\n\n", "\n\n\n");
        }

        return trimToMax(cleaned, maxChars);
    }

    private static String trimToMax(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);

        if (value.length() <= safeMax) {
            return value;
        }

        return value.substring(0, safeMax) + "\n...[TRUNCATED]";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "StructuredResponse{" +
                "thought_process='" + previewField(getThought_process()) + '\'' +
                ", summary='" + previewField(getSummary()) + '\'' +
                ", convo='" + previewField(getConvo()) + '\'' +
                ", spoken_summary='" + previewField(getSpoken_summary()) + '\'' +
                ", rawLength=" + getRaw().length() +
                ", metaKeys=" + getMeta().keySet() +
                '}';
    }

    private static String previewField(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= 80) {
            return cleaned;
        }

        return cleaned.substring(0, 80) + "...";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StructuredResponse that)) {
            return false;
        }

        return Objects.equals(getThought_process(), that.getThought_process()) &&
                Objects.equals(getSummary(), that.getSummary()) &&
                Objects.equals(getConvo(), that.getConvo()) &&
                Objects.equals(getSpoken_summary(), that.getSpoken_summary()) &&
                Objects.equals(getRaw(), that.getRaw());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getThought_process(),
                getSummary(),
                getConvo(),
                getSpoken_summary(),
                getRaw());
    }
}