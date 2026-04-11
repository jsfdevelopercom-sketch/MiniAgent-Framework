package com.miniagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.config.AgentConfig;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

import java.util.List;
import java.util.Map;

/**
 * MiniAgentWorker is the generative workhorse of the architecture.
 * <p>
 * It utilizes the PromptFactory to build constraints and calls the LLM APIs
 * to produce structured output. It abstracts model switching and error handling.
 */
public class MiniAgentWorker {

    private final OpenAiHttpClient openAiHttpClient;
    private final GeminiHttpClient geminiHttpClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    public MiniAgentWorker(
            OpenAiHttpClient openAiHttpClient,
            GeminiHttpClient geminiHttpClient,
            PromptFactory promptFactory,
            ObjectMapper mapper
    ) {
        this.openAiHttpClient = openAiHttpClient;
        this.geminiHttpClient = geminiHttpClient;
        this.promptFactory = promptFactory;
        this.mapper = mapper;
    }

    /**
     * Generates a structured response based on domain parameters and data.
     * 
     * @param model the LLM model identifier to use
     * @param domainContext specific AI role definition
     * @param taskInstructions what the AI specifically needs to accomplish
     * @param dataset mapped variables providing facts
     * @param liveInjections user instructions dynamically added midway that must be obeyed
     * @return A parsed StructuredResponse ensuring separation of reasoning, summary, and meta-conversation
     */
    public StructuredResponse generateDraft(
            String model,
            String domainContext,
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections
    ) {
        String sysPrompt = promptFactory.buildWorkerSystemPrompt(domainContext);
        String userPrompt = promptFactory.buildWorkerUserPrompt(taskInstructions, dataset, liveInjections);

        String rawJson;
        if (model != null && model.toLowerCase().startsWith("gemini")) {
            rawJson = geminiHttpClient.executeStructuredCall(model, sysPrompt, userPrompt);
        } else {
            rawJson = openAiHttpClient.executeStructuredCall(model, sysPrompt, userPrompt);
        }
        return parseToStructuredResult(rawJson);
    }

    /**
     * Attempts a focused repair cycle addressing specific defects discovered by the Evaluator.
     * 
     * @param model target LLM model
     * @param previousDraft the broken string payload
     * @param factualityFixes listed hallucinations to fix
     * @param structuralFixes listed schema/format violations to fix
     * @param missingInstructions instructions the user provided that the worker ignored
     * @param dataset the ground truth variables
     * @return A newly parsed StructuredResponse generated through active correction
     */
    public StructuredResponse repairDraft(
            String model,
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset
    ) {
        String sysPrompt = promptFactory.buildRepairSystemPrompt();
        String userPrompt = promptFactory.buildRepairUserPrompt(
                previousDraft,
                factualityFixes,
                structuralFixes,
                missingInstructions,
                dataset
        );

        String rawJson;
        if (model != null && model.toLowerCase().startsWith("gemini")) {
            rawJson = geminiHttpClient.executeStructuredCall(model, sysPrompt, userPrompt);
        } else {
            rawJson = openAiHttpClient.executeStructuredCall(model, sysPrompt, userPrompt);
        }
        return parseToStructuredResult(rawJson);
    }

    /**
     * Parses the strict JSON emitted by the network client into our StructuredResponse object.
     * Provides immense safety by dropping malformed strings safely.
     */
    private StructuredResponse parseToStructuredResult(String rawJson) {
        try {
            StructuredResponse response = mapper.readValue(rawJson, StructuredResponse.class);
            response.setRaw(rawJson);
            return response;
        } catch (Exception e) {
            // Failsafe: if the generator fully corrupts the JSON, return the raw data safely encapsulated in 'convo'.
            StructuredResponse failSafe = new StructuredResponse();
            failSafe.setRaw(rawJson);
            failSafe.setSummary("");
            failSafe.setThought_process("JSON parsing failed catastrophically.");
            failSafe.setConvo(rawJson);
            return failSafe;
        }
    }
}
