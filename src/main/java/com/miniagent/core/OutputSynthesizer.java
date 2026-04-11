package com.miniagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

/**
 * OutputSynthesizer uses a dual-model approach to finalize AI outputs.
 * 1. It uses Gemini (fast, large context) to extract the pure intent from a messy "deep thought" draft.
 * 2. It uses OpenAI (strict markdown/schema adherence) to format the final output properly.
 */
public class OutputSynthesizer {

    private final OpenAiHttpClient openAi;
    private final GeminiHttpClient gemini;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    public OutputSynthesizer(OpenAiHttpClient openAi, GeminiHttpClient gemini, PromptFactory promptFactory, ObjectMapper mapper) {
        this.openAi = openAi;
        this.gemini = gemini;
        this.promptFactory = promptFactory;
        this.mapper = mapper;
    }

    /**
     * Cleans and finalizes the deep thinking output.
     * 
     * @param draft The potentially messy output from the worker suite.
     * @param originalQuery The user's original request.
     * @return A pristine StructuredResponse ready for the frontend.
     */
    public StructuredResponse synthesize(StructuredResponse draft, String originalQuery) {
        try {
            // Step 1: Gemini Extraction
            System.out.println("[SYNTHESIZER] Step 1: Extraction running...");
            String extractionSys = promptFactory.buildSynthesisExtractionSystemPrompt();
            String extractionUser = "Original User Query: " + originalQuery + "\n\nMessy AI Draft:\n" + draft.getSummary() + "\n\n" + draft.getRaw();
            String extractedRaw = gemini.executeTextCall("gemini-2.5-flash", extractionSys, extractionUser);

            // Step 2: OpenAI Schema & Markdown Formatting
            System.out.println("[SYNTHESIZER] Step 2: OpenAI Formatting running...");
            String openaiSys = promptFactory.buildSynthesisFormattingSystemPrompt();
            String formattedJson = openAi.executeStructuredCall("gpt-4o-mini", openaiSys, extractedRaw);
            
            StructuredResponse finalResponse = mapper.readValue(formattedJson, StructuredResponse.class);
            
            if (finalResponse.getSummary() == null || finalResponse.getSummary().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(formattedJson);
                String fallbackText = "";
                java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                    if (!field.getKey().equals("thought_process") && !field.getKey().equals("convo")) {
                        fallbackText += field.getValue().asText() + "\n";
                    }
                }
                finalResponse.setSummary(fallbackText.trim().isEmpty() ? formattedJson : fallbackText.trim());
            }

            finalResponse.setRaw(formattedJson);
            return finalResponse;
            
        } catch (Exception e) {
            System.err.println("[SYNTHESIZER ERROR] " + e.getMessage());
            // Fallback safely to original draft if synthesis fails
            return draft;
        }
    }
}
