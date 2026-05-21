package com.miniagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.AgentConfig;

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
 * OpenAI transport client for MiniAgent.
 *
 * This class is intentionally boring and strict. It is not an agent. It should
 * not
 * classify, repair, or synthesize. Its job is to translate MiniAgent's
 * stage-level
 * intent into the correct OpenAI HTTP request shape and then parse the matching
 * response shape.
 *
 * There are two OpenAI endpoint families here:
 *
 * 1. Responses API
 * Endpoint: /v1/responses
 * Request shape: model + instructions + input + reasoning + max_output_tokens +
 * text.format
 * Response parser: output_text, then output[].content[].text
 *
 * 2. Chat Completions API
 * Endpoint: /v1/chat/completions
 * Request shape: model + messages + reasoning_effort + max_completion_tokens +
 * response_format
 * Response parser: choices[0].message.content
 *
 * Never mix these shapes. A large part of the earlier failure mode came from
 * half-migrated code where a Responses-shaped request was parsed like Chat, or
 * a
 * Chat-style JSON mode parameter was sent to Responses.
 *
 * Stage-aware design:
 *
 * MiniAgentWorker should call the overloads that accept maxOutputTokens and
 * Duration. That lets AgentRunPlan control first-draft and repair budgets. The
 * old overloads remain only for compatibility and use safe bounded defaults.
 */
public class OpenAiHttpClient {

    private static final String RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final int DEFAULT_TEXT_MAX_OUTPUT_TOKENS = 6500;
    private static final int DEFAULT_STRUCTURED_MAX_OUTPUT_TOKENS = 1200;

    private static final Duration DEFAULT_TEXT_TIMEOUT = Duration.ofSeconds(115);
    private static final Duration DEFAULT_STRUCTURED_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_FAST_TIMEOUT = Duration.ofSeconds(30);

    private static final int MIN_OUTPUT_TOKENS = 256;
    private static final int MAX_ONE_SHOT_OUTPUT_TOKENS = 7000;
    private static final int MAX_STRUCTURED_OUTPUT_TOKENS = 3000;

    private static final String RESPONSES_TEXT_REASONING_EFFORT = "minimal";
    private static final String RESPONSES_TEXT_REASONING_FALLBACK_EFFORT = "low";
    private static final String STRUCTURED_REASONING_EFFORT = "low";
    private static final String CHAT_REASONING_EFFORT = "low";

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Constructs the OpenAI transport client.
     *
     * The HttpClient is intentionally shared across calls. Per-request timeouts are
     * set on each HttpRequest because worker, critic, repair, synthesis, and audio
     * operations all have different latency budgets.
     */
    public OpenAiHttpClient(AgentConfig config, ObjectMapper mapper) {
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
     * Returns the config object so wiring/tests can inspect API key availability.
     */
    public AgentConfig getConfig() {
        return config;
    }

    /** Backward-compatible text call without temperature. */
    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Backward-compatible text call used by older MiniAgent code.
     *
     * New worker code should call the stage-aware overload below. This
     * compatibility
     * path deliberately uses the same safe bounded defaults as the worker
     * first-draft
     * stage instead of the previous unbounded 30000-token / 3-minute behavior.
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
     * Stage-aware text call.
     *
     * MiniAgentWorker should use this for freeform generation and repair. The
     * caller
     * owns the stage budget. This method only clamps it into provider-safe bounds
     * and
     * chooses Responses vs Chat based on the model family.
     */
    public String executeTextCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            int maxOutputTokens,
            Duration timeout) {
        String activeModel = resolveModel(model);
        int safeMaxOutputTokens = clamp(maxOutputTokens, MIN_OUTPUT_TOKENS, MAX_ONE_SHOT_OUTPUT_TOKENS);
        Duration safeTimeout = effectiveTimeout(timeout, DEFAULT_TEXT_TIMEOUT);

        if (shouldUseResponsesApi(activeModel)) {
            return executeResponsesTextCall(activeModel, systemPrompt, userPrompt, safeMaxOutputTokens, safeTimeout);
        }

        return executeChatTextCall(activeModel, systemPrompt, userPrompt, temperature, safeMaxOutputTokens,
                safeTimeout);
    }

