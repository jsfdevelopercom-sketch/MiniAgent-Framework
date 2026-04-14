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
import java.util.ArrayList;

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

    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Executes a generative call forcing JSON output format.
     *
     * @param model the Gemini model name
     * @param systemPrompt the system-level guidelines
     * @param userPrompt the specific user tasks
     * @param temperature the logical temperature randomness parameter (optional)
     * @param history the raw conversational mapping arrays
     * @return the raw JSON generated output
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt, Double temperature, List<Map<String, String>> history) {
        String apiKey = config.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing from AgentConfig.");
        }

        String targetModel = model != null ? model : config.getDefaultGeminiModel();

        int attempts = 0;
        int maxAttempts = 3;
        
        while (attempts < maxAttempts) {
            attempts++;
            try {
                Map<String, Object> request = new LinkedHashMap<>();

                // Native Contents Mapping (Replaces arbitrary string injection)
                List<Map<String, Object>> contentsList = new ArrayList<>();
                if (history != null) {
                    for (Map<String, String> h : history) {
                        String role = "user".equalsIgnoreCase(h.get("role")) ? "user" : "model";
                        contentsList.add(Map.of(
                            "role", role, 
                            "parts", List.of(Map.of("text", h.getOrDefault("content", "")))
                        ));
                    }
                }
                
                // Final Prompt Payload
                contentsList.add(Map.of(
                    "role", "user", 
                    "parts", List.of(Map.of("text", "SYSTEM INSTRUCTION:\n" + systemPrompt + "\n\nUSER PROMPT:\n" + userPrompt))
                ));
                request.put("contents", contentsList);
                
                // Force strict JSON output generation for supported v1beta models
                Map<String, Object> genConfig = new LinkedHashMap<>();
                genConfig.put("responseMimeType", "application/json");
                // Modulate temperature slightly on retry to break deterministic empty deadlocks
                double retryMod = attempts > 1 ? 0.2 * attempts : 0.0;
                if (temperature != null) genConfig.put("temperature", Math.min(2.0, temperature + retryMod));
                request.put("generationConfig", genConfig);

                // Disable all safety settings to prevent Empty Node blocking
                List<Map<String, String>> safetySettings = new ArrayList<>();
                safetySettings.add(Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"));
                safetySettings.add(Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"));
                safetySettings.add(Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"));
                safetySettings.add(Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE"));
                request.put("safetySettings", safetySettings);

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
                    if (attempts == maxAttempts) {
                        throw new RuntimeException("Gemini API Error HTTP " + response.statusCode() + ": " + response.body());
                    }
                    continue; // Auto-retry on 500s or 429s natively
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
                
                if (textNode.isMissingNode()) {
                    System.err.println("[GEMINI WARNING] Empty payload detected on attempt " + attempts + ". Retrying natively...");
                    if (attempts == maxAttempts) {
                        return "{\"thought_process\":\"Gemini generated an empty response payload repeatedly.\",\"summary\":\"Sorry, but the AI continues generating empty responses. Please try rewording your query safely.\",\"convo\":\"\"}";
                    }
                    continue; // Auto-retry
                }
                
                String responseJson = textNode.asText().trim();
                // Strip markdown wrappers if they exist
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
                if (attempts == maxAttempts) {
                    throw new RuntimeException("Failed to invoke Gemini structured call completely. Reason: " + e.getMessage(), e);
                }
            }
        }
        
        return "{\"thought_process\":\"Gemini backend failed.\",\"summary\":\"Failure.\",\"convo\":\"\"}";
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Executes a generative text-only call.
     * 
     * @param model the Gemini model name (e.g., gemini-1.5-flash)
     * @param systemPrompt the system-level guidelines
     * @param userPrompt the specific user tasks or context
     * @param temperature the generation temperature logic
     * @return the plain text generated output
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt, Double temperature) {
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
            
            if (temperature != null) {
                Map<String, Object> genConfig = new LinkedHashMap<>();
                genConfig.put("temperature", temperature);
                request.put("generationConfig", genConfig);
            }

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
            if (textNode.isMissingNode()) {
                System.err.println("[GEMINI WARNING] Unexpected or empty response format: " + response.body());
                return "AI generated an empty response.";
            }
            return textNode.asText();

        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke Gemini text call.", e);
        }
    }
}
