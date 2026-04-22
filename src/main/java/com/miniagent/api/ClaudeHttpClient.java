package com.miniagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.AgentConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles communication with the Anthropic Claude API using java.net.http.
 */
public class ClaudeHttpClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AgentConfig config;

    public ClaudeHttpClient(AgentConfig config) {
        this.config = config;
    }

    public AgentConfig getConfig() {
        return config;
    }

    public String executeStructuredCall(String model, String systemPrompt, String userPrompt) {
        return executeStructuredCall(model, systemPrompt, userPrompt, null, null);
    }

    /**
     * Executes a call expecting a structured JSON response.
     */
    public String executeStructuredCall(String model, String systemPrompt, String userPrompt, Double temperature, List<Map<String, String>> history) {
        String apiKey = config.getClaudeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Claude API key is not configured.");
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("max_tokens", 4000);
            request.put("system", systemPrompt);
            if (temperature != null) request.put("temperature", temperature);

            List<Map<String, Object>> messages = new ArrayList<>();
            if (history != null) {
                for (Map<String, String> hc : history) {
                    messages.add(Map.of(
                        "role", "user".equalsIgnoreCase(hc.getOrDefault("role", "")) ? "user" : "assistant",
                        "content", hc.getOrDefault("content", "")
                    ));
                }
            }
            messages.add(Map.of(
                "role", "user",
                "content", userPrompt + "\n\nRETURN ONLY VALID JSON. Start your response with { and end with }."
            ));
            request.put("messages", messages);

            String requestBody = mapper.writeValueAsString(request);
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                System.err.println("[CLAUDE ERROR] HTTP " + response.statusCode() + ": " + response.body());
                throw new RuntimeException("Claude API Error: " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode contentNode = root.path("content");
            StringBuilder textBuilder = new StringBuilder();
            if (contentNode.isArray()) {
                for (JsonNode c : contentNode) {
                    if (c.has("text")) textBuilder.append(c.get("text").asText());
                }
            }
            
            if (textBuilder.length() == 0) {
                System.err.println("[CLAUDE WARNING] Empty response: " + response.body());
                return "{\"thought_process\":\"Claude returned an empty frame.\",\"summary\":\"Sorry, but Claude generated an empty response.\",\"convo\":\"\"}";
            }

            String responseJson = textBuilder.toString().trim();
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
            System.err.println("Failed to invoke Claude." + e.getMessage());
            throw new RuntimeException("Failed to invoke Claude structured call. Reason: " + e.getMessage(), e);
        }
    }

    public String executeTextCall(String model, String systemPrompt, String userPrompt) {
        return executeTextCall(model, systemPrompt, userPrompt, null);
    }

    /**
     * Executes a raw conversational query specifically designed for the GD Room persona.
     * Takes an optional token override per the architecture spec securely mapping user BYOT keys.
     */
    public String executeTextCall(String model, String systemPrompt, String userPrompt, Double temperature) {
        String apiKey = config.getClaudeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Claude API key is not configured for text calls.");
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("max_tokens", 4000);
            request.put("system", systemPrompt);
            if (temperature != null) request.put("temperature", temperature);

            request.put("messages", List.of(
                Map.of("role", "user", "content", userPrompt)
            ));

            String requestBody = mapper.writeValueAsString(request);
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                System.err.println("[CLAUDE ERROR] HTTP " + response.statusCode() + ": " + response.body());
                return "Claude Error: " + response.body();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode contentNode = root.path("content");
            StringBuilder textBuilder = new StringBuilder();
            if (contentNode.isArray()) {
                for (JsonNode c : contentNode) {
                    if (c.has("text")) textBuilder.append(c.get("text").asText());
                }
            }
            return textBuilder.length() == 0 ? "Claude produced an empty message structure." : textBuilder.toString().trim();

        } catch (Exception e) {
            return "Claude network disruption: " + e.getMessage();
        }
    }
}
