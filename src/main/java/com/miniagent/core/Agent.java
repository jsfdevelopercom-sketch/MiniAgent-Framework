package com.miniagent.core;

import com.miniagent.model.StructuredResponse;
import com.miniagent.model.EvaluationResult;
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
    private final MiniAgentEvaluator evaluator;
    private final TokenCostManager costManager;
    private final OutputSynthesizer synthesizer;
    private volatile String currentThought = "Idling...";

    public Agent(MiniAgentWorker worker, MiniAgentEvaluator evaluator, TokenCostManager costManager, OutputSynthesizer synthesizer) {
        this.worker = worker;
        this.evaluator = evaluator;
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
    public StructuredResponse thinkFast(String model, String userQuery, Map<String, Object> memoryDataset, List<Map<String, String>> history, String userId, Double temperature) {
        updateThought("Parsing quick query for immediate response...");
        long start = System.currentTimeMillis();

        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            updateThought("Dispatching Fast Agent to generate draft...");
            String personaModifier = "";
            if (temperature != null) {
                if (temperature <= 0.3) personaModifier = " Output MUST be exceptionally professional, structured, and clinical.";
                else if (temperature >= 1.2) personaModifier = " Output MUST be playful, cheeky, risky, and highly experimental depending on context.";
            }

            StructuredResponse resp = worker.generateDraft(
                model, 
                "You are an assistant. Answer concisely and quickly." + personaModifier,
                userQuery, 
                memoryDataset, 
                Collections.emptyList(),
                history,
                temperature
            );
            // Increment mock tokens based on response length for cost tracking
            int estimatedTokens = Math.max(10, userQuery.length() / 4 + resp.getSummary().length() / 4);
            costManager.addUsage(userId, userQuery.length() / 4, resp.getSummary().length() / 4);
            
            updateThought("Synthesizing fast draft visually into UI schema...");
            return synthesizer.synthesize(resp, userQuery, "gpt-4o-mini");
        });

        try {
            // Hard timeout for Fast Thinking appropriately bumped for Synthesizer double-hops
            StructuredResponse response = future.get(60, TimeUnit.SECONDS);
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
     * This incorporates a genuine Reflection loop where the Critic's errors are passed back.
     */
    public StructuredResponse thinkDeep(String model, String userQuery, Map<String, Object> memoryDataset, List<Map<String, String>> history, String userId, Double temperature) {
        updateThought("CEO analyzing deep request context...");
        
        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            
            // Tri-Model Routing Implementation (The Intelligence Matrix) 
            String generatorModel = model;
            String criticModel = model;
            String synthesizerModel = model;
            
            if ("mixed".equalsIgnoreCase(model)) {
                generatorModel = "gemini-3.1-pro-preview"; // Native Gemini for massive content drafting
                criticModel = "claude-sonnet-4-6"; // Native Claude for rigorous logical evaluation
                synthesizerModel = "gpt-4o"; // Native GPT for flawless UI/UX Markdown mapping
            }

            String personaModifier = "";
            if (temperature != null) {
                if (temperature <= 0.3) personaModifier = " Output MUST be exceptionally professional, structured, and clinical.";
                else if (temperature >= 1.2) personaModifier = " Output MUST be playful, cheeky, risky, and highly experimental depending on context.";
            }

            // Phase 1: Initial Generation
            updateThought("CEO assigned Generator MiniAgent (" + generatorModel + ") to create initial comprehensive draft.");
            StructuredResponse draft = worker.generateDraft(
                generatorModel, 
                "You are an expert deep analyst. Provide high-quality profound insights." + personaModifier,
                userQuery, 
                memoryDataset, 
                Collections.emptyList(),
                history,
                temperature
            );
            costManager.addUsage(userId, userQuery.length() / 4, draft.getSummary().length() / 4);

            // Phase 2: Active Reflection (Agent-Critic Iteration)
            int loopCount = 0;
            int maxLoops = 2; // Strict safety threshold preventing infinite LLM loop token bleeding
            StructuredResponse currentDraft = draft;

            while (loopCount < maxLoops) {
                updateThought("CEO assigned Evaluator Critic Agent (" + criticModel + " | Pass " + (loopCount + 1) + ") to check draft for logical flaws.");
                
                EvaluationResult eval = evaluator.evaluateDraft(
                        criticModel,
                        criticModel != null && criticModel.toLowerCase().contains("gemini"),
                        currentDraft.getSummary(),
                        List.of("Ensure comprehensive detail is maximized without hallucination.", "Strictly adhere to logical structuring."),
                        memoryDataset,
                        Collections.emptyList(),
                        history
                );
                
                // Mathematical gating logic: If it securely passes or scores are immensely high, break natively!
                if (eval.isPass() || (eval.getFactualityScore() >= 90 && eval.getStructureScore() >= 90)) {
                    updateThought("Critic approved the output bounds. Breaking Reflexion Loop natively.");
                    break;
                }
                
                updateThought("Critic discovered conceptual flaws. CEO dispatching Repair Agent! (Pass " + (loopCount + 1) + ")");
                currentDraft = worker.repairDraft(
                    generatorModel,
                    currentDraft.getRaw(),
                    eval.getFactualityFixes(),
                    eval.getStructureFixes(),
                    eval.getMissingInstructions(),
                    memoryDataset
                );
                
                costManager.addUsage(userId, currentDraft.getRaw().length() / 4, currentDraft.getSummary().length() / 4);
                loopCount++;
            }

            updateThought("CEO synthesizing final output natively via " + synthesizerModel + "...");
            StructuredResponse finalOutput = synthesizer.synthesize(currentDraft, userQuery, synthesizerModel);

            return finalOutput;
        });

        try {
            // Max bounds for deep thinking strictly expanded 
            StructuredResponse response = future.get(240, TimeUnit.SECONDS);
            updateThought("Deep generation Reflexion logic successfully concluded.");
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            updateThought("Deep thinking hit absolute max safety limits.");
            StructuredResponse fallback = new StructuredResponse();
            fallback.setSummary("I hit my compute limit while recursively reviewing my output. Please try a simpler metric.");
            return fallback;
        } catch (Exception e) {
            updateThought("Error in deep multi-agent recursion.");
            throw new RuntimeException(e);
        }
    }
}
