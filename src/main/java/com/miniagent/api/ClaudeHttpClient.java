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
 * ClaudeHttpClient is MiniAgent's native transport adapter for Anthropic
 * Claude.
 *
 * This class deliberately mirrors the public method shape of OpenAiHttpClient
 * and
 * GeminiHttpClient so the rest of MiniAgent can treat providers uniformly:
 *
 * - executeTextCall(...)
 * - executeStructuredCall(...)
 * - stage-aware overloads with maxOutputTokens and timeout
 *
 * Provider mapping:
 *
 * OpenAI: max_output_tokens / max_completion_tokens
 * Gemini: generationConfig.maxOutputTokens
 * Claude: max_tokens
 *
 * The MiniAgent orchestration layer should never need to remember those
 * provider
 * field names. It passes a stage budget into this adapter, and this adapter
 * maps
 * the budget into Claude's Messages API shape.
 *
 * Important runtime rule:
 *
 * This client does not hide long retries inside HTTP calls. SafeThoughtExecutor
 * owns fallback across models. Hidden provider-level retry loops are dangerous
 * because they make one Worker stage look like one call while actually spending
 * several minutes before the agent can react.
 */
public class ClaudeHttpClient {

    private static final String CLAUDE_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final int DEFAULT_TEXT_MAX_OUTPUT_TOKENS = 6500;
    private static final int DEFAULT_STRUCTURED_MAX_OUTPUT_TOKENS = 1200;

    private static final int MIN_OUTPUT_TOKENS = 256;
    private static final int MAX_TEXT_OUTPUT_TOKENS = 7000;
    private static final int MAX_STRUCTURED_OUTPUT_TOKENS = 3000;

    private static final Duration DEFAULT_TEXT_TIMEOUT = Duration.ofSeconds(115);
    private static final Duration DEFAULT_STRUCTURED_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(45);

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final AgentConfig config;

    /**
     * Creates a Claude client using a local ObjectMapper.
     *
     * The existing project constructed this class with only AgentConfig, so this
     * constructor is retained for compatibility.
     */
    public ClaudeHttpClient(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig cannot be null.");
        }

        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Exposes the shared config for diagnostics and key checks.
     */
    public AgentConfig getConfig() {
        return config;
    }

    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Backward-compatible structured call.
     *
     * Old code that does not pass a stage budget now gets conservative critic-like
     * defaults instead of the previous 12000-token / 10-minute high-model budget.
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
     * This path is intended for critic/evaluator/synthesis JSON, not for large
     * freeform code generation. The caller decides the budget, and this method
     * maps it to Claude's max_tokens and HTTP timeout.
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

        Map<String, Object> request = buildMessagesRequest(
                targetModel,
                ensureJsonSystemPrompt(systemPrompt),
                ensureJsonUserPrompt(userPrompt),
                temperature,
                history,
                safeMaxTokens);

        HttpResponse<String> response = sendClaudeRequest(
                "CLAUDE STRUCTURED",
                targetModel,
                request,
                safeTimeout,
                safeMaxTokens);

        return stripMarkdownCodeFence(extractClaudeText(response.body(), targetModel)).trim();
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Backward-compatible text call.
     *
     * This preserves the old signature while applying the current stage budget
     * defaults. New code should use the overload with maxOutputTokens and timeout.
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
     * MiniAgentWorker should use this for freeform generation/repair when Claude
     * is selected as a generator or fallback. It returns direct text and does not
     * ask Claude to wrap large code inside a JSON object.
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

        Map<String, Object> request = buildMessagesRequest(
                targetModel,
                systemPrompt,
                userPrompt,
                temperature,
                null,
                safeMaxTokens);

        HttpResponse<String> response = sendClaudeRequest(
                "CLAUDE TEXT",
                targetModel,
                request,
                safeTimeout,
                safeMaxTokens);

