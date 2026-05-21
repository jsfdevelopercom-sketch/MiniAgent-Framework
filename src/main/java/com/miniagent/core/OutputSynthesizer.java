package com.miniagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

import java.time.Duration;
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
 *
 * Critical code-safety rule:
 * - If the user asked for code and the draft contains code, do not send it to a
 * final cheap synthesis model. Return the draft directly after deterministic
 * cleanup. Model synthesis can reorder HTML/CSS/JS, remove braces, escape
 * content incorrectly, shrink implementations, or turn complete code into a
 * toy example.
 */
public class OutputSynthesizer {

    private static final int MAX_SYNTHESIS_ATTEMPTS = 2;
    private static final int MAX_INPUT_CHARS = 120_000;
    private static final String DEFAULT_SYNTH_MODEL = ModelConstants.GPT_4_1_MINI;

    /*
     * Final synthesis is deliberately tiny. The synthesizer is a formatter, not
     * a second problem-solving agent. Large/code answers should bypass synthesis
     * before this budget is used. Non-code prose cleanup should finish quickly.
     */
    private static final int SYNTHESIS_MAX_OUTPUT_TOKENS = 800;
    private static final Duration SYNTHESIS_TIMEOUT = Duration.ofSeconds(25);

    private final OpenAiHttpClient openAi;
    private final GeminiHttpClient gemini;
    private final ClaudeHttpClient claude;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    /**
     * Backward-compatible constructor.
     *
     * Older Agent-Nero / MiniAgent integration code may still construct the
     * synthesizer with only OpenAI, Gemini, PromptFactory, and ObjectMapper.
     * This constructor preserves that path and delegates to the Claude-aware
     * constructor with a null Claude client.
     */
    public OutputSynthesizer(
            OpenAiHttpClient openAi,
            GeminiHttpClient gemini,
            PromptFactory promptFactory,
            ObjectMapper mapper) {
        this(openAi, gemini, null, promptFactory, mapper);
    }

    /**
     * Preferred constructor.
     *
     * This constructor supports all currently wired provider clients. The final
     * synthesis layer is intentionally small and conservative, but when a route
     * asks for a Claude or Gemini synthesizer, the corresponding client must be
     * available or the call will fail cleanly and fall back to the best draft.
     */
    public OutputSynthesizer(
            OpenAiHttpClient openAi,
            GeminiHttpClient gemini,
            ClaudeHttpClient claude,
            PromptFactory promptFactory,
            ObjectMapper mapper) {
        this.openAi = openAi;
        this.gemini = gemini;
        this.claude = claude;
        this.promptFactory = promptFactory;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * Produces the final user-facing StructuredResponse.
     *
     * For prose tasks, this method may use a cheap synthesizer model to clean
     * JSON leakage and improve final formatting. For code tasks, it bypasses
     * model synthesis entirely once code is detected. That bypass is deliberate:
     * a final formatting model must not be allowed to mutate line ordering,
     * remove syntax, compress features, or interleave HTML/CSS/JavaScript.
     */
    public StructuredResponse synthesize(
            StructuredResponse draft,
            String originalQuery,
            String synthesizerModel,
            AgentRunPlan plan) {
        StructuredResponse safeDraft = draft == null
                ? StructuredResponse.empty()
                : draft.normalize();

        String bestText = chooseBestDraftText(safeDraft);

        boolean isHeavyTask = plan != null && plan.shouldSkipLargeAnswerSynthesis();

        if (isHeavyTask) {
            return preserveHeavyDraftWithoutModelSynthesis(safeDraft, bestText);
        }

        if (shouldBypassSynthesisForCode(bestText, originalQuery)) {
            return preserveCodeDraftWithoutModelSynthesis(safeDraft, bestText);
        }

        if (bestText.isBlank()) {
            return StructuredResponse.failure(
                    "The agent finished, but no usable answer text was produced.",
                    "SYNTHESIS_NO_DRAFT_TEXT");
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
                failureNotes.add("Attempt " + attempt + " failed on " + model + ": "
                        + e.getClass().getSimpleName() + ": " + safeMessage(e));
            }
        }

        return fallbackFromBestDraft(safeDraft, bestText, failureNotes);
    }

