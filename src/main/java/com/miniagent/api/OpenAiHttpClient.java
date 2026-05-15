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
 * This class intentionally keeps TWO SEPARATE OpenAI paths:
 *
 * 1. Responses API path:
 * Endpoint: https://api.openai.com/v1/responses
 * Request: model + instructions + input + reasoning + max_output_tokens +
 * text.format
 * Parser: output_text fallback, then output[].content[].text
 *
 * 2. Chat Completions path:
 * Endpoint: https://api.openai.com/v1/chat/completions
 * Request: model + messages + reasoning_effort + max_completion_tokens +
 * response_format
 * Parser: choices[0].message.content
 *
 * The important rule:
 *
 * NEVER send Responses-shaped JSON and parse it like Chat Completions.
 * NEVER send Chat-shaped JSON and parse it like Responses.
 *
 * The earlier failure pattern came from exactly that kind of half-migration.
 */
public class OpenAiHttpClient {

    private static final String RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

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

    public AgentConfig getConfig() {
        return config;
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt, Double temperature) {
        String activeModel = resolveModel(model);

        if (shouldUseResponsesApi(activeModel)) {
            return executeResponsesTextCall(activeModel, systemPrompt, userPrompt);
        }

        return executeChatTextCall(activeModel, systemPrompt, userPrompt, temperature);
    }

    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    public String executeStructuredCall(
            String model,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history) {
        String activeModel = resolveModel(model);

        if (shouldUseResponsesApi(activeModel)) {
            return executeResponsesStructuredCall(activeModel, systemPrompt, userPrompt, history);
        }

        return executeChatStructuredCall(activeModel, systemPrompt, userPrompt, temperature, history);
    }

