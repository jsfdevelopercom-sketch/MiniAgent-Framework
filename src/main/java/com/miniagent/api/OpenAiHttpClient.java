package com.miniagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAiHttpClient is a zero-dependency (other than Jackson) raw HTTP client
 * designed to interact with OpenAI APIs dynamically.
 * <p>
 * This class abstracts away network transport, using java.net.http.HttpClient
 * to enforce structured JSON output on OpenAI's /v1/responses or
 * /v1/chat/completions endpoints.
 */
public class OpenAiHttpClient {

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Constructs the HTTP client tied to a specific AgentConfig.
     * 
     * @param config the dynamic configuration containing live API keys
     * @param mapper Jackson object mapper for serialization
     */
    public OpenAiHttpClient(AgentConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public AgentConfig getConfig() {
        return config;
    }

    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Executes a generative call forcing the strict JSON output schema.
     * 
     * @param model        the model to run (e.g., gpt-4o-mini)
     * @param systemPrompt the system-level behavior directive
     * @param userPrompt   the task-level data
     * @param temperature  randomness logic for GD overrides
     * @return Raw JSON output text expected to map strictly to StructuredResponse
     * @throws RuntimeException if network or authentication fails
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt, Double temperature,
            List<Map<String, String>> history) {
        String apiKey = config.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }

        try {
            // Using /v1/chat/completions as it supports structured JSON outputs reliably
            Map<String, Object> request = new LinkedHashMap<>();
            String activeModel = model != null ? model : config.getDefaultOpenaiModel();
            boolean isResponsesApi = activeModel.endsWith("-pro") || activeModel.contains("deep-research")
                    || activeModel.startsWith("o1-pro");

            request.put("model", activeModel);
            if (com.miniagent.core.ModelConstants.HIGH_THINKING_MODELS.contains(activeModel)) {
                if (isResponsesApi)
                    request.put("reasoning", Map.of("effort", "high"));
                else
                    request.put("reasoning_effort", "high");
            } else if (com.miniagent.core.ModelConstants.MEDIUM_THINKING_MODELS.contains(activeModel)) {
                if (isResponsesApi)
                    request.put("reasoning", Map.of("effort", "medium"));
                else
                    request.put("reasoning_effort", "medium");
            }
            if (temperature != null && !activeModel.startsWith("o1") && !activeModel.startsWith("o3")
                    && !activeModel.startsWith("o4")) {
                if (supportsTemperature(activeModel)) {
                    request.put("temperature", temperature);
                }
            }

            List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            if (history != null) {
                for (Map<String, String> h : history) {
                    messages.add(Map.of(
                            "role", "user".equalsIgnoreCase(h.getOrDefault("role", "")) ? "user" : "assistant",
                            "content", h.getOrDefault("content", "")));
                }
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            if (isResponsesApi) {
                request.put("input", messages);
            } else {
                request.put("messages", messages);
            }
            if (activeModel.contains("deep-research")) {
                request.put("tools", List.of(Map.of("type", "web_search_preview")));
            }

            // Force JSON object mode if supported
            if (!activeModel.contains("search") && !activeModel.contains("transcribe")
                    && !activeModel.contains("diarize")) {
                if (isResponsesApi) {
                    request.put("text", Map.of("format", Map.of("type", "json_object")));
                } else {
                    request.put("response_format", Map.of("type", "json_object"));
                }
            }

            boolean highModel = com.miniagent.core.ModelConstants.isHighModel(activeModel);
            if (highModel) {
                if (isResponsesApi)
                    request.put("max_output_tokens", 16384);
                else
                    request.put("max_completion_tokens", 16384);
            }

            String requestBody = mapper.writeValueAsString(request);

            String endpoint = "https://api.openai.com/v1/chat/completions";
            if (activeModel.endsWith("-pro") || activeModel.contains("deep-research")
                    || activeModel.startsWith("o1-pro")) {
                endpoint = "https://api.openai.com/v1/responses";
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(highModel ? Duration.ofMinutes(15) : Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI API Error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode messageNode = root.path("choices").path(0).path("message").path("content");
            String responseJson = messageNode.asText().trim();
            if (responseJson.startsWith("```json")) {
                responseJson = responseJson.substring(7);
                if (responseJson.endsWith("```")) {
                    responseJson = responseJson.substring(0, responseJson.length() - 3);
                }
            } else if (responseJson.startsWith("```")) {
                responseJson = responseJson.substring(3);
                if (responseJson.endsWith("```")) {
                    responseJson = responseJson.substring(0, responseJson.length() - 3);
                }
            }
            return responseJson.trim();

        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI structured call. Reason: " + e.getMessage(), e);
        }
    }

    private boolean supportsTemperature(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }

        String m = model.toLowerCase(Locale.ROOT);

        /*
         * Reasoning/high-control OpenAI models often reject custom temperature.
         * The provider may require the default temperature only.
         * For those models, omit the temperature field entirely.
         */
        if (m.startsWith("gpt-5")
                || m.startsWith("o1")
                || m.startsWith("o3")
                || m.startsWith("o4")
                || m.contains("deep-research")) {
            return false;
        }

        return true;
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Executes a simple text-to-text generative call (often used by Evaluators).
     * 
     * @param model        the underlying model
     * @param systemPrompt system directive
     * @param userPrompt   task data
     * @param temperature  specific variability override for the GD room persona
     * @return The plain text output
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt, Double temperature) {
        String apiKey = config.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            String activeModel = model != null ? model : config.getDefaultOpenaiModel();
            boolean isResponsesApi = activeModel.endsWith("-pro") || activeModel.contains("deep-research")
                    || activeModel.startsWith("o1-pro");

            request.put("model", activeModel);
            if (com.miniagent.core.ModelConstants.HIGH_THINKING_MODELS.contains(activeModel)) {
                if (isResponsesApi)
                    request.put("reasoning", Map.of("effort", "high"));
                else
                    request.put("reasoning_effort", "high");
            } else if (com.miniagent.core.ModelConstants.MEDIUM_THINKING_MODELS.contains(activeModel)) {
                if (isResponsesApi)
                    request.put("reasoning", Map.of("effort", "medium"));
                else
                    request.put("reasoning_effort", "medium");
            }
            if (temperature != null && !activeModel.startsWith("o1") && !activeModel.startsWith("o3")
                    && !activeModel.startsWith("o4")) {
                if (supportsTemperature(activeModel)) {
                    request.put("temperature", temperature);
                }
            }

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt));
            if (isResponsesApi) {
                request.put("input", messages);
            } else {
                request.put("messages", messages);
            }
            if (activeModel.contains("deep-research")) {
                request.put("tools", List.of(Map.of("type", "web_search_preview")));
            }

