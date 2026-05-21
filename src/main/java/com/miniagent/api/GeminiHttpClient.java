package com.miniagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.AgentConfig;
import com.miniagent.core.ModelConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GeminiHttpClient is MiniAgent's provider adapter for Google's Gemini API.
 *
 * This class exists so the rest of MiniAgent can speak in MiniAgent terms:
 * text call, structured call, max output tokens, timeout, temperature, and
 * conversation history. Gemini itself expects a different wire shape:
 *
 * - endpoint: /v1beta/models/{model}:generateContent
 * - content list: contents[].role + contents[].parts[].text
 * - output budget: generationConfig.maxOutputTokens
 * - JSON mode: generationConfig.responseMimeType = application/json
 * - thinking control: generationConfig.thinkingConfig for newer Gemini models
 *
 * The orchestrator should not care about those provider-specific field names.
 * It should pass the stage budget decided by TaskClassifier/AgentRunPlan, and
 * this client should map that budget into Gemini's native request format.
 *
 * Runtime design:
 *
 * - Large freeform worker calls should use executeTextCall(..., maxTokens,
 * timeout).
 * - Critic/classifier/synthesis JSON calls should use
 * executeStructuredCall(...).
 * - This client does not hide long retry loops. SafeThoughtExecutor owns
 * fallback
 * between models/providers. Hidden retries here would make one stage consume
 * several minutes while the agent believes only one model attempt happened.
 */
public class GeminiHttpClient {

    private static final String GEMINI_GENERATE_CONTENT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final int DEFAULT_TEXT_MAX_OUTPUT_TOKENS = 6500;
    private static final int DEFAULT_STRUCTURED_MAX_OUTPUT_TOKENS = 1200;

    private static final int MIN_OUTPUT_TOKENS = 256;
    private static final int MAX_TEXT_OUTPUT_TOKENS = 7000;
    private static final int MAX_STRUCTURED_OUTPUT_TOKENS = 3000;

