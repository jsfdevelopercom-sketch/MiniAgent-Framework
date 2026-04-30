package com.miniagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OutputSynthesizer is the final formatting layer.
 *
 * It must not become another expensive agent loop.
 *
 * Responsibilities:
 * - Take the best draft from AgentRunState.
 * - Preserve the answer content.
 * - Clean raw JSON leakage.
 * - Format into StructuredResponse.
 * - Provide safe fallback if model synthesis fails.
 *
 * Non-responsibilities:
 * - Do not re-solve the task.
 * - Do not run tools.
 * - Do not critique deeply.
 * - Do not add unsupported facts.
 */
public class OutputSynthesizer {

    private static final int MAX_SYNTHESIS_ATTEMPTS = 2;
    private static final int MAX_INPUT_CHARS = 120_000;
    private static final String DEFAULT_SYNTH_MODEL = "gpt-4.1-mini";

    private final OpenAiHttpClient openAi;
    private final GeminiHttpClient gemini;
    private final ClaudeHttpClient claude;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    /**
     * Backward-compatible constructor.
     */
    public OutputSynthesizer(
            OpenAiHttpClient openAi,
            GeminiHttpClient gemini,
            PromptFactory promptFactory,
            ObjectMapper mapper
    ) {
        this(openAi, gemini, null, promptFactory, mapper);
    }

    /**
     * Preferred constructor if Claude is also available.
     */
    public OutputSynthesizer(
            OpenAiHttpClient openAi,
            GeminiHttpClient gemini,
            ClaudeHttpClient claude,
            PromptFactory promptFactory,
            ObjectMapper mapper
    ) {
        this.openAi = openAi;
        this.gemini = gemini;
        this.claude = claude;
        this.promptFactory = promptFactory;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }
private boolean looksLikeLargeCodeAnswer(String text, String originalQuery) {
    String answer = text == null ? "" : text;
    String q = originalQuery == null ? "" : originalQuery.toLowerCase(Locale.ROOT);

    boolean userAskedForCode =
            q.contains("code") ||
                    q.contains("html") ||
                    q.contains("javascript") ||
                    q.contains("java") ||
                    q.contains("python") ||
                    q.contains("working") ||
                    q.contains("complete") ||
                    q.contains("elaborate");

    boolean answerHasCode =
            answer.contains("```") ||
                    answer.contains("<!DOCTYPE html") ||
                    answer.contains("<script") ||
                    answer.contains("function ") ||
                    answer.contains("class ") ||
                    answer.contains("const ") ||
                    answer.contains("let ") ||
                    answer.contains("public class");

    boolean largeEnough = answer.length() > 2500;

    return userAskedForCode && answerHasCode && largeEnough;
}
    public StructuredResponse synthesize(
            StructuredResponse draft,
            String originalQuery,
            String synthesizerModel
    ) {
        StructuredResponse safeDraft = draft == null
                ? StructuredResponse.empty()
                : draft.normalize();

        String bestText = chooseBestDraftText(safeDraft);
        if (looksLikeLargeCodeAnswer(bestText, originalQuery)) {
    StructuredResponse direct = safeDraft.normalize();

    if (direct.getSummary().isBlank()) {
        direct.setSummary(bestText);
    }

    direct.setThought_process("Large code answer preserved without destructive synthesis.");
    direct.setSpoken_summary("I have prepared the full code on screen.");
    direct.putMeta("synthesisSkipped", true);
    direct.putMeta("synthesisSkipReason", "large_code_answer");

    return direct.normalize();
}

        if (bestText.isBlank()) {
            return StructuredResponse.failure(
                    "The agent finished, but no usable answer text was produced.",
                    "SYNTHESIS_NO_DRAFT_TEXT"
            );
        }

        String targetModel = cleanModel(synthesizerModel);
        List<String> modelCandidates = synthesisFallbacks(targetModel);

        String systemPrompt = promptFactory.buildSynthesisFormattingSystemPrompt();
        String userPrompt = buildSynthesisUserPrompt(originalQuery, safeDraft, bestText);

        List<String> failureNotes = new ArrayList<>();

        int attempt = 0;

        for (String model : modelCandidates) {
            if (attempt >= MAX_SYNTHESIS_ATTEMPTS) {
                break;
            }

            attempt++;

            try {
                String rawJson = executeStructured(model, systemPrompt, userPrompt);

                if (isCorrupted(rawJson)) {
                    failureNotes.add("Attempt " + attempt + " returned corrupted synthesis text from " + model + ".");
                    continue;
                }

                StructuredResponse parsed = parseStructuredResponse(rawJson);

                if (parsed == null) {
                    failureNotes.add("Attempt " + attempt + " returned unparsable JSON from " + model + ".");
                    continue;
                }

                parsed.normalize();

                if (isCorrupted(parsed.getSummary()) || parsed.getSummary().isBlank()) {
                    failureNotes.add("Attempt " + attempt + " produced blank/corrupted summary from " + model + ".");
                    continue;
                }

                parsed.setSummary(cleanFinalSummary(parsed.getSummary(), bestText));
                parsed.setRaw(rawJson);
                parsed.putMeta("synthesizerModel", model);
                parsed.putMeta("synthesisFallbackUsed", !model.equals(targetModel));
                parsed.putMeta("synthesisAttempts", attempt);

                return parsed.normalize();
            } catch (Exception e) {
                failureNotes.add("Attempt " + attempt + " failed on " + model + ": " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            }
        }

        return fallbackFromBestDraft(safeDraft, bestText, failureNotes);
    }

    private String executeStructured(
            String model,
            String systemPrompt,
            String userPrompt
    ) {
        String lower = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gemini")) {
            if (gemini == null) {
                throw new IllegalStateException("Gemini synthesizer requested but Gemini client is null.");
            }
            return gemini.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
        }

        if (lower.startsWith("claude")) {
            if (claude == null) {
                throw new IllegalStateException("Claude synthesizer requested but Claude client is null.");
            }
            return claude.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
        }

        if (openAi == null) {
            throw new IllegalStateException("OpenAI synthesizer requested but OpenAI client is null.");
        }

        return openAi.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
    }

