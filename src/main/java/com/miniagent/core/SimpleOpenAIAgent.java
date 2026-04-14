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
                           "Keep your response natural, highly analytical, conversational, and strictly under 100 words. " +
                           "If what you want to say has already been thoroughly covered by other agents, " +
                           "or if you have absolutely nothing novel to add, reply EXACTLY with the word [SILENCE]. " +
                           "Do not use markdown headers, just speak naturally to the user and the group.";
        String userPrompt = "Here is the user's query and the discussion history so far:\n" + userQuery;

        try {
            String rawOutput = client.executeTextCall("gpt-4o-mini", sysPrompt, userPrompt, temperature);
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
