package com.miniagent.core;

import com.miniagent.api.GeminiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

/**
 * A direct, un-orchestrated Gemini Agent bypassing the Critic loop for the GD Room.
 * Uses a conversational system prompt.
 */
public class SimpleGeminiAgent {

    private final GeminiHttpClient client;
    private final PromptFactory promptFactory;

    public SimpleGeminiAgent(GeminiHttpClient client, PromptFactory promptFactory) {
        this.client = client;
        this.promptFactory = promptFactory;
    }

    public StructuredResponse respond(String userQuery, String apiKeyOverride, Double temperature) {
        String sysPrompt = "You are Gemini, an AI built by Google. You are participating in a group discussion room. " +
                           "Keep your response natural, conversational, insightful, and strictly under 100 words. " +
                           "If what you want to say has already been thoroughly covered by other agents, " +
                           "or if you have absolutely nothing novel to add, reply EXACTLY with the word [SILENCE]. " +
                           "Do not use markdown headers, just speak naturally to the user and the group.";
        String userPrompt = "Here is the user's query and the discussion history so far:\n" + userQuery;

        try {
            // Optional: You could pass apiKeyOverride into executeTextCall if the client supported dynamic injection
            // The existing clients use sharedConfig, but AgentNeroServer overrides environment natively.
            String rawOutput = client.executeTextCall("gemini-2.5-flash", sysPrompt, userPrompt, temperature);
            StructuredResponse response = new StructuredResponse();
            response.setSummary(rawOutput);
            response.setRaw(rawOutput);
            return response;
        } catch (Exception e) {
            StructuredResponse err = new StructuredResponse();
            err.setSummary("Gemini encountered a disruption.");
            return err;
        }
    }
}