    private static final Duration DEFAULT_TEXT_TIMEOUT = Duration.ofSeconds(115);
    private static final Duration DEFAULT_STRUCTURED_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(45);

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Creates a Gemini provider client bound to the shared MiniAgent config.
     *
     * The config provides the API key and default Gemini model. The ObjectMapper is
     * injected so the entire MiniAgent stack can use the same JSON configuration.
     * The Java HttpClient is intentionally created once and reused because these
     * provider clients may be called many times during one deep-thinking run.
     */
    public GeminiHttpClient(AgentConfig config, ObjectMapper mapper) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig cannot be null.");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper cannot be null.");
        }

        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Returns the live MiniAgent config used by this client.
     *
     * Other components use this for diagnostics and API-key readiness checks. This
     * method does not expose secrets directly; it simply returns the same config
     * object that was already injected into the provider layer.
     */
    public AgentConfig getConfig() {
        return config;
    }

    /**
     * Backward-compatible structured call used by older code paths.
     *
     * Newer code should call the overload that accepts maxOutputTokens and timeout,
     * but this method is retained so legacy construction and tests keep compiling.
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Backward-compatible structured call with optional temperature/history.
     *
     * The old implementation gave high models very large token budgets and long
     * timeouts. That made fallback stages unpredictable. This compatibility method
     * now uses critic-like defaults: small JSON budget and short timeout.
     */
    public String executeStructuredCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history) {
        return executeStructuredCall(
                model,
                systemPrompt,
                userPrompt,
                temperature,
                history,
                DEFAULT_STRUCTURED_MAX_OUTPUT_TOKENS,
                DEFAULT_STRUCTURED_TIMEOUT);
    }

    /**
     * Stage-aware structured Gemini call.
     *
     * This path should be used for classifier, critic/evaluator, and small final
     * metadata stages. It explicitly asks Gemini for application/json and strips
     * markdown fences if the model still returns fenced JSON.
     */
    public String executeStructuredCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxOutputTokens,
            Duration timeout) {
        String targetModel = resolveModel(model);
        int safeMaxTokens = clamp(maxOutputTokens, MIN_OUTPUT_TOKENS, MAX_STRUCTURED_OUTPUT_TOKENS);
        Duration safeTimeout = effectiveTimeout(timeout, DEFAULT_STRUCTURED_TIMEOUT);

        Map<String, Object> request = buildGenerateContentRequest(
                targetModel,
                ensureJsonSystemPrompt(systemPrompt),
                ensureJsonUserPrompt(userPrompt),
                temperature,
                history,
                safeMaxTokens,
                true);

        HttpResponse<String> response = sendGeminiRequest(
                "GEMINI STRUCTURED",
                targetModel,
                request,
                safeTimeout,
                safeMaxTokens);

        return stripMarkdownCodeFence(extractGeminiText(response.body(), targetModel)).trim();
    }

    /**
     * Backward-compatible plain text call.
     *
     * Legacy callers land here. They receive the current worker-like defaults,
     * but the preferred MiniAgent path should use the explicit budget overload.
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Backward-compatible text call with optional temperature.
     *
     * This wrapper keeps the public API stable while preventing old callers from
     * accidentally getting a 10-minute high-model request.
     */
    public String executeTextCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature) {
        return executeTextCall(
                model,
                systemPrompt,
                userPrompt,
                temperature,
                DEFAULT_TEXT_MAX_OUTPUT_TOKENS,
                DEFAULT_TEXT_TIMEOUT);
    }

    /**
     * Stage-aware freeform Gemini call.
     *
     * MiniAgentWorker should use this method for first-draft and repair stages
     * when Gemini is selected as the generator/fallback. The output is direct
     * user-facing text. It is not forced into a MiniAgent JSON wrapper.
     */
    public String executeTextCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            int maxOutputTokens,
            Duration timeout) {
        String targetModel = resolveModel(model);
        int safeMaxTokens = clamp(maxOutputTokens, MIN_OUTPUT_TOKENS, MAX_TEXT_OUTPUT_TOKENS);
        Duration safeTimeout = effectiveTimeout(timeout, DEFAULT_TEXT_TIMEOUT);

        Map<String, Object> request = buildGenerateContentRequest(
                targetModel,
                systemPrompt,
                userPrompt,
                temperature,
                null,
                safeMaxTokens,
                false);

        HttpResponse<String> response = sendGeminiRequest(
                "GEMINI TEXT",
                targetModel,
                request,
                safeTimeout,
                safeMaxTokens);

        return extractGeminiText(response.body(), targetModel).trim();
    }

    /**
     * Builds the provider-native Gemini generateContent request.
     *
     * This method is the single place where MiniAgent's provider-neutral stage
     * budget becomes Gemini's request shape. Keeping that mapping centralized
     * prevents future edits from accidentally reintroducing old hidden 12000-token
     * or 10-minute behavior in one path but not another.
     */
    private Map<String, Object> buildGenerateContentRequest(
            String targetModel,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxOutputTokens,
            boolean jsonMode) {
        Map<String, Object> request = new LinkedHashMap<>();

        request.put("contents", buildContents(systemPrompt, userPrompt, history));
        request.put("generationConfig", buildGenerationConfig(
                targetModel,
                temperature,
                maxOutputTokens,
                jsonMode));
        request.put("safetySettings", buildSafetySettings());

        return request;
    }

    /**
     * Converts MiniAgent prompts/history into Gemini contents[].
     *
     * Gemini uses role="user" for user turns and role="model" for assistant/model
     * turns. History is kept lightweight and plain-text because the
     * worker/evaluator
     * already receives the important context in the current prompt.
     */
    private List<Map<String, Object>> buildContents(
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> history) {
        List<Map<String, Object>> contents = new ArrayList<>();

        if (history != null) {
            for (Map<String, String> item : history) {
                if (item == null) {
                    continue;
                }

                String content = item.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }

                String role = normalizeGeminiRole(item.get("role"));
                contents.add(content(role, content));
            }
        }

        StringBuilder currentTurn = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            currentTurn.append("SYSTEM INSTRUCTION:\n")
                    .append(systemPrompt.trim())
                    .append("\n\n");
        }
        currentTurn.append("USER PROMPT:\n")
                .append(userPrompt == null ? "" : userPrompt);

        contents.add(content("user", currentTurn.toString()));
        return contents;
    }

    /**
     * Creates one Gemini content object.
     *
     * The API supports multiple parts, but MiniAgent currently sends plain text.
     * Keeping this as a helper makes future multimodal/tool parts easier to add.
     */
    private Map<String, Object> content(String role, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", normalizeGeminiRole(role));
        content.put("parts", List.of(Map.of("text", text == null ? "" : text)));
        return content;
    }

    /**
     * Builds Gemini generationConfig.
     *
     * maxOutputTokens is always set from the caller's stage budget. For newer
     * Gemini Pro-style thinking models, thinking is kept low/minimal rather than
     * high so fallback providers do not become another long hidden reasoning loop.
     */
    private Map<String, Object> buildGenerationConfig(
            String targetModel,
            Double temperature,
            int maxOutputTokens,
            boolean jsonMode) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", maxOutputTokens);

        if (jsonMode) {
            generationConfig.put("responseMimeType", "application/json");
        }

        if (temperature != null) {
            generationConfig.put("temperature", clampTemperature(temperature));
        }

        Map<String, Object> thinkingConfig = buildThinkingConfig(targetModel);
        if (!thinkingConfig.isEmpty()) {
            generationConfig.put("thinkingConfig", thinkingConfig);
        }

        return generationConfig;
    }

    /**
     * Builds a conservative Gemini thinking configuration.
     *
     * Gemini model families have evolved across versions. The previous code used
     * high thinking for Gemini Pro preview, which is the opposite of our current
     * bounded-stage design. This helper keeps the setting local and easy to update
     * if Google changes the exact field names for future models.
     */
    private Map<String, Object> buildThinkingConfig(String targetModel) {
        Map<String, Object> thinkingConfig = new LinkedHashMap<>();
        String normalized = targetModel == null ? "" : targetModel.toLowerCase(Locale.ROOT);

        if (normalized.contains("gemini-3") || normalized.contains("3.1")) {
            thinkingConfig.put("thinkingLevel", "low");
        }

        return thinkingConfig;
    }

    /**
     * Returns safety settings matching the previous integration behavior.
     *
     * Provider safety still applies at the account/model layer. These per-request
     * settings avoid an overly aggressive category block turning into an empty
     * payload for normal developer prompts.
     */
    private List<Map<String, String>> buildSafetySettings() {
        List<Map<String, String>> safetySettings = new ArrayList<>();
        safetySettings.add(Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"));
        safetySettings.add(Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"));
        safetySettings.add(Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"));
        safetySettings.add(Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE"));
        return safetySettings;
    }

    /**
     * Sends the HTTP request to Gemini and throws on provider errors.
     *
     * Throwing is intentional. Returning a fake string such as "Gemini failed" as
     * if it were an answer makes SafeThoughtExecutor treat transport failure as a
     * successful generation. Exceptions are the correct signal for
     * fallback/recovery.
     */
    private HttpResponse<String> sendGeminiRequest(
            String pathName,
            String targetModel,
            Map<String, Object> request,
            Duration timeout,
            int maxOutputTokens) {
        String apiKey = requireGeminiApiKey();

        try {
            String requestBody = mapper.writeValueAsString(request);
            String url = GEMINI_GENERATE_CONTENT_BASE_URL
                    + targetModel
                    + ":generateContent?key="
                    + apiKey;

            System.out.println("GEMINI REQUEST PATH = " + pathName);
            System.out.println("GEMINI REQUEST MODEL = " + targetModel);
            System.out.println("GEMINI REQUEST TIMEOUT_SECONDS = " + timeout.toSeconds());
            System.out.println("GEMINI REQUEST MAX_OUTPUT_TOKENS = " + maxOutputTokens);
            System.out.println("GEMINI REQUEST BODY PREVIEW = " + abbreviate(requestBody, 4000));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            System.out.println("GEMINI PATH = " + pathName);
            System.out.println("GEMINI HTTP STATUS = " + response.statusCode());
            System.out.println("GEMINI RAW BODY PREVIEW = " + abbreviate(response.body(), 12000));

            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini API Error HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke " + pathName + ". Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts visible text from Gemini's candidates[].content.parts[].text shape.
     *
     * Empty Gemini responses often contain useful block metadata outside the text
     * parts. This parser includes those details in thrown errors so Railway logs
     * show whether the cause was safety, finish reason, malformed output, or a
     * provider-side empty candidate.
     */
    private String extractGeminiText(String responseBody, String targetModel) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("Gemini returned an empty HTTP body for model " + targetModel + ".");
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode partsNode = root.path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();

            if (partsNode.isArray()) {
                for (JsonNode part : partsNode) {
                    String partText = part.path("text").asText("");
                    if (!partText.isBlank()) {
                        text.append(partText);
                    }
                }
            }

            if (text.length() > 0) {
                return text.toString();
            }

            String blockReason = root.path("promptFeedback").path("blockReason").asText("");
            String finishReason = root.path("candidates").path(0).path("finishReason").asText("");

            throw new RuntimeException("Gemini returned no visible text. model="
                    + targetModel
                    + ", blockReason="
                    + blockReason
                    + ", finishReason="
                    + finishReason
                    + ", body="
                    + abbreviate(responseBody, 3000));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to parse Gemini response. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Adds JSON-only instructions to the system prompt when missing.
     *
     * Gemini's JSON response MIME type is helpful, but a text instruction still
     * improves compliance, especially with fallback models.
     */
    private String ensureJsonSystemPrompt(String systemPrompt) {
        String safePrompt = safeText(systemPrompt);
        if (safePrompt.toLowerCase(Locale.ROOT).contains("json")) {
            return safePrompt;
        }

        return safePrompt
                + "\n\nReturn only valid JSON. Do not use markdown. Do not wrap JSON in code fences.";
    }

    /**
     * Adds JSON-only instructions to the user prompt when missing.
     *
     * Repeating the JSON requirement in the active user turn prevents older prompt
     * fragments/history from pulling the model back into prose mode.
     */
    private String ensureJsonUserPrompt(String userPrompt) {
        String safePrompt = safeText(userPrompt);
        if (safePrompt.toLowerCase(Locale.ROOT).contains("json")) {
            return safePrompt;
        }

        return safePrompt + "\n\nReturn only valid JSON. Start with { and end with }.";
    }

    /**
     * Removes markdown code fences around structured JSON.
     *
     * Even with responseMimeType=application/json, providers may occasionally wrap
     * output in ```json fences. The parser above the client expects raw JSON text.
     */
    private String stripMarkdownCodeFence(String text) {
        if (text == null) {
            return "";
        }

        String result = text.trim();

        if (result.startsWith("```json")) {
            result = result.substring(7).trim();
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3).trim();
            }
            return result;
        }

        if (result.startsWith("```")) {
            result = result.substring(3).trim();
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3).trim();
            }
        }

        return result.trim();
    }

    /**
     * Converts MiniAgent/OpenAI style roles into Gemini roles.
     */
    private String normalizeGeminiRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }

        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if ("assistant".equals(normalized) || "model".equals(normalized)) {
            return "model";
        }

        return "user";
    }

    /**
     * Ensures an API key exists before building the provider URL.
     */
    private String requireGeminiApiKey() {
        String apiKey = config.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing from AgentConfig.");
        }
        return apiKey;
    }

    /**
     * Resolves the active model from caller input or AgentConfig default.
     */
    private String resolveModel(String model) {
        String targetModel = model != null && !model.isBlank()
                ? model.trim()
                : config.getDefaultGeminiModel();

        if (targetModel == null || targetModel.isBlank()) {
            throw new IllegalStateException(
                    "Gemini model is missing. Provide a model or configure defaultGeminiModel.");
        }

        return targetModel.trim();
    }

    /**
     * Returns caller timeout or a safe fallback.
     */
    private Duration effectiveTimeout(Duration requested, Duration fallback) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return fallback == null ? FALLBACK_TIMEOUT : fallback;
        }
        return requested;
    }

    /**
     * Clamps integer stage budgets into provider-safe bounds.
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Keeps Gemini temperature in a sane range.
     */
    private double clampTemperature(Double temperature) {
        if (temperature == null || temperature.isNaN() || temperature.isInfinite()) {
            return 0.2d;
        }
        return Math.max(0.0d, Math.min(2.0d, temperature));
    }

    /**
     * Converts nullable text to a non-null string.
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Shortens very large request/response logs for Railway readability.
     */
    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...[truncated " + (value.length() - maxChars) + " chars]";
    }
}