    /** Backward-compatible structured call without temperature/history. */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Backward-compatible structured call.
     *
     * Classifier, critic, and small metadata stages can use this safely. The
     * default
     * budget is deliberately small because structured JSON should never be the path
     * used for large code generation.
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
     * Stage-aware structured call.
     *
     * This is meant for JSON-sized outputs: classifier result, critic result, and
     * small final metadata. Do not use this for full app/code generation.
     */
    public String executeStructuredCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxOutputTokens,
            Duration timeout) {
        String activeModel = resolveModel(model);
        int safeMaxOutputTokens = clamp(maxOutputTokens, MIN_OUTPUT_TOKENS, MAX_STRUCTURED_OUTPUT_TOKENS);
        Duration safeTimeout = effectiveTimeout(timeout, DEFAULT_STRUCTURED_TIMEOUT);

        if (shouldUseResponsesApi(activeModel)) {
            return executeResponsesStructuredCall(activeModel, systemPrompt, userPrompt, history, safeMaxOutputTokens,
                    safeTimeout);
        }

        return executeChatStructuredCall(activeModel, systemPrompt, userPrompt, temperature, history,
                safeMaxOutputTokens, safeTimeout);
    }

    /**
     * Executes a plain text Responses API call.
     *
     * This is the path used by GPT-5-class reasoning models for large freeform
     * outputs. It uses text.format={type:text}, not JSON mode. Reasoning is kept at
     * minimal first; if the provider rejects that effort value for the specific
     * model, we retry once with low.
     */
    private String executeResponsesTextCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            Duration timeout) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = buildResponsesTextRequest(
                    activeModel,
                    systemPrompt,
                    userPrompt,
                    maxOutputTokens,
                    RESPONSES_TEXT_REASONING_EFFORT);

            HttpResponse<String> response = sendOpenAiRequest(
                    "RESPONSES TEXT",
                    RESPONSES_ENDPOINT,
                    apiKey,
                    request,
                    timeout,
                    activeModel,
                    maxOutputTokens,
                    RESPONSES_TEXT_REASONING_EFFORT);

            if (response.statusCode() >= 400
                    && shouldRetryResponsesReasoningWithLow(response.body(), RESPONSES_TEXT_REASONING_EFFORT)) {
                Map<String, Object> fallbackRequest = buildResponsesTextRequest(
                        activeModel,
                        systemPrompt,
                        userPrompt,
                        maxOutputTokens,
                        RESPONSES_TEXT_REASONING_FALLBACK_EFFORT);

                response = sendOpenAiRequest(
                        "RESPONSES TEXT RETRY_LOW_REASONING",
                        RESPONSES_ENDPOINT,
                        apiKey,
                        fallbackRequest,
                        timeout,
                        activeModel,
                        maxOutputTokens,
                        RESPONSES_TEXT_REASONING_FALLBACK_EFFORT);
            }

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI Responses API Error HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            return extractResponsesText(response.body(), activeModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI Responses text call. Reason: "
                    + e.getMessage(), e);
        }
    }

    /** Builds the request body for a plain text Responses API generation. */
    private Map<String, Object> buildResponsesTextRequest(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            String reasoningEffort) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", activeModel);

        String safeInstructions = systemPrompt == null ? "" : systemPrompt.trim();
        if (!safeInstructions.isBlank()) {
            request.put("instructions", safeInstructions);
        }

        request.put("input", buildResponsesInput(userPrompt, null));
        request.put("text", Map.of("format", Map.of("type", "text")));

        applyResponsesReasoningIfSupported(request, activeModel, reasoningEffort);
        request.put("max_output_tokens", maxOutputTokens);

        return request;
    }

    /**
     * Executes a structured JSON Responses API call.
     *
     * This uses the Responses API's text.format shape. It is intentionally bounded
     * because JSON stages are supposed to be small decision/evaluation objects.
     */
    private String executeResponsesStructuredCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> history,
            int maxOutputTokens,
            Duration timeout) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);
            request.put("instructions", ensureJsonInstruction(systemPrompt));
            request.put("input", buildResponsesInput(userPrompt, history));
            request.put("text", Map.of("format", Map.of("type", "json_object")));

            applyResponsesReasoningIfSupported(request, activeModel, STRUCTURED_REASONING_EFFORT);
            request.put("max_output_tokens", maxOutputTokens);

            HttpResponse<String> response = sendOpenAiRequest(
                    "RESPONSES STRUCTURED",
                    RESPONSES_ENDPOINT,
                    apiKey,
                    request,
                    timeout,
                    activeModel,
                    maxOutputTokens,
                    STRUCTURED_REASONING_EFFORT);

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI Responses API Error HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            return stripMarkdownCodeFence(extractResponsesText(response.body(), activeModel)).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI Responses structured call. Reason: "
                    + e.getMessage(), e);
        }
    }

    /** Executes a plain text Chat Completions call for non-Responses models. */
    private String executeChatTextCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            int maxCompletionTokens,
            Duration timeout) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);
            request.put("messages", buildChatMessagesForTextCall(systemPrompt, userPrompt));

            applyChatReasoningEffortIfSupported(request, activeModel);
            applyTemperatureIfSupported(request, activeModel, temperature);
            request.put("max_completion_tokens", maxCompletionTokens);

            HttpResponse<String> response = sendOpenAiRequest(
                    "CHAT TEXT",
                    CHAT_COMPLETIONS_ENDPOINT,
                    apiKey,
                    request,
                    timeout,
                    activeModel,
                    maxCompletionTokens,
                    isReasoningCapableModel(activeModel) ? CHAT_REASONING_EFFORT : "none");

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI Chat Completions API Error HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            return extractChatCompletionText(response.body(), activeModel);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI Chat text call. Reason: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Executes a structured JSON Chat Completions call for non-Responses models.
     */
    private String executeChatStructuredCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxCompletionTokens,
            Duration timeout) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);
            request.put("messages", buildChatMessagesForStructuredCall(systemPrompt, userPrompt, history));
            request.put("response_format", Map.of("type", "json_object"));

            applyChatReasoningEffortIfSupported(request, activeModel);
            applyTemperatureIfSupported(request, activeModel, temperature);
            request.put("max_completion_tokens", maxCompletionTokens);

            HttpResponse<String> response = sendOpenAiRequest(
                    "CHAT STRUCTURED",
                    CHAT_COMPLETIONS_ENDPOINT,
                    apiKey,
                    request,
                    timeout,
                    activeModel,
                    maxCompletionTokens,
                    isReasoningCapableModel(activeModel) ? CHAT_REASONING_EFFORT : "none");

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI Chat Completions API Error HTTP "
                        + response.statusCode()
                        + ": "
                        + response.body());
            }

            return stripMarkdownCodeFence(extractChatCompletionText(response.body(), activeModel)).trim();
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI Chat structured call. Reason: "
                    + e.getMessage(), e);
        }
    }

    /** Decides whether this model family should use the Responses endpoint. */
    private boolean shouldUseResponsesApi(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String m = normalizeModelName(model);

        if (m.contains("deep-research")) {
            return true;
        }

        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) {
            return true;
        }

        if (m.startsWith("gpt-5")) {
            return !isMiniOrNanoModel(m);
        }

        return false;
    }

    /** Identifies models that can accept OpenAI reasoning controls. */
    private boolean isReasoningCapableModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String m = normalizeModelName(model);
        return m.startsWith("gpt-5")
                || m.startsWith("o1")
                || m.startsWith("o3")
                || m.startsWith("o4")
                || m.contains("deep-research");
    }

    /**
     * Treats mini/nano/lite names as cheap variants rather than full
     * reasoning-first models.
     */
    private boolean isMiniOrNanoModel(String normalizedModel) {
        return normalizedModel.contains("mini")
                || normalizedModel.contains("nano")
                || normalizedModel.contains("lite");
    }

    /** Applies Responses API reasoning shape: reasoning={effort:...}. */
    private void applyResponsesReasoningIfSupported(
            Map<String, Object> request,
            String model,
            String effort) {
        if (!isReasoningCapableModel(model)) {
            return;
        }

        String safeEffort = effort == null || effort.isBlank()
                ? RESPONSES_TEXT_REASONING_FALLBACK_EFFORT
                : effort.trim().toLowerCase(Locale.ROOT);

        request.put("reasoning", Map.of("effort", safeEffort));
    }

    /** Applies Chat Completions reasoning shape: reasoning_effort=... */
    private void applyChatReasoningEffortIfSupported(Map<String, Object> request, String model) {
        if (!isReasoningCapableModel(model)) {
            return;
        }

        request.put("reasoning_effort", CHAT_REASONING_EFFORT);
    }

    /** Applies temperature only to models that support it. */
    private void applyTemperatureIfSupported(Map<String, Object> request, String model, Double temperature) {
        if (temperature == null) {
            return;
        }

        if (!supportsTemperature(model)) {
            return;
        }

        double safeTemperature = temperature.doubleValue();
        if (Double.isNaN(safeTemperature) || Double.isInfinite(safeTemperature)) {
            return;
        }

        if (safeTemperature < 0.0d) {
            safeTemperature = 0.0d;
        } else if (safeTemperature > 2.0d) {
            safeTemperature = 2.0d;
        }

        request.put("temperature", safeTemperature);
    }

    /** Reasoning models should usually use provider defaults for temperature. */
    private boolean supportsTemperature(String model) {
        return !isReasoningCapableModel(model);
    }

    /**
     * Builds Responses API input items from optional history and the current user
     * prompt.
     */
    private List<Map<String, String>> buildResponsesInput(String userPrompt, List<Map<String, String>> history) {
        List<Map<String, String>> input = new ArrayList<>();

        if (history != null) {
            for (Map<String, String> item : history) {
                if (item == null) {
                    continue;
                }

                String content = item.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }

                input.add(Map.of(
                        "role", normalizeConversationRole(item.get("role")),
                        "content", content));
            }
        }

        input.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
        return input;
    }

    /** Builds Chat Completions messages for plain text generation. */
    private List<Map<String, String>> buildChatMessagesForTextCall(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();

        String safeSystemPrompt = systemPrompt == null ? "" : systemPrompt;
        if (!safeSystemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", safeSystemPrompt));
        }

        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
        return messages;
    }

    /** Builds Chat Completions messages for JSON-mode structured calls. */
    private List<Map<String, String>> buildChatMessagesForStructuredCall(
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", ensureJsonInstruction(systemPrompt)));

        if (history != null) {
            for (Map<String, String> item : history) {
                if (item == null) {
                    continue;
                }

                String content = item.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }

                messages.add(Map.of(
                        "role", normalizeConversationRole(item.get("role")),
                        "content", content));
            }
        }

        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
        return messages;
    }

    /**
     * Normalizes history roles so older history cannot inject new system
     * instructions.
     */
    private String normalizeConversationRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return "user";
        }

        String role = rawRole.trim().toLowerCase(Locale.ROOT);
        if ("assistant".equals(role)) {
            return "assistant";
        }

        if ("system".equals(role) || "developer".equals(role)) {
            return "assistant";
        }

        return "user";
    }

    /** Ensures JSON-mode prompts contain the word JSON and the expected schema. */
    private String ensureJsonInstruction(String systemPrompt) {
        String base = systemPrompt == null ? "" : systemPrompt.trim();

        String jsonInstruction = """
                Return only valid JSON.
                Do not use markdown.
                Do not wrap the JSON in code fences.
                The JSON object must contain:
                - "thought_process": a short private-work summary string
                - "summary": the final user-facing answer string
                """;

        if (base.toLowerCase(Locale.ROOT).contains("json")) {
            return base;
        }

        if (base.isBlank()) {
            return jsonInstruction;
        }

        return base + "\n\n" + jsonInstruction;
    }

    /** Sends a logged OpenAI request and returns the raw HTTP response. */
    private HttpResponse<String> sendOpenAiRequest(
            String pathName,
            String endpoint,
            String apiKey,
            Map<String, Object> request,
            Duration timeout,
            String activeModel,
            int maxOutputTokens,
            String reasoningEffort) throws Exception {
        String requestBody = mapper.writeValueAsString(request);

        System.out.println("OPENAI REQUEST PATH = " + pathName);
        System.out.println("OPENAI REQUEST MODEL = " + activeModel);
        System.out.println("OPENAI REQUEST TIMEOUT_SECONDS = " + timeout.toSeconds());
        System.out.println("OPENAI REQUEST MAX_OUTPUT_TOKENS = " + maxOutputTokens);
        System.out.println("OPENAI REQUEST REASONING_EFFORT = " + reasoningEffort);
        System.out.println("OPENAI REQUEST BODY PREVIEW = " + abbreviate(requestBody, 4000));

        HttpResponse<String> response = sendJsonRequest(endpoint, apiKey, requestBody, timeout);
        logOpenAiResponse(pathName, response);
        return response;
    }

    /** Performs the actual blocking HTTP POST with a per-request timeout. */
    private HttpResponse<String> sendJsonRequest(
            String endpoint,
            String apiKey,
            String requestBody,
            Duration timeout) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    /** Logs status/body preview and usage fields without throwing. */
    private void logOpenAiResponse(String pathName, HttpResponse<String> response) {
        System.out.println("OPENAI PATH = " + pathName);
        System.out.println("OPENAI HTTP STATUS = " + response.statusCode());
        System.out.println("OPENAI RAW BODY PREVIEW = " + abbreviate(response.body(), 12000));
        logOpenAiUsageIfPresent(response.body());
    }

    /** Parses usage/incomplete fields for Railway debugging. */
    private void logOpenAiUsageIfPresent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return;
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                System.out.println("OPENAI USAGE = " + usage);
            }

            String status = root.path("status").asText("");
            if (!status.isBlank()) {
                System.out.println("OPENAI RESPONSE STATUS = " + status);
            }

            String incompleteReason = root.path("incomplete_details").path("reason").asText("");
            if (!incompleteReason.isBlank()) {
                System.out.println("OPENAI INCOMPLETE REASON = " + incompleteReason);
            }
        } catch (Exception ignored) {
            /* Logging must never break generation. */
        }
    }

    /** Extracts visible text from a Responses API response. */
    private String extractResponsesText(String responseBody, String activeModel) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String status = root.path("status").asText("");

        JsonNode directOutputText = root.get("output_text");
        if (directOutputText != null && !directOutputText.isNull()) {
            String text = directOutputText.asText("");
            if (!text.isBlank()) {
                return text;
            }
        }

        StringBuilder visibleText = new StringBuilder();
        JsonNode output = root.path("output");

        if (output.isArray()) {
            for (JsonNode outputItem : output) {
                String itemType = outputItem.path("type").asText("");

                if ("message".equals(itemType)) {
                    JsonNode content = outputItem.path("content");
                    if (content.isArray()) {
                        for (JsonNode contentItem : content) {
                            String contentType = contentItem.path("type").asText("");
                            if ("output_text".equals(contentType) || "text".equals(contentType)) {
                                String text = contentItem.path("text").asText("");
                                if (!text.isBlank()) {
                                    if (visibleText.length() > 0) {
                                        visibleText.append("\n");
                                    }
                                    visibleText.append(text);
                                }
                            }
                        }
                    }
                }

                if ("output_text".equals(itemType)) {
                    String text = outputItem.path("text").asText("");
                    if (!text.isBlank()) {
                        if (visibleText.length() > 0) {
                            visibleText.append("\n");
                        }
                        visibleText.append(text);
                    }
                }
            }
        }

        String result = visibleText.toString().trim();
        if (!result.isBlank()) {
            return result;
        }

        throw buildEmptyResponsesOutputException(root, activeModel, status);
    }

    /** Builds a detailed empty-output exception for reasoning-token failures. */
    private RuntimeException buildEmptyResponsesOutputException(JsonNode root, String activeModel, String status) {
        String incompleteReason = root.path("incomplete_details").path("reason").asText("");
        int outputTokens = root.path("usage").path("output_tokens").asInt(-1);
        int reasoningTokens = root.path("usage").path("output_tokens_details").path("reasoning_tokens").asInt(-1);

        StringBuilder error = new StringBuilder();
        error.append("OpenAI Responses API returned no visible output. model=").append(activeModel).append('.');

        if (!status.isBlank()) {
            error.append(" status=").append(status).append('.');
        }
        if (!incompleteReason.isBlank()) {
            error.append(" incomplete_reason=").append(incompleteReason).append('.');
        }
        if (outputTokens >= 0) {
            error.append(" output_tokens=").append(outputTokens).append('.');
        }
        if (reasoningTokens >= 0) {
            error.append(" reasoning_tokens=").append(reasoningTokens).append('.');
        }
        if ("max_output_tokens".equalsIgnoreCase(incompleteReason)) {
            error.append(" The model likely used the output budget for reasoning before producing visible text.");
        }

        return new RuntimeException(error.toString());
    }

    /** Extracts visible content from a Chat Completions response. */
    private String extractChatCompletionText(String responseBody, String activeModel) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choice = root.path("choices").path(0);

        if (choice.isMissingNode() || choice.isNull()) {
            throw new RuntimeException("OpenAI Chat response did not contain choices[0]. Body: " + responseBody);
        }

        String finishReason = choice.path("finish_reason").asText("");
        String content = choice.path("message").path("content").asText("");

        if (content != null && !content.isBlank()) {
            return content;
        }

        int completionTokens = root.path("usage").path("completion_tokens").asInt(-1);
        int reasoningTokens = root.path("usage").path("completion_tokens_details").path("reasoning_tokens").asInt(-1);

        StringBuilder error = new StringBuilder();
        error.append("OpenAI Chat Completions returned empty message content. model=").append(activeModel).append('.');

        if (!finishReason.isBlank()) {
            error.append(" finish_reason=").append(finishReason).append('.');
        }
        if (completionTokens >= 0) {
            error.append(" completion_tokens=").append(completionTokens).append('.');
        }
        if (reasoningTokens >= 0) {
            error.append(" reasoning_tokens=").append(reasoningTokens).append('.');
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            error.append(" The model likely consumed the completion budget before producing visible text.");
        }

        throw new RuntimeException(error.toString());
    }

    /** Detects whether a minimal-reasoning request should be retried with low. */
    private boolean shouldRetryResponsesReasoningWithLow(String responseBody, String originalEffort) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }

        if (!RESPONSES_TEXT_REASONING_EFFORT.equalsIgnoreCase(originalEffort)) {
            return false;
        }

        String body = responseBody.toLowerCase(Locale.ROOT);
        return body.contains("reasoning")
                && body.contains("effort")
                && (body.contains("unsupported")
                        || body.contains("invalid")
                        || body.contains("not supported")
                        || body.contains("unknown"));
    }

    /** Removes markdown code fences around JSON/text payloads. */
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

        return result;
    }

    /** Reads the OpenAI API key from AgentConfig and fails clearly if missing. */
    private String requireOpenAiApiKey() {
        String apiKey = config.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }
        return apiKey;
    }

    /** Chooses the call model from method arg or default config. */
    private String resolveModel(String model) {
        String activeModel = model != null && !model.isBlank()
                ? model.trim()
                : config.getDefaultOpenaiModel();

        if (activeModel == null || activeModel.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI model is missing. Provide a model or configure defaultOpenaiModel.");
        }

        return activeModel.trim();
    }

    /** Normalizes model IDs for family checks. */
    private String normalizeModelName(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    /** Chooses requested timeout or a fallback if caller passed null/invalid. */
    private Duration effectiveTimeout(Duration requested, Duration fallback) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return fallback == null ? DEFAULT_FAST_TIMEOUT : fallback;
        }
        return requested;
    }

    /** Local integer clamp helper. */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Abbreviates logs so Railway stdout remains readable. */
    private String abbreviate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n...[truncated " + (value.length() - maxChars) + " chars]";
    }

    /**
     * Executes raw multipart upload to OpenAI Whisper transcription.
     *
     * This audio path is unrelated to reasoning generation. It remains static so
     * older Agent-Nero voice code can use it without constructing MiniAgent.
     */
    public static String executeWhisperTranscription(byte[] audioBytes, String apiKey, String filename)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Cannot execute Whisper audio without OpenAI authentication.");
        }
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("Cannot execute Whisper audio with an empty audio payload.");
        }

        String safeFilename = filename != null && !filename.isBlank() ? filename : "audio.webm";
        String boundary = "----AgentNeroVoiceBoundary" + System.currentTimeMillis();

        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append("--").append(boundary).append("\r\n");
        headerBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(safeFilename)
                .append("\"\r\n");
        headerBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StringBuilder footerBuilder = new StringBuilder();
        footerBuilder.append("\r\n--").append(boundary).append("\r\n");
        footerBuilder.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
        footerBuilder.append("whisper-1\r\n");
        footerBuilder.append("--").append(boundary).append("--\r\n");

        byte[] footerBytes = footerBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] multipartBody = new byte[headerBytes.length + audioBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, multipartBody, 0, headerBytes.length);
        System.arraycopy(audioBytes, 0, multipartBody, headerBytes.length, audioBytes.length);
        System.arraycopy(footerBytes, 0, multipartBody, headerBytes.length + audioBytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("Whisper Engine Rejected Upload: "
                    + response.statusCode()
                    + " "
                    + response.body());
        }

        return new ObjectMapper().readTree(response.body()).path("text").asText();
    }

    /**
     * Executes OpenAI speech synthesis and returns raw MP3 bytes.
     *
     * This path is separate from chat/reasoning. It keeps a short timeout because
     * TTS should be a small UI convenience, not a long agent-stage operation.
     */
    public static byte[] executeSpeechSynthesis(String text, String voice, String apiKey) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Cannot execute Speech Synthesis without OpenAI authentication.");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Cannot execute Speech Synthesis with empty text.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "tts-1");
        payload.put("input", text);
        payload.put("voice", voice != null && !voice.isBlank() ? voice : "alloy");

        String requestBody = new ObjectMapper().writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/speech"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<byte[]> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("TTS Engine Rejected Payload: "
                    + response.statusCode()
                    + " "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        }

        return response.body();
    }
}