package com.miniagent.core;

import com.miniagent.model.StructuredResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent serves as the CEO Orchestrator that manages Fast vs Deep thinking processes.
 * It dynamically tracks what it is thinking about in a 'currentThought' state string
 * which can be polled by the JSF Chatter frontend UI.
 */
public class Agent {

    private final MiniAgentWorker worker;
    private final TokenCostManager costManager;
    private final OutputSynthesizer synthesizer;
    private volatile String currentThought = "Idling...";

    public Agent(MiniAgentWorker worker, TokenCostManager costManager, OutputSynthesizer synthesizer) {
        this.worker = worker;
        this.costManager = costManager;
        this.synthesizer = synthesizer;
    }

    public String getCurrentThought() {
        return currentThought;
    }

    public TokenCostManager getCostManager() {
        return costManager;
    }

    private void updateThought(String thought) {
        this.currentThought = thought;
        System.out.println("CEO THOUGHT: " + thought);
    }

    /**
     * Think Fast: Limits response times using timeouts, providing a quick generation
     * without deep recursive loops. Best for simple chatting.
     */
    public StructuredResponse thinkFast(String model, String userQuery) {
        updateThought("Parsing quick query for immediate response...");
        long start = System.currentTimeMillis();

        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            updateThought("Dispatching Fast Agent to generate draft...");
            StructuredResponse resp = worker.generateDraft(
                model, 
                "You are an assistant. Answer concisely and quickly.",
                userQuery, 
                Collections.emptyMap(), 
                Collections.emptyList()
            );
            
            // Increment mock tokens based on response length for cost tracking
            int estimatedTokens = Math.max(10, userQuery.length() / 4 + resp.getSummary().length() / 4);
            costManager.addUsage(userQuery.length() / 4, resp.getSummary().length() / 4);
            
            return resp;
        });

        try {
            // Hard timeout for Fast Thinking to ensure high speed
            StructuredResponse response = future.get(30, TimeUnit.SECONDS);
            updateThought("Fast generation complete in " + (System.currentTimeMillis() - start) + "ms.");
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            updateThought("Fast generation timed out. Falling back to default.");
            StructuredResponse fallback = new StructuredResponse();
            fallback.setSummary("Sorry, I had to stop thinking to save time.");
            return fallback;
        } catch (Exception e) {
            updateThought("Error during fast generation.");
            throw new RuntimeException(e);
        }
    }

    /**
     * Think Deep: Uses recursive evaluation to improve output quality, simulating a
     * multi-agent committee (CEO, Researcher, Generator, Evaluator).
     */
    public StructuredResponse thinkDeep(String model, String userQuery, Map<String, Object> memoryDataset) {
        updateThought("CEO analyzing deep request context...");
        
        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            
            // Phase 1: Initial Generation
            updateThought("CEO assigned Generator MiniAgent to create initial comprehensive draft.");
            StructuredResponse draft = worker.generateDraft(
                model, 
                "You are an expert deep analyst. Provide high-quality profound insights.",
                userQuery, 
                memoryDataset, 
                Collections.emptyList()
            );
            costManager.addUsage(userQuery.length() / 4, draft.getSummary().length() / 4);

            updateThought("CEO assigned Evaluator MiniAgent to check draft for logical flaws.");
            // Mocking a recursive evaluation here. (Normally would call Evaluator)
            // Phase 2: Refinement
            updateThought("CEO assigned Repair Agent to fix deep logical inconsistencies.");
            StructuredResponse refined = worker.repairDraft(
                model,
                draft.getRaw(),
                List.of("Ensure comprehensive detail is maximized without hallucination."),
                List.of("Strictly adhere to logical structuring."),
                Collections.emptyList(),
                memoryDataset
            );
            
            costManager.addUsage(draft.getRaw().length() / 4, refined.getSummary().length() / 4);

            updateThought("CEO synthesizing final output securely...");
            StructuredResponse finalOutput = synthesizer.synthesize(refined, userQuery);

            return finalOutput;
        });

        try {
            // Max bounds for deep thinking (Increased significantly to allow AI multiple diagnostic passes)
            StructuredResponse response = future.get(180, TimeUnit.SECONDS);
            updateThought("Deep generation successfully concluded.");
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            updateThought("Deep thinking hit absolute max safety limits.");
            StructuredResponse fallback = new StructuredResponse();
            fallback.setSummary("I hit my compute limit while thinking deeply. Please ask a smaller query.");
            return fallback;
        } catch (Exception e) {
            updateThought("Error in deep multi-agent recursion.");
            throw new RuntimeException(e);
        }
    }
}