    private List<String> synthesisFallbacks(String preferredModel) {
        List<String> models = new ArrayList<>();

        addUnique(models, preferredModel);

        String lower = preferredModel.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gemini")) {
            addUnique(models, "gpt-4.1-mini");
        } else if (lower.startsWith("claude")) {
            addUnique(models, "gpt-4.1-mini");
        } else {
            addUnique(models, "gpt-4.1-mini");
            addUnique(models, "gemini-3.1-flash-lite-preview");
        }

        return models;
    }

    private void addUnique(List<String> models, String model) {
        if (model == null || model.isBlank()) {
            return;
        }

        String cleaned = model.trim();

        if (!models.contains(cleaned)) {
            models.add(cleaned);
        }
    }

    private String buildSynthesisUserPrompt(
            String originalQuery,
            StructuredResponse draft,
            String bestText
    ) {
        String safeQuery = originalQuery == null ? "" : originalQuery.trim();

        StringBuilder sb = new StringBuilder();

        sb.append("Original User Query:\n");
        sb.append(limit(safeQuery, 20_000)).append("\n\n");

        sb.append("Best Draft Text To Preserve:\n");
        sb.append(limit(bestText, MAX_INPUT_CHARS)).append("\n\n");

        sb.append("Existing Draft Fields:\n");
        sb.append("thought_process: ").append(limit(draft.getThought_process(), 2000)).append("\n");
        sb.append("summary: ").append(limit(draft.getSummary(), 40_000)).append("\n");
        sb.append("convo: ").append(limit(draft.getConvo(), 5000)).append("\n");
        sb.append("spoken_summary: ").append(limit(draft.getSpoken_summary(), 3000)).append("\n\n");

        sb.append("Finalization rules:\n");
        sb.append("- Preserve the answer content.\n");
        sb.append("- Do not invent new facts.\n");
        sb.append("- Do not mention synthesis, critic, repair, internal agent stages, or hidden process.\n");
        sb.append("- If code exists, preserve complete code blocks.\n");
        sb.append("- If the draft is already good, mostly keep it and only clean formatting.\n");
        sb.append("- Return only JSON matching the StructuredResponse schema.\n");

        return sb.toString();
    }

    private StructuredResponse parseStructuredResponse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }

        String json = extractJsonObject(rawJson);
        if (json.isBlank()) {
            return null;
        }

        try {
            return mapper.readValue(json, StructuredResponse.class);
        } catch (Exception firstFailure) {
            return tryLooseParse(json);
        }
    }

    private StructuredResponse tryLooseParse(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            StructuredResponse response = new StructuredResponse();

            response.setThought_process(firstText(root,
                    "thought_process",
                    "thoughtProcess",
                    "reasoning",
                    "public_reasoning"
            ));

            response.setSummary(firstText(root,
                    "summary",
                    "answer",
                    "response",
                    "content",
                    "final_answer",
                    "finalAnswer",
                    "output",
                    "markdown"
            ));

            response.setConvo(firstText(root,
                    "convo",
                    "conversation",
                    "follow_up",
                    "followUp",
                    "message"
            ));

            response.setSpoken_summary(firstText(root,
                    "spoken_summary",
                    "spokenSummary",
                    "tts",
                    "tts_summary",
                    "voice_summary"
            ));

            response.setRaw(json);

            return response.normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(JsonNode root, String... keys) {
        if (root == null || keys == null) {
            return "";
        }

        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && !node.isNull()) {
                if (node.isTextual()) {
                    return node.asText("");
                }
                return node.toString();
            }
        }

        return "";
    }

    private StructuredResponse fallbackFromBestDraft(
            StructuredResponse safeDraft,
            String bestText,
            List<String> failureNotes
    ) {
        StructuredResponse fallback = new StructuredResponse();

        fallback.setThought_process("Synthesis failed safely; returned best available draft.");
        fallback.setSummary(cleanFinalSummary(bestText, bestText));
        fallback.setConvo(safeDraft.getConvo());
        fallback.setSpoken_summary(safeDraft.getSpoken_summary());
        fallback.setRaw(safeDraft.getRaw());

        fallback.putMeta("synthesisFallback", true);
        fallback.putMeta("synthesisFailureNotes", failureNotes == null ? List.of() : failureNotes);

        return fallback.normalize();
    }

    private String chooseBestDraftText(StructuredResponse draft) {
        String summary = draft.getSummary();
        String raw = draft.getRaw();
        String convo = draft.getConvo();

        if (isUsableUserFacingText(summary)) {
            return summary;
        }

        if (isUsableUserFacingText(raw)) {
            StructuredResponse parsedRaw = parseStructuredResponse(raw);
            if (parsedRaw != null && isUsableUserFacingText(parsedRaw.getSummary())) {
                return parsedRaw.getSummary();
            }

            return raw;
        }

        if (isUsableUserFacingText(convo)) {
            return convo;
        }

        return firstNonBlank(summary, raw, convo);
    }

    private boolean isUsableUserFacingText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String cleaned = text.trim();

        if (isCorrupted(cleaned)) {
            return false;
        }

        if (cleaned.equals("{}") || cleaned.equals("[]") || cleaned.equalsIgnoreCase("null")) {
            return false;
        }

        return cleaned.length() >= 2;
    }

    private String cleanFinalSummary(String summary, String fallback) {
        String chosen = firstNonBlank(summary, fallback);

        if (chosen.isBlank()) {
            return "The agent completed the task, but the final text was empty.";
        }

        String cleaned = chosen.trim();

        if (looksLikeStructuredResponseJson(cleaned)) {
            StructuredResponse parsed = parseStructuredResponse(cleaned);
            if (parsed != null && !parsed.getSummary().isBlank()) {
                cleaned = parsed.getSummary();
            }
        }

        cleaned = cleaned.replace("empty json returned", "").trim();

        if (cleaned.isBlank()) {
            return fallback == null || fallback.isBlank()
                    ? "The agent completed the task, but the final text was empty."
                    : fallback.trim();
        }

        return cleaned;
    }

    private boolean looksLikeStructuredResponseJson(String text) {
        if (text == null) {
            return false;
        }

        String trimmed = text.trim();

        return trimmed.startsWith("{") &&
                trimmed.endsWith("}") &&
                (trimmed.contains("\"summary\"") ||
                        trimmed.contains("\"thought_process\"") ||
                        trimmed.contains("\"spoken_summary\""));
    }

    private boolean isCorrupted(String text) {
        if (text == null) {
            return true;
        }

        String lower = text.toLowerCase(Locale.ROOT).trim();

        return lower.isBlank() ||
                lower.contains("empty json returned") ||
                lower.contains("generated an empty response") ||
                lower.contains("empty response payload") ||
                lower.equals("{}") ||
                lower.equals("[]") ||
                lower.equals("null");
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String cleaned = raw.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end >= start) {
            return cleaned.substring(start, end + 1);
        }

        return "";
    }

    private String cleanModel(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_SYNTH_MODEL;
        }

        return model.trim();
    }

    private String firstNonBlank(String... values) {
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

    private String limit(String text, int maxChars) {
        if (text == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);

        if (text.length() <= safeMax) {
            return text;
        }

        return text.substring(0, safeMax) + "\n...[TRUNCATED_FOR_SYNTHESIS]";
    }

    private String safeMessage(Exception e) {
        if (e == null) {
            return "";
        }

        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }
}