        return extractClaudeText(response.body(), targetModel).trim();
    }

    /**
     * Builds the Anthropic Messages API request.
     *
     * Claude expects:
     *
     * - model
     * - max_tokens
     * - optional system
     * - messages[] with alternating user/assistant turns
     *
     * History is normalized here because the provider can reject malformed turn
     * order. The rest of MiniAgent should not need to know that Claude's assistant
     * role is called "assistant" while Gemini calls it "model".
     */
    private Map<String, Object> buildMessagesRequest(
            String targetModel,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxTokens) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", targetModel);
        request.put("max_tokens", maxTokens);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            request.put("system", systemPrompt.trim());
        }

        if (temperature != null) {
            request.put("temperature", clampTemperature(temperature));
        }

        List<Map<String, Object>> messages = buildMessages(history, userPrompt);
        request.put("messages", messages);

        return request;
    }

    /**
     * Converts MiniAgent history into Claude messages and appends the current user
     * prompt.
     *
     * Consecutive same-role turns are merged so that noisy history does not cause
     * avoidable provider-side errors.
     */
    private List<Map<String, Object>> buildMessages(List<Map<String, String>> history, String finalUserPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();

        if (history != null) {
            for (Map<String, String> item : history) {
                if (item == null) {
                    continue;
                }

                String content = item.getOrDefault("content", "");
                if (content == null || content.isBlank()) {
                    continue;
                }

                String role = "assistant".equalsIgnoreCase(item.getOrDefault("role", ""))
                        ? "assistant"
                        : "user";

                appendMessage(messages, role, content);
            }
        }

        if (!messages.isEmpty() && "assistant".equals(messages.get(0).get("role"))) {
            messages.add(0, message("user", "(Conversation started.)"));
        }

        appendMessage(messages, "user", safeText(finalUserPrompt));

        if (messages.isEmpty()) {
            messages.add(message("user", ""));
        }

        return messages;
    }

    /**
     * Appends one message while merging repeated roles.
     */
    private void appendMessage(List<Map<String, Object>> messages, String role, String content) {
        if (messages == null || content == null || content.isBlank()) {
            return;
        }

        String safeRole = "assistant".equals(role) ? "assistant" : "user";

        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if (safeRole.equals(last.get("role"))) {
                Object oldContent = last.get("content");
                last.put("content", String.valueOf(oldContent == null ? "" : oldContent) + "\n\n" + content);
                return;
            }
        }

        messages.add(message(safeRole, content));
    }

    /**
     * Creates one Claude message map.
     *
     * The Messages API accepts a simple role/content object for plain text turns.
     * Keeping this tiny factory separate makes history normalization easier to
     * inspect when debugging provider-side "invalid request" errors.
     */
    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    /**
     * Sends the HTTP request and throws on provider errors.
     *
     * Returning strings like "Claude Error: ..." makes the agent treat transport
     * failures as successful draft text. Throwing lets SafeThoughtExecutor record
     * the failure and use fallback/recovery policy correctly.
     */
    private HttpResponse<String> sendClaudeRequest(
            String pathName,
            String targetModel,
            Map<String, Object> request,
            Duration timeout,
            int maxTokens) {
        String apiKey = requireClaudeApiKey();

        try {
            String requestBody = mapper.writeValueAsString(request);

            System.out.println("CLAUDE REQUEST PATH = " + pathName);
            System.out.println("CLAUDE REQUEST MODEL = " + targetModel);
            System.out.println("CLAUDE REQUEST TIMEOUT_SECONDS = " + timeout.toSeconds());
            System.out.println("CLAUDE REQUEST MAX_TOKENS = " + maxTokens);
            System.out.println("CLAUDE REQUEST BODY PREVIEW = " + abbreviate(requestBody, 4000));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CLAUDE_MESSAGES_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            System.out.println("CLAUDE PATH = " + pathName);
            System.out.println("CLAUDE HTTP STATUS = " + response.statusCode());
            System.out.println("CLAUDE RAW BODY PREVIEW = " + abbreviate(response.body(), 12000));

            if (response.statusCode() >= 400) {
                throw new RuntimeException("Claude API Error HTTP "
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
     * Extracts visible text from Claude's content[].text response shape.
     */
    private String extractClaudeText(String responseBody, String targetModel) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new RuntimeException("Claude returned an empty HTTP body for model " + targetModel + ".");
        }

        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode contentNode = root.path("content");
            StringBuilder text = new StringBuilder();

            if (contentNode.isArray()) {
                for (JsonNode content : contentNode) {
                    String contentType = content.path("type").asText("");
                    String contentText = content.path("text").asText("");

                    if ((contentType.isBlank() || "text".equals(contentType)) && !contentText.isBlank()) {
                        text.append(contentText);
                    }
                }
            }

            if (text.length() > 0) {
                return text.toString();
            }

            String stopReason = root.path("stop_reason").asText("");
            throw new RuntimeException("Claude returned no visible text. model="
                    + targetModel
                    + ", stop_reason="
                    + stopReason
                    + ", body="
                    + abbreviate(responseBody, 3000));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to parse Claude response. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Structured mode requires explicit JSON language in the system instruction.
     */
    private String ensureJsonSystemPrompt(String systemPrompt) {
        String safePrompt = safeText(systemPrompt);
        String lower = safePrompt.toLowerCase(Locale.ROOT);

        if (lower.contains("json")) {
            return safePrompt;
        }

        return safePrompt
                + "\n\nReturn only valid JSON. Do not use markdown. Do not wrap JSON in code fences.";
    }

    /**
     * Structured mode also repeats JSON-only instructions in the user turn because
     * provider JSON compliance improves when both system and user messages agree.
     */
    private String ensureJsonUserPrompt(String userPrompt) {
        String safePrompt = safeText(userPrompt);
        String lower = safePrompt.toLowerCase(Locale.ROOT);

        if (lower.contains("json")) {
            return safePrompt;
        }

        return safePrompt + "\n\nReturn only valid JSON. Start with { and end with }.";
    }

    /**
     * Removes markdown fences around structured JSON output.
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
     * Reads and validates the Anthropic API key from AgentConfig.
     *
     * Provider clients should fail before sending network traffic when secrets are
     * missing. That produces a clear configuration error instead of a confusing
     * provider 401 or an empty fallback response.
     */
    private String requireClaudeApiKey() {
        String apiKey = config.getClaudeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Claude API key is missing from AgentConfig.");
        }
        return apiKey;
    }

    /**
     * Resolves the active Claude model.
     *
     * Callers can pass an explicit model from ModelRouter. If they do not, the
     * configured default Claude model is used. A blank result is a setup error.
     */
    private String resolveModel(String model) {
        String targetModel = model != null && !model.isBlank()
                ? model.trim()
                : config.getDefaultClaudeModel();

        if (targetModel == null || targetModel.isBlank()) {
            throw new IllegalStateException(
                    "Claude model is missing. Provide a model or configure defaultClaudeModel.");
        }

        return targetModel.trim();
    }

    /**
     * Chooses the effective HTTP timeout for this stage.
     *
     * The caller-owned timeout is trusted when valid. The fallback exists for old
     * call sites and prevents accidental unbounded provider waits.
     */
    private Duration effectiveTimeout(Duration requested, Duration fallback) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return fallback == null ? FALLBACK_TIMEOUT : fallback;
        }
        return requested;
    }

    /**
     * Clamps integer token budgets into known safe limits.
     *
     * This prevents malformed caller input from accidentally requesting a huge
     * Claude output during a critic or synthesis stage.
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Normalizes temperature into Claude's practical range.
     *
     * MiniAgent usually uses low temperatures for generator/repair and zero-ish
     * temperatures for critic/synthesis, but defensive handling here avoids NaN or
     * invalid values reaching the provider payload.
     */
    private double clampTemperature(Double temperature) {
        if (temperature == null || temperature.isNaN() || temperature.isInfinite()) {
            return 0.2d;
        }
        return Math.max(0.0d, Math.min(1.0d, temperature));
    }

    /**
     * Converts nullable prompt fragments into safe strings.
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Produces bounded log previews for Railway.
     *
     * Full prompt/response bodies can be extremely large. This helper keeps logs
     * useful without flooding stdout or hiding the beginning of a malformed body.
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