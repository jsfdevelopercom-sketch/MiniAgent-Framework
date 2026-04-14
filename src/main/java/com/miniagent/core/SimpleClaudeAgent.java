package com.miniagent.core;

import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

/**
 * A direct, un-orchestrated Claude Agent bypassing the Critic loop for the GD Room.
 * Uses a conversational system prompt.
 */
public class SimpleClaudeAgent {

    private final ClaudeHttpClient client;
    private final PromptFactory promptFactory;

    public SimpleClaudeAgent(ClaudeHttpClient client, PromptFactory promptFactory) {
        this.client = client;
        this.promptFactory = promptFactory;
    }

    public StructuredResponse respond(String userQuery, String apiKeyOverride, Double temperature) {
        String sysPrompt = "You are Claude, an AI built by Anthropic. You are participating in a group discussion room. " +
                           "Your persona: Nuanced, deeply empathetic, highly thoughtful, and playing Devil's Advocate when needed. " +
                           "Keep your response strictly under 50 words. Focus uniquely on edge cases or human factors. " +
                           "CRITICAL RULE: If what you want to say adds absolutely no novel value beyond what the user or other agents already said, " +
                           "you MUST reply entirely with the exact text `[SILENCE]` and nothing else. Do not use markdown headers.";
        String userPrompt = "Here is the user's query and the discussion history so far:\n" + userQuery;

        try {
            String rawOutput = client.executeTextCall("claude-haiku-4-5-20251001", sysPrompt, userPrompt, temperature);
            StructuredResponse response = new StructuredResponse();
            response.setSummary(rawOutput);
            response.setRaw(rawOutput);
            return response;
        } catch (Exception e) {
            StructuredResponse err = new StructuredResponse();
            err.setSummary("Claude encountered a disruption.");
            return err;
        }
    }
}
