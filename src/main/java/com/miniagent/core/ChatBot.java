package com.miniagent.core;

import com.miniagent.model.StructuredResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatBot is a stateful conversational layer built on top of MiniAgent.
 * 
 * It automatically tracks conversation history, and provides configurable
 * intelligence levels to balance speed and accuracy. If the history grows
 * beyond the configured limit, it will automatically compress/summarize
 * older messages to keep the token size manageable without losing context.
 */
public class ChatBot {

    public enum IntelligenceLevel {
        FAST,
        BALANCED,
        DEEP
    }

    private final Agent agent;
    private IntelligenceLevel intelligenceLevel;
    private int maxHistorySize;
    private String explicitModel;
    
    private final List<Map<String, String>> history;
    private final Map<String, Object> memoryDataset;

    /**
     * Creates a new ChatBot.
     *
     * @param agent             The underlying Agent orchestrator.
     * @param intelligenceLevel The speed/intelligence strategy (FAST vs DEEP).
     * @param maxHistorySize    The number of messages before history is summarized.
     */
    public ChatBot(Agent agent, IntelligenceLevel intelligenceLevel, int maxHistorySize) {
        if (agent == null) {
            throw new IllegalArgumentException("Agent cannot be null.");
        }
        this.agent = agent;
        this.intelligenceLevel = intelligenceLevel != null ? intelligenceLevel : IntelligenceLevel.BALANCED;
        this.maxHistorySize = maxHistorySize > 0 ? maxHistorySize : 10;
        this.history = new ArrayList<>();
        this.memoryDataset = new HashMap<>();
    }

    /**
     * Creates a new ChatBot with default settings (BALANCED, 10 messages).
     *
     * @param agent The underlying Agent orchestrator.
     */
    public ChatBot(Agent agent) {
        this(agent, IntelligenceLevel.BALANCED, 10);
    }

    public void setExplicitModel(String model) {
        this.explicitModel = model;
    }

    public void setIntelligenceLevel(IntelligenceLevel intelligenceLevel) {
        if (intelligenceLevel != null) {
            this.intelligenceLevel = intelligenceLevel;
        }
    }
    
    public void setMaxHistorySize(int maxHistorySize) {
        if (maxHistorySize > 0) {
            this.maxHistorySize = maxHistorySize;
        }
    }

    public List<Map<String, String>> getHistory() {
        return history;
    }

    public Map<String, Object> getMemoryDataset() {
        return memoryDataset;
    }

    /**
     * Sends a message to the ChatBot and gets a reply.
     * Uses default anonymous user and a balanced temperature of 0.7.
     */
    public String chat(String message) {
        return chat(message, "chatbot-user", 0.7);
    }

    /**
     * Sends a message to the ChatBot and gets a reply.
     *
     * @param message     The user's input.
     * @param userId      The ID of the user for tracing.
     * @param temperature The generation temperature.
     * @return The AI's response text.
     */
    public String chat(String message, String userId, Double temperature) {
        if (message == null || message.trim().isEmpty()) {
            return "Please provide a valid message.";
        }
        
        checkAndSummarizeHistory(userId, temperature);

        appendUserMessage(message);

        StructuredResponse response;
        if (intelligenceLevel == IntelligenceLevel.FAST) {
            String modelToUse = explicitModel != null ? explicitModel : ModelConstants.GPT_4_1_MINI;
            response = agent.thinkFast(modelToUse, message, memoryDataset, new ArrayList<>(history), userId, temperature);
        } else {
            // DEEP or BALANCED
            response = agent.deepThink(explicitModel, message, memoryDataset, new ArrayList<>(history), userId, temperature);
        }

        String reply = "";
        if (response != null) {
            reply = response.getSummary();
            if (reply == null || reply.isBlank()) {
                reply = response.getRaw();
            }
        }
        
        if (reply == null || reply.isBlank()) {
            reply = "I'm sorry, I couldn't generate a response.";
        }

        appendAssistantMessage(reply);

        return reply;
    }

    private void appendUserMessage(String text) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", text);
        history.add(msg);
    }

    private void appendAssistantMessage(String text) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", text);
        history.add(msg);
    }

    private void checkAndSummarizeHistory(String userId, Double temperature) {
        if (history.size() <= maxHistorySize) {
            return;
        }
        
        // We will summarize the oldest (maxHistorySize / 2) messages, keeping the rest.
        int numberToSummarize = Math.max(1, history.size() / 2);
        
        List<Map<String, String>> oldestChunk = new ArrayList<>(history.subList(0, numberToSummarize));
        
        StringBuilder contextBuilder = new StringBuilder();
        for (Map<String, String> msg : oldestChunk) {
            contextBuilder.append(msg.get("role").toUpperCase()).append(": ").append(msg.get("content")).append("\n\n");
        }
        
        String summaryPrompt = "Please read the following conversation snippet and write a concise summary of the key points, " +
                "topics discussed, and any important context. This summary will be used as long-term memory for an AI assistant. " +
                "Keep it brief but informative.\n\nConversation:\n" + contextBuilder.toString();
        
        // Always use FAST mode for summarization to save time/tokens.
        StructuredResponse summaryResponse = agent.thinkFast(ModelConstants.GPT_4_1_MINI, summaryPrompt, new HashMap<>(), new ArrayList<>(), userId, temperature);
        
        String summaryText = summaryResponse != null && summaryResponse.getSummary() != null 
                ? summaryResponse.getSummary() 
                : "Previous conversation context summarized.";
        
        // Remove the oldest messages
        history.subList(0, numberToSummarize).clear();
        
        // Ensure any existing initial system summary is either updated or prepended
        if (!history.isEmpty() && "system".equals(history.get(0).get("role"))) {
            String existingSummary = history.get(0).get("content");
            history.get(0).put("content", existingSummary + "\n\nAdditional Summary: " + summaryText);
        } else {
            Map<String, String> newSysMsg = new HashMap<>();
            newSysMsg.put("role", "system");
            newSysMsg.put("content", "Summary of earlier conversation: " + summaryText);
            history.add(0, newSysMsg);
        }
    }
}
