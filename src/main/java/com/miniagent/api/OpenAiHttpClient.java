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
import java.util.Map;

/**
 * OpenAiHttpClient is a zero-dependency (other than Jackson) raw HTTP client
 * designed to interact with OpenAI APIs dynamically.
 * <p>
 * This class abstracts away network transport, using java.net.http.HttpClient
 * to enforce structured JSON output on OpenAI's /v1/responses or /v1/chat/completions endpoints.
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
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Executes a generative call forcing the strict JSON output schema.
     * 
     * @param model the model to run (e.g., gpt-4o-mini)
     * @param systemPrompt the system-level behavior directive
     * @param userPrompt the task-level data
     * @return Raw JSON output text expected to map strictly to StructuredResponse
     * @throws RuntimeException if network or authentication fails
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        String apiKey = config.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }

        try {
            // Using /v1/chat/completions as it supports structured JSON outputs reliably
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model != null ? model : config.getDefaultOpenaiModel());
            
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );
            request.put("messages", messages);

            // Force JSON object mode
            request.put("response_format", Map.of("type", "json_object"));

            String requestBody = mapper.writeValueAsString(request);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
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
            throw new RuntimeException("Failed to invoke OpenAI structured call.", e);
        }
    }

    /**
     * Executes a simple text-to-text generative call (often used by Evaluators).
     * 
     * @param model the underlying model
     * @param systemPrompt system directive
     * @param userPrompt task data
     * @return The plain text output
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        String apiKey = config.getOpenaiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing from AgentConfig.");
        }

        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model != null ? model : config.getDefaultOpenaiModel());
            
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            );
            request.put("messages", messages);

            String requestBody = mapper.writeValueAsString(request);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
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
            throw new RuntimeException("Failed to invoke OpenAI text call.", e);
        }
    }
}