            boolean highModel = com.miniagent.core.ModelConstants.isHighModel(activeModel);
            if (highModel) {
                if (isResponsesApi)
                    request.put("max_output_tokens", 16384);
                else
                    request.put("max_completion_tokens", 16384);
            }

            String requestBody = mapper.writeValueAsString(request);

            String endpoint = "https://api.openai.com/v1/chat/completions";
            if (activeModel.endsWith("-pro") || activeModel.contains("deep-research")
                    || activeModel.startsWith("o1-pro")) {
                endpoint = "https://api.openai.com/v1/responses";
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(highModel ? Duration.ofMinutes(15) : Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("OpenAI API Error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode messageNode = root.path("choices").path(0).path("message").path("content");
            return messageNode.asText();

        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke OpenAI text call. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * Executes an ultra-fast raw multi-part HTTP mapping to OpenAI's Whisper API.
     * We sidestep heavy library dependencies by natively formulating the multipart
     * MIME
     * boundaries allowing lightning-fast audio string chunk deliveries straight out
     * of
     * the user's WebRTC device flow directly up to GPT transcription models.
     * 
     * @param audioBytes the pure raw audio payload from the navigator blob
     * @param apiKey     user token or BYOT
     * @param filename   generated browser filename so OpenAI infers container
     *                   mapping correctly
     * @return Transcribed layout text mapped out
     */
    public static String executeWhisperTranscription(byte[] audioBytes, String apiKey, String filename)
            throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Cannot execute Whisper audio without OpenAI authentication layout.");
        }

        // Random secure string boundary logic
        String boundary = "----AgentNeroVoiceBoundary" + System.currentTimeMillis();

        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append("--").append(boundary).append("\r\n");
        headerBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename)
                .append("\"\r\n");
        // We tell whisper we are shipping standard secure audio containers
        headerBuilder.append("Content-Type: application/octet-stream\r\n\r\n");

        byte[] headerBytes = headerBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StringBuilder formPadding = new StringBuilder();
        formPadding.append("\r\n--").append(boundary).append("\r\n");
        formPadding.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
        // Open AI's voice processing endpoint runs precisely off the whisper-1
        // architectural name mapping
        formPadding.append("whisper-1\r\n");
        formPadding.append("--").append(boundary).append("--\r\n");

        byte[] footerBytes = formPadding.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Aggregate payload size directly saving buffer allocations
        byte[] multipartBody = new byte[headerBytes.length + audioBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, multipartBody, 0, headerBytes.length);
        System.arraycopy(audioBytes, 0, multipartBody, headerBytes.length, audioBytes.length);
        System.arraycopy(footerBytes, 0, multipartBody, headerBytes.length + audioBytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60)) // Allow slightly longer for heavy uploads
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException(
                    "Whisper Engine Rejected Upload: " + response.statusCode() + " " + response.body());
        }

        // Return pure text resolution block natively
        return new ObjectMapper().readTree(response.body()).path("text").asText();
    }

    /**
     * Executes an immediate streaming-compatible HTTP POST to the OpenAI TTS
     * generator.
     * Maps precisely to the tts-1 model for extreme latency optimizations mapping
     * natively to the
     * audio byte payload formats.
     * 
     * @param text   The humanized summary text for Agent Nero to speak.
     * @param voice  The requested OpenAI voice (alloy, echo, fable, onyx, nova,
     *               shimmer).
     * @param apiKey The active user token or server BYOT.
     * @return Raw binary audio layer (MP3 format natively by default).
     */
    public static byte[] executeSpeechSynthesis(String text, String voice, String apiKey) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot execute Speech Synthesis audio without OpenAI authentication layout.");
        }

        Map<String, Object> payload = new java.util.HashMap<>();
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

        HttpResponse<byte[]> response = HttpClient.newBuilder().build().send(request,
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("TTS Engine Rejected Payload: " + response.statusCode() + " "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        }

        return response.body();
    }
}
