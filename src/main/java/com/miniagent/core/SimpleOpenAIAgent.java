package com.miniagent.core;

import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

/**
 * A direct, un-orchestrated OpenAI GPT Agent bypassing the Critic loop for the GD Room.
 * Uses a conversational system prompt.
 */
public class SimpleOpenAIAgent {

    private final OpenAiHttpClient client;
    private final PromptFactory promptFactory;

    public SimpleOpenAIAgent(OpenAiHttpClient client, PromptFactory promptFactory) {
        this.client = client;
        this.promptFactory = promptFactory;
    }

    public StructuredResponse respond(String userQuery, String apiKeyOverride, Double temperature) {
        String sysPrompt = "You are GPT, an AI built by OpenAI. You are participating in a group discussion room. " +
                           "Your persona: Highly analytical, logical, data-driven, and concise. " +
                           "Keep your response strictly under 50 words. Focus strictly on facts and logic. " +
                           "CRITICAL RULE: If what you want to say adds absolutely no novel value beyond what the user or other agents already said, " +
                           "you MUST reply entirely with the exact text `[SILENCE]` and nothing else. Do not use markdown headers.";
        String userPrompt = "Here is the user's query and the discussion history so far:\n" + userQuery;

        try {
            String rawOutput = client.executeTextCall(client.getConfig().getDefaultOpenaiModel(), sysPrompt, userPrompt, temperature);
            StructuredResponse response = new StructuredResponse();
            response.setSummary(rawOutput);
            response.setRaw(rawOutput);
            return response;
        } catch (Exception e) {
            StructuredResponse err = new StructuredResponse();
            err.setSummary("GPT encountered a disruption.");
            return err;
        }
    }
}