    /**
     * Detects whether model-based final synthesis must be skipped.
     *
     * This deliberately does not require the answer to be "large enough".
     * A short code draft can be short because an upstream generation failed.
     * Running that fragile draft through a cheap final synthesizer often makes
     * it worse by scrambling syntax or hiding the real upstream failure.
     */
    private boolean shouldBypassSynthesisForCode(String text, String originalQuery) {
        String answer = text == null ? "" : text;
        String query = originalQuery == null ? "" : originalQuery.toLowerCase(Locale.ROOT);

        boolean userAskedForCode = userQueryLooksLikeCodeRequest(query);
        boolean answerHasCode = answerLooksLikeCode(answer);

        return userAskedForCode && answerHasCode;
    }

    /**
     * Checks whether the user query is asking for code or software engineering
     * work. This is intentionally broad and language-inclusive. The purpose is
     * not final task classification; the MiniAgent classifier already did that.
     * The purpose here is only to protect generated code from final synthesis.
     */
    private boolean userQueryLooksLikeCodeRequest(String queryLower) {
        if (queryLower == null || queryLower.isBlank()) {
            return false;
        }

        return queryLower.contains("code") ||
                queryLower.contains("coding") ||
                queryLower.contains("program") ||
                queryLower.contains("script") ||
                queryLower.contains("software") ||
                queryLower.contains("app") ||
                queryLower.contains("application") ||
                queryLower.contains("html") ||
                queryLower.contains("css") ||
                queryLower.contains("javascript") ||
                queryLower.contains("typescript") ||
                queryLower.contains("java") ||
                queryLower.contains("kotlin") ||
                queryLower.contains("python") ||
                queryLower.contains("c++") ||
                queryLower.contains("cpp") ||
                queryLower.contains("c#") ||
                queryLower.contains("csharp") ||
                queryLower.contains("go ") ||
                queryLower.contains("golang") ||
                queryLower.contains("rust") ||
                queryLower.contains("swift") ||
                queryLower.contains("php") ||
                queryLower.contains("ruby") ||
                queryLower.contains("scala") ||
                queryLower.contains("runnable") ||
                queryLower.contains("compile") ||
                queryLower.contains("compiler") ||
                queryLower.contains("debug") ||
                queryLower.contains("bug") ||
                queryLower.contains("backend") ||
                queryLower.contains("frontend") ||
                queryLower.contains("api") ||
                queryLower.contains("server") ||
                queryLower.contains("database") ||
                queryLower.contains("sql") ||
                queryLower.contains("xml") ||
                queryLower.contains("json") ||
                queryLower.contains("yaml") ||
                queryLower.contains("gradle") ||
                queryLower.contains("maven") ||
                queryLower.contains("spring") ||
                queryLower.contains("android") ||
                queryLower.contains("compose") ||
                queryLower.contains("react") ||
                queryLower.contains("vue") ||
                queryLower.contains("node") ||
                queryLower.contains("express") ||
                queryLower.contains("editor") ||
                queryLower.contains("ide") ||
                queryLower.contains("visual studio") ||
                queryLower.contains("vs code") ||
                queryLower.contains("complete") ||
                queryLower.contains("fully working") ||
                queryLower.contains("working code") ||
                queryLower.contains("production") ||
                queryLower.contains("full file") ||
                queryLower.contains("entire file");
    }

    /**
     * Checks whether the current answer text contains real code.
     *
     * This is intentionally structural rather than relying on a single language.
     * It catches HTML documents, scripts, common language keywords, imports,
     * package declarations, SQL, shell scripts, JSON/YAML-like structures, and
     * fenced code blocks.
     */
    private boolean answerLooksLikeCode(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }

