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
 * GeminiHttpClient acts as the native networking bridge to Google's Gemini models.
 * <p>
 * Given Gemini's slightly different payload expectations compared to OpenAI, this
 * client serializes the prompts into Gemini's "contents" and "parts" arrays.
 */
public class GeminiHttpClient {

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * Constructs the HTTP client tied to a specific AgentConfig.
     * 
     * @param config the dynamic configuration containing live API keys
     * @param mapper Jackson object mapper for serialization
     */
    public GeminiHttpClient(AgentConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Executes a generative call forcing JSON output format.
     *
     * @param model the Gemini model name
     * @param systemPrompt the system-level guidelines
     * @param userPrompt the specific user tasks
     * @return the raw JSON generated output
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        String apiKey = config.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing from AgentConfig.");
        }

        String targetModel = model != null ? model : config.getDefaultGeminiModel();

        try {
            Map<String, Object> request = new LinkedHashMap<>();

            // Contents
            Map<String, Object> contents = new LinkedHashMap<>();
            contents.put("role", "user");
            String combinedText = "SYSTEM INSTRUCTION:\n" + systemPrompt + "\n\nUSER PROMPT:\n" + userPrompt;
            contents.put("parts", List.of(Map.of("text", combinedText)));
            request.put("contents", List.of(contents));

            // Generation Config for JSON Mode
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("response_mime_type", "application/json");
            request.put("generationConfig", generationConfig);

            String requestBody = mapper.writeValueAsString(request);
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + targetModel + ":generateContent?key=" + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini API Error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            return textNode.asText();

        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke Gemini structured call.", e);
        }
    }

    /**
     * Executes a generative text-only call.
     * 
     * @param model the Gemini model name (e.g., gemini-1.5-flash)
     * @param systemPrompt the system-level guidelines
     * @param userPrompt the specific user tasks or context
     * @return the plain text generated output
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        String apiKey = config.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing from AgentConfig.");
        }

        String targetModel = model != null ? model : config.getDefaultGeminiModel();

        try {
            // Gemini expects "system_instruction" separately in some v1beta APIs, 
            // but combining them in the user prompt is often safer for standard v1.
            // This maps strictly to standard "generateContent".
            Map<String, Object> request = new LinkedHashMap<>();
            
            Map<String, Object> contents = new LinkedHashMap<>();
            contents.put("role", "user");
            
            // For stability, prepend system prompt to the user text if system_instructions isn't formally used
            String combinedText = "SYSTEM INSTRUCTION:\n" + systemPrompt + "\n\nUSER PROMPT:\n" + userPrompt;
            contents.put("parts", List.of(Map.of("text", combinedText)));

            request.put("contents", List.of(contents));

            String requestBody = mapper.writeValueAsString(request);
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + targetModel + ":generateContent?key=" + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Gemini API Error HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            return textNode.asText();

        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke Gemini text call.", e);
        }
    }
}
