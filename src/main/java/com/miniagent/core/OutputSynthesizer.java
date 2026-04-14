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
 * 
 * Crucially, this acts as the "boss" layer. If it detects sloppy work (like empty JSON returning),
 * it forcefully commands the pipeline to try again. If it completely bombs out, it converts
 * the stack trace into a friendly bulleted list for the end user rather than crashing the UI.
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
     * Cleans and finalizes the deep thinking output, applying defensive error checks.
     * 
     * @param draft The potentially messy output from the worker suite.
     * @param originalQuery The user's original request.
     * @return A pristine StructuredResponse ready for the frontend.
     */
    public StructuredResponse synthesize(StructuredResponse draft, String originalQuery, String synthesizerModel) {
        int maximumRepeats = 3; // We give it 3 solid attempts before we give up completely
        int attempt = 1;

        while (attempt <= maximumRepeats) {
            try {
                System.out.println("[SYNTHESIZER] Execution Cycle " + attempt + " running...");
                /*
                 * Step 1: Direct Final Formatting
                 * Use dynamically requested synthesis models to format the raw payload. 
                 * We skip intermediary extraction to prevent data trimming!
                 */
                String openaiSys = promptFactory.buildSynthesisFormattingSystemPrompt();
                String openaiUser = "Original User Query: " + originalQuery + "\n\nRaw Agent Draft:\n" + draft.getRaw();
                
                // Ensure default fallback if missing
                String targetSynth = synthesizerModel != null ? synthesizerModel : "gpt-4o-mini";
                String formattedJson = openAi.executeStructuredCall(targetSynth, openaiSys, openaiUser);
                
                /*
                 * Validation Gateway
                 * If the models still managed to leak the phrase "empty json returned", we intercept it here.
                 */
                if (formattedJson.toLowerCase().contains("empty json returned")) {
                    System.out.println("[SYNTHESIZER BLOCK] Detected 'empty json returned' leak in output format. Retrying... (Attempt " + attempt + ")");
                    attempt++;
                    continue; // Skip the rest, force the loop to go again
                }
                
                StructuredResponse finalResponse = mapper.readValue(formattedJson, StructuredResponse.class);
                
                // Final safety check over the summary fields
                if (finalResponse.getSummary() == null || finalResponse.getSummary().isBlank() || finalResponse.getSummary().toLowerCase().contains("empty json returned")) {
                    if (attempt < maximumRepeats) {
                        System.out.println("[SYNTHESIZER BLOCK] Summary remains empty or corrupted. Retrying... (Attempt " + attempt + ")");
                        attempt++;
                        continue;
                    }
                    
                    // If we made it here but it's empty, attempt an aggressive raw JSON field extraction natively.
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(formattedJson);
                    String fallbackText = "";
                    java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = root.fields();
                    while (fields.hasNext()) {
                        java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
                        if (!field.getKey().equals("thought_process") && !field.getKey().equals("convo")) {
                            fallbackText += field.getValue().asText() + "\n";
                        }
                    }
                    finalResponse.setSummary(fallbackText.trim().isEmpty() ? "The request was evaluated, but no textual summary could be formulated natively." : fallbackText.trim());
                }

                // If everything survived the gauntlet, return the pristine response!
                finalResponse.setRaw(formattedJson);
                return finalResponse;
                
            } catch (Exception e) {
                System.err.println("[SYNTHESIZER ERROR] Exception tripped during attempt " + attempt + ": " + e.getMessage());
                // If it's not our final attempt, go again.
                if (attempt < maximumRepeats) {
                    attempt++;
                    continue;
                }
                // Terminal error occurred across all runs. Send to fallback breakdown.
                return generateLaymanErrorExplanation(originalQuery, e.getMessage());
            }
        }
        
        // Failsafe outside the loop (Should theoretically never execute due to the return in catch)
        return generateLaymanErrorExplanation(originalQuery, "Synthesis exhausted all " + maximumRepeats + " retries hitting dead-ends.");
    }

    /**
     * Translates a terrifying backend Java Exception into a gentle, 
     * human-readable bullet list explaining exactly what broke to the CEO / user.
     * Prevents technical gobbledegook from hitting the UI frontend.
     */
    private StructuredResponse generateLaymanErrorExplanation(String originalQuery, String rawErrorCause) {
        System.out.println("[SYNTHESIZER] Generating layman-friendly explanation for hard error...");
        try {
            String sys = "You are a friendly, deeply empathetic technical assistant communicating with an end user. " +
                         "Our backend systems just threw a massive unhandled exception preventing their query from completing. " +
                         "Raw Error details: '" + rawErrorCause + "'.\n\n" +
                         "Your job: Briefly explain what went wrong in a tiny, layman-perspective paragraph. Follow it up with a 2-3 point bulleted list on what they can do next (e.g. Try rewording, Check connection, Wait a moment). " +
                         "Ensure it is brief and completely avoids hardcore developer jargon. " +
                         "Output structured JSON conforming exactly to {\"thought_process\": \"...\", \"summary\": \"your formatted markdown explanation\", \"convo\": \"...\"}";
            
            // Execute the rescue generation over GPT
            String json = openAi.executeStructuredCall("gpt-4o-mini", sys, "The User originally requested: " + originalQuery);
            return mapper.readValue(json, StructuredResponse.class);
            
        } catch (Exception fatalErr) {
            // Unbelievable, even the error handler crashed. Fall back to absolute bedrock text.
            StructuredResponse bedrock = new StructuredResponse();
            bedrock.setSummary("### 🚨 Critical System Error\nWe experienced a highly severe network failure processing this request.\n\n* The internal models timed out.\n* Please wait 30 seconds and try again.");
            bedrock.setThought_process("Deep infrastructure panic.");
            bedrock.setRaw("Fatal cascading exception.");
            return bedrock;
        }
    }
}