        String lower = answer.toLowerCase(Locale.ROOT);

        return answer.contains("```") ||
                lower.contains("<!doctype html") ||
                lower.contains("<html") ||
                lower.contains("<head") ||
                lower.contains("<body") ||
                lower.contains("<script") ||
                lower.contains("<style") ||
                lower.contains("</html>") ||
                lower.contains("function ") ||
                lower.contains("class ") ||
                lower.contains("interface ") ||
                lower.contains("enum ") ||
                lower.contains("const ") ||
                lower.contains("let ") ||
                lower.contains("var ") ||
                lower.contains("public class") ||
                lower.contains("private ") ||
                lower.contains("protected ") ||
                lower.contains("import ") ||
                lower.contains("package ") ||
                lower.contains("@composable") ||
                lower.contains("fun ") ||
                lower.contains("def ") ||
                lower.contains("async ") ||
                lower.contains("await ") ||
                lower.contains("#!/") ||
                lower.contains("select ") ||
                lower.contains("insert into ") ||
                lower.contains("create table ") ||
                lower.contains("springapplication.run") ||
                lower.contains("document.getelementbyid") ||
                lower.contains("addeventlistener") ||
                lower.contains("monaco.editor") ||
                lower.contains("localstorage") ||
                lower.contains("json.parse") ||
                lower.contains("json.stringify");
    }

    /**
     * Returns a code draft directly without sending it to a final model.
     *
     * The method still normalizes the StructuredResponse and ensures the summary
     * is populated, but it does not alter the code body through another LLM call.
     * Metadata is attached so Railway logs and frontend responses can confirm
     * that the protective bypass fired.
     */
    private StructuredResponse preserveCodeDraftWithoutModelSynthesis(
            StructuredResponse safeDraft,
            String bestText) {
        StructuredResponse direct = safeDraft == null
                ? StructuredResponse.empty()
                : safeDraft.normalize();

        String currentSummary = direct.getSummary();

        if (currentSummary == null || currentSummary.isBlank()) {
            direct.setSummary(bestText == null ? "" : bestText);
        } else {
            direct.setSummary(cleanFinalSummary(currentSummary, bestText));
        }

        direct.setThought_process("Code answer preserved without destructive final synthesis.");
        direct.setSpoken_summary("I have prepared the full code on screen.");
        direct.putMeta("synthesisSkipped", true);
        direct.putMeta("synthesisSkipReason", "code_answer_preserved");
        direct.putMeta("synthesisBypassRule", "user_asked_for_code_and_answer_contains_code");

        return direct.normalize();
    }

    /**
     * Returns a heavy draft directly without sending it to a final model.
     * Uses hardcoded cleanup to ensure formatting is presentable without LLM
     * latency.
     */
    private StructuredResponse preserveHeavyDraftWithoutModelSynthesis(
            StructuredResponse safeDraft,
            String bestText) {
        StructuredResponse direct = safeDraft == null
                ? StructuredResponse.empty()
                : safeDraft.normalize();

        String currentSummary = direct.getSummary();

        if (currentSummary == null || currentSummary.isBlank()) {
            direct.setSummary(bestText == null ? "" : bestText);
        } else {
            direct.setSummary(cleanFinalSummary(currentSummary, bestText));
        }

        direct.setThought_process("Heavy task answer preserved without destructive final synthesis.");
        direct.setSpoken_summary("I have prepared the full output on screen.");
        direct.putMeta("synthesisSkipped", true);
        direct.putMeta("synthesisSkipReason", "heavy_task_answer_preserved");
        direct.putMeta("synthesisBypassRule", "classified_as_hard_or_large_token_budget");

        return direct.normalize();
    }

    /**
     * Executes one bounded structured synthesis call with the requested provider.
     *
     * This method is intentionally provider-thin: it selects the correct client,
     * passes the same small synthesis budget to every provider, and lets the
     * provider client build its own API-specific request shape. OpenAI uses
     * response_format / Responses text.format depending on model family; Gemini
     * uses generationConfig.maxOutputTokens; Claude uses max_tokens. This method
     * should not know those wire-format details.
     *
     * Future debugger note:
     * If a large code answer reaches this method, the bug is probably not here.
     * Check Agent.finishFromStopDecision(...) and
     * shouldBypassSynthesisForCode(...).
     * Synthesis is only supposed to format smaller prose answers.
     */
    private String executeStructured(
            String model,
            String systemPrompt,
            String userPrompt) {
        String lower = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gemini")) {
            if (gemini == null) {
                throw new IllegalStateException("Gemini synthesizer requested but Gemini client is null.");
            }

            /*
             * Gemini has its own HTTP schema, but the public client overload keeps
             * stage budgeting consistent with OpenAI and Claude. The synthesizer
             * should receive only enough output room for JSON formatting.
             */
            return gemini.executeStructuredCall(
                    model,
                    systemPrompt,
                    userPrompt,
                    0.0,
                    null,
                    SYNTHESIS_MAX_OUTPUT_TOKENS,
                    SYNTHESIS_TIMEOUT);
        }

        if (lower.startsWith("claude")) {
            if (claude == null) {
                throw new IllegalStateException("Claude synthesizer requested but Claude client is null.");
            }

            /*
             * Claude synthesis is also bounded. A long Claude call here would be a
             * design smell because the heavy reasoning/code work should already be
             * finished by the worker/repair stages.
             */
            return claude.executeStructuredCall(
                    model,
                    systemPrompt,
                    userPrompt,
                    0.0,
                    null,
                    SYNTHESIS_MAX_OUTPUT_TOKENS,
                    SYNTHESIS_TIMEOUT);
        }

        if (openAi == null) {
            throw new IllegalStateException("OpenAI synthesizer requested but OpenAI client is null.");
        }

        /*
         * OpenAI gets the same small budget. This prevents a final formatting pass
         * from accidentally becoming another long reasoning call.
         */
        return openAi.executeStructuredCall(
                model,
                systemPrompt,
                userPrompt,
                0.0,
                null,
                SYNTHESIS_MAX_OUTPUT_TOKENS,
                SYNTHESIS_TIMEOUT);
    }

    /**
     * Builds a small fallback list for synthesis.
     *
     * Synthesis is intentionally cheap because this layer is not supposed to
     * solve the task again. It only formats non-code responses into the expected
     * StructuredResponse shape. Code responses should already have bypassed this.
     */
    private List<String> synthesisFallbacks(String preferredModel) {
        List<String> models = new ArrayList<>();

        addUnique(models, preferredModel);

        String lower = preferredModel == null ? "" : preferredModel.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gemini")) {
            addUnique(models, ModelConstants.GPT_4_1_MINI);
        } else if (lower.startsWith("claude")) {
            addUnique(models, ModelConstants.GPT_4_1_MINI);
        } else {
            addUnique(models, ModelConstants.GPT_4_1_MINI);
            addUnique(models, ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW);
        }

        return models;
    }

    /**
     * Adds a model to a candidate list only once.
     */
    private void addUnique(List<String> models, String model) {
        if (models == null || model == null || model.isBlank()) {
            return;
        }

        String cleaned = model.trim();

        if (!models.contains(cleaned)) {
            models.add(cleaned);
        }
    }

    /**
     * Builds the user prompt used for non-code synthesis.
     *
     * This prompt tells the synthesizer to preserve content and avoid internal
     * process leakage. It is intentionally conservative. Code answers should not
     * reach this method when the bypass rule fires correctly.
     */
    private String buildSynthesisUserPrompt(
            String originalQuery,
            StructuredResponse draft,
            String bestText) {
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
        sb.append("- If code exists, preserve complete code blocks without changing line order.\n");
        sb.append("- If the draft is already good, mostly keep it and only clean formatting.\n");
        sb.append("- Return only JSON matching the StructuredResponse schema.\n");

        return sb.toString();
    }

    /**
     * Parses a structured response JSON string.
     *
     * Provider responses sometimes include fenced JSON or extra text around the
     * JSON object. This method extracts the object first and then attempts both
     * strict and loose parsing.
     */
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

    /**
     * Performs loose parsing when the model returns compatible JSON with
     * non-exact field names.
     */
    private StructuredResponse tryLooseParse(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            StructuredResponse response = new StructuredResponse();

            response.setThought_process(firstText(root,
                    "thought_process",
                    "thoughtProcess",
                    "reasoning",
                    "public_reasoning"));

            response.setSummary(firstText(root,
                    "summary",
                    "answer",
                    "response",
                    "content",
                    "final_answer",
                    "finalAnswer",
                    "output",
                    "markdown"));

            response.setConvo(firstText(root,
                    "convo",
                    "conversation",
                    "follow_up",
                    "followUp",
                    "message"));

            response.setSpoken_summary(firstText(root,
                    "spoken_summary",
                    "spokenSummary",
                    "tts",
                    "tts_summary",
                    "voice_summary"));

            response.setRaw(json);

            return response.normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Returns the first textual field found in a JSON object.
     */
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

    /**
     * Safe fallback when every model-based synthesis attempt fails.
     *
     * This preserves the best available draft instead of returning an empty or
     * internal error-looking answer. The failure notes are retained in metadata.
     */
    private StructuredResponse fallbackFromBestDraft(
            StructuredResponse safeDraft,
            String bestText,
            List<String> failureNotes) {
        StructuredResponse fallback = new StructuredResponse();

        fallback.setThought_process("Synthesis failed safely; returned best available draft.");
        fallback.setSummary(cleanFinalSummary(bestText, bestText));
        fallback.setConvo(safeDraft == null ? "" : safeDraft.getConvo());
        fallback.setSpoken_summary(safeDraft == null ? "" : safeDraft.getSpoken_summary());
        fallback.setRaw(safeDraft == null ? "" : safeDraft.getRaw());

        fallback.putMeta("synthesisFallback", true);
        fallback.putMeta("synthesisFailureNotes", failureNotes == null ? List.of() : failureNotes);

        return fallback.normalize();
    }

    /**
     * Selects the best user-facing text from the draft.
     *
     * Summary is preferred. Raw is parsed if it appears to contain a nested
     * StructuredResponse. Conversation text is used only if summary/raw are not
     * usable.
     */
    private String chooseBestDraftText(StructuredResponse draft) {
        if (draft == null) {
            return "";
        }

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

    /**
     * Checks whether text is usable as a final answer candidate.
     */
    private boolean isUsableUserFacingText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String cleaned = text.trim();

        if (isCorrupted(cleaned)) {
            return false;
        }

        return !cleaned.equals("{}")
                && !cleaned.equals("[]")
                && !cleaned.equalsIgnoreCase("null")
                && cleaned.length() >= 2;
    }

    /**
     * Performs deterministic final cleanup.
     *
     * This method does not reorder or rewrite code. It only unwraps accidental
     * StructuredResponse JSON and removes known empty-response phrases.
     */
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

    /**
     * Detects whether text appears to be a serialized StructuredResponse object.
     */
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

    /**
     * Detects obviously unusable or empty provider output.
     */
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

    /**
     * Extracts a JSON object from raw provider output.
     *
     * This handles fenced markdown JSON as well as models that include a small
     * amount of text before or after the object.
     */
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

    /**
     * Normalizes the requested synthesizer model.
     */
    private String cleanModel(String model) {
        if (model == null || model.isBlank()) {
            return DEFAULT_SYNTH_MODEL;
        }

        return model.trim();
    }

    /**
     * Returns the first non-blank string from the provided values.
     */
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

    /**
     * Limits text length before sending it to a synthesis model.
     */
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

    /**
     * Safely extracts an exception message for logs and failure metadata.
     */
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