    /**
     * Responses API plain text path.
     *
     * This is the preferred path for higher reasoning models such as:
     *
     * gpt-5
     * gpt-5-pro
     * gpt-5.4
     * o1 / o3 / o4 families
     * deep-research style models
     *
     * Critical fields:
     *
     * instructions = system/developer instruction string
     * input = user/assistant conversation input
     * reasoning = Responses-style reasoning object
     * max_output_tokens = Responses-style token cap
     *
     * Do not put "messages" here.
     * Do not parse choices[0].message.content from this response.
     */
    private String executeResponsesTextCall(String activeModel, String systemPrompt, String userPrompt) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);

            String safeInstructions = systemPrompt == null ? "" : systemPrompt.trim();
            if (!safeInstructions.isBlank()) {
                request.put("instructions", safeInstructions);
            }

            request.put("input", buildResponsesInput(userPrompt, null));

            applyResponsesReasoningIfSupported(request, activeModel);
            applyResponsesTokenLimit(request, activeModel);

            String requestBody = mapper.writeValueAsString(request);

            HttpResponse<String> response = sendJsonRequest(
                    RESPONSES_ENDPOINT,
                    apiKey,
                    requestBody,
                    resolveRequestTimeout(activeModel));

            logOpenAiResponse("RESPONSES TEXT", response);

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

    /**
     * Responses API structured JSON path.
     *
     * This is the Responses equivalent of Chat Completions JSON mode.
     *
     * Critical Responses field:
     *
     * "text": {
     * "format": {
     * "type": "json_object"
     * }
     * }
     *
     * This is NOT the same as Chat Completions:
     *
     * "response_format": { "type": "json_object" }
     *
     * That field belongs to Chat Completions. The Responses API uses text.format.
     */
    private String executeResponsesStructuredCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> history) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);

            request.put("instructions", ensureJsonInstruction(systemPrompt));
            request.put("input", buildResponsesInput(userPrompt, history));

            request.put("text", Map.of(
                    "format", Map.of("type", "json_object")));

            applyResponsesReasoningIfSupported(request, activeModel);
            applyResponsesTokenLimit(request, activeModel);

            String requestBody = mapper.writeValueAsString(request);

            HttpResponse<String> response = sendJsonRequest(
                    RESPONSES_ENDPOINT,
                    apiKey,
                    requestBody,
                    resolveRequestTimeout(activeModel));

            logOpenAiResponse("RESPONSES STRUCTURED", response);

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

    /**
     * Chat Completions plain text path.
     *
     * This is used for non-reasoning / legacy chat models.
     *
     * Critical fields:
     *
     * messages
     * max_completion_tokens
     * reasoning_effort only if the model supports it
     *
     * Do not put Responses fields like:
     *
     * input
     * instructions
     * max_output_tokens
     * text.format
     *
     * in this path.
     */
    private String executeChatTextCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            Double temperature) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);
            request.put("messages", buildChatMessagesForTextCall(systemPrompt, userPrompt));

            applyChatReasoningEffortIfSupported(request, activeModel);
            applyTemperatureIfSupported(request, activeModel, temperature);
            applyChatTokenLimit(request, activeModel);

            String requestBody = mapper.writeValueAsString(request);

            HttpResponse<String> response = sendJsonRequest(
                    CHAT_COMPLETIONS_ENDPOINT,
                    apiKey,
                    requestBody,
                    resolveRequestTimeout(activeModel));

            logOpenAiResponse("CHAT TEXT", response);

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
     * Chat Completions structured JSON path.
     *
     * Critical Chat field:
     *
     * "response_format": {
     * "type": "json_object"
     * }
     *
     * Do not use Responses field:
     *
     * "text": {
     * "format": ...
     * }
     *
     * here.
     */
    private String executeChatStructuredCall(
            String activeModel,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history) {
        String apiKey = requireOpenAiApiKey();

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", activeModel);
            request.put("messages", buildChatMessagesForStructuredCall(systemPrompt, userPrompt, history));
            request.put("response_format", Map.of("type", "json_object"));

            applyChatReasoningEffortIfSupported(request, activeModel);
            applyTemperatureIfSupported(request, activeModel, temperature);
            applyChatTokenLimit(request, activeModel);

            String requestBody = mapper.writeValueAsString(request);

            HttpResponse<String> response = sendJsonRequest(
                    CHAT_COMPLETIONS_ENDPOINT,
                    apiKey,
                    requestBody,
                    resolveRequestTimeout(activeModel));

            logOpenAiResponse("CHAT STRUCTURED", response);

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

    /**
     * Decides whether the model should use the Responses API.
     *
     * Policy:
     *
     * 1. Full GPT-5-class models use Responses.
     * 2. GPT-5 mini/nano stay on Chat Completions unless you later decide
     * otherwise.
     * They are cheaper/fast models and may be used in simple routing.
     * 3. o-series reasoning models use Responses.
     * 4. deep-research style models use Responses.
     *
     * This avoids the old broken heuristic:
     *
     * activeModel.endsWith("-pro") || activeModel.contains("deep-research")
     *
     * That was too narrow and missed normal GPT-5 reasoning models.
     */
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

    /**
     * Reasoning-capable model detector.
     *
     * This is broader than shouldUseResponsesApi because even gpt-5-mini/nano
     * may be reasoning-capable, but this client currently routes mini/nano through
     * Chat Completions by policy.
     */
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

    private boolean isMiniOrNanoModel(String normalizedModel) {
        return normalizedModel.contains("mini")
                || normalizedModel.contains("nano")
                || normalizedModel.contains("lite");
    }

    /**
     * Responses API reasoning parameter.
     *
     * Responses shape:
     *
     * "reasoning": {
     * "effort": "medium"
     * }
     *
     * Do not send:
     *
     * "reasoning_effort": "medium"
     *
     * in Responses. That is Chat Completions shape.
     *
     * GPT-5-pro-like models may require high. For normal GPT-5 reasoning,
     * medium is safer because your earlier logs showed invisible reasoning tokens
     * consuming the whole output budget and producing empty visible content.
     */
    private void applyResponsesReasoningIfSupported(Map<String, Object> request, String model) {
        if (!isReasoningCapableModel(model)) {
            return;
        }

        String m = normalizeModelName(model);
        String effort = m.contains("pro") ? "medium" : "low";

        request.put("reasoning", Map.of("effort", effort));
    }

    /**
     * Chat Completions reasoning parameter.
     *
     * Chat shape:
     *
     * "reasoning_effort": "medium"
     *
     * Do not send:
     *
     * "reasoning": { "effort": "medium" }
     *
     * in Chat Completions.
     */
    private void applyChatReasoningEffortIfSupported(Map<String, Object> request, String model) {
        if (!isReasoningCapableModel(model)) {
            return;
        }

        String m = normalizeModelName(model);
        String effort = m.contains("pro") ? "medium" : "low";

        request.put("reasoning_effort", effort);
    }

    /**
     * Responses token limit.
     *
     * Responses API field:
     *
     * max_output_tokens
     *
     * This includes visible output + reasoning tokens.
     */
    private void applyResponsesTokenLimit(Map<String, Object> request, String model) {
        if (isReasoningCapableModel(model)) {
            request.put("max_output_tokens", 30000);
        } else {
            request.put("max_output_tokens", 16384);
        }
    }

    /**
     * Chat Completions token limit.
     *
     * Chat Completions field:
     *
     * max_completion_tokens
     *
     * Do not use max_output_tokens here.
     */
    private void applyChatTokenLimit(Map<String, Object> request, String model) {
        if (isReasoningCapableModel(model)) {
            request.put("max_completion_tokens", 30000);
        } else {
            request.put("max_completion_tokens", 16384);
        }
    }

    /**
     * Temperature handling.
     *
     * Reasoning/high-control models often reject custom temperature or behave best
     * with defaults. For those models we omit temperature entirely.
     *
     * Important:
     *
     * Do not send "temperature": null.
     * Do not unbox nullable Double.
     * Do not pass temperature to reasoning models.
     */
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

    private boolean supportsTemperature(String model) {
        return !isReasoningCapableModel(model);
    }

    /**
     * Responses input builder.
     *
     * Responses input items can use role/content.
     *
     * We keep system instructions out of this list and put them into the top-level
     * "instructions" field. That is cleaner and prevents old history from becoming
     * accidental system-level instruction.
     */
    private List<Map<String, String>> buildResponsesInput(
            String userPrompt,
            List<Map<String, String>> history) {
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

        input.add(Map.of(
                "role", "user",
                "content", userPrompt == null ? "" : userPrompt));

        return input;
    }

    private List<Map<String, String>> buildChatMessagesForTextCall(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();

        String safeSystemPrompt = systemPrompt == null ? "" : systemPrompt;
        if (!safeSystemPrompt.isBlank()) {
            messages.add(Map.of(
                    "role", "system",
                    "content", safeSystemPrompt));
        }

        messages.add(Map.of(
                "role", "user",
                "content", userPrompt == null ? "" : userPrompt));

        return messages;
    }

    private List<Map<String, String>> buildChatMessagesForStructuredCall(
            String systemPrompt,
            String userPrompt,
            List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", ensureJsonInstruction(systemPrompt)));

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

        messages.add(Map.of(
                "role", "user",
                "content", userPrompt == null ? "" : userPrompt));

        return messages;
    }

    private String normalizeConversationRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return "user";
        }

        String role = rawRole.trim().toLowerCase(Locale.ROOT);

        if ("assistant".equals(role)) {
            return "assistant";
        }

        /*
         * We deliberately do not allow old history to inject another system role.
         * There should be only one authoritative system/developer instruction:
         * the current systemPrompt/instructions passed to this call.
         */
        if ("system".equals(role) || "developer".equals(role)) {
            return "assistant";
        }

        return "user";
    }

    /**
     * Required because JSON mode rejects requests unless the prompt contains the
     * word "json" somewhere.
     */
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

    private void logOpenAiResponse(String pathName, HttpResponse<String> response) {
        System.out.println("OPENAI PATH = " + pathName);
        System.out.println("OPENAI HTTP STATUS = " + response.statusCode());
        System.out.println("OPENAI RAW BODY = " + response.body());
    }

    /**
     * Correct parser for Responses API.
     *
     * Valid visible text can appear in:
     *
     * root.output_text
     *
     * or inside:
     *
     * output[].content[].text
     *
     * depending on exact raw response shape.
     *
     * This parser deliberately ignores reasoning items as final user output.
     */
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

    private RuntimeException buildEmptyResponsesOutputException(
            JsonNode root,
            String activeModel,
            String status) {
        String incompleteReason = root.path("incomplete_details").path("reason").asText("");
        int outputTokens = root.path("usage").path("output_tokens").asInt(-1);
        int reasoningTokens = root.path("usage").path("output_tokens_details").path("reasoning_tokens").asInt(-1);

        StringBuilder error = new StringBuilder();
        error.append("OpenAI Responses API returned no visible output.");
        error.append(" model=").append(activeModel).append(".");

        if (!status.isBlank()) {
            error.append(" status=").append(status).append(".");
        }

        if (!incompleteReason.isBlank()) {
            error.append(" incomplete_reason=").append(incompleteReason).append(".");
        }

        if (outputTokens >= 0) {
            error.append(" output_tokens=").append(outputTokens).append(".");
        }

        if (reasoningTokens >= 0) {
            error.append(" reasoning_tokens=").append(reasoningTokens).append(".");
        }

        if ("max_output_tokens".equalsIgnoreCase(incompleteReason)) {
            error.append(
                    " The model likely ran out of output budget during reasoning or before visible text was produced.");
        }

        return new RuntimeException(error.toString());
    }

    /**
     * Correct parser for Chat Completions.
     *
     * This parser must only be used for /v1/chat/completions.
     */
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
        error.append("OpenAI Chat Completions returned empty message content.");
        error.append(" model=").append(activeModel).append(".");

        if (!finishReason.isBlank()) {
            error.append(" finish_reason=").append(finishReason).append(".");
        }

        if (completionTokens >= 0) {
            error.append(" completion_tokens=").append(completionTokens).append(".");
        }

        if (reasoningTokens >= 0) {
            error.append(" reasoning_tokens=").append(reasoningTokens).append(".");
        }

        if ("length".equalsIgnoreCase(finishReason)) {
            error.append(" The model likely consumed the completion budget before producing visible text.");
        }

        throw new RuntimeException(error.toString());
    }

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
            return result;
        }

        return result;
    }

    private String requireOpenAiApiKey() {
        String apiKey = config.getOpenaiApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }

        return apiKey;
    }

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

    private String normalizeModelName(String model) {
        return model == null
                ? ""
                : model.trim().toLowerCase(Locale.ROOT);
    }

    private Duration resolveRequestTimeout(String activeModel) {
        if (shouldUseResponsesApi(activeModel)) {
            return Duration.ofMinutes(3);
        }

        if (isReasoningCapableModel(activeModel)) {
            return Duration.ofMinutes(3);
        }

        return Duration.ofSeconds(30);
    }

    /**
     * Executes raw multipart upload to OpenAI Whisper transcription.
     *
     * Unrelated to chat/reasoning generation.
     */
    public static String executeWhisperTranscription(byte[] audioBytes, String apiKey, String filename)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Cannot execute Whisper audio without OpenAI authentication.");
        }

        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("Cannot execute Whisper audio with an empty audio payload.");
        }

        String safeFilename = filename != null && !filename.isBlank()
                ? filename
                : "audio.webm";

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
     * Unrelated to chat/reasoning generation.
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