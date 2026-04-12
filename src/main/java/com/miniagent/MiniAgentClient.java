package com.miniagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.config.AgentConfig;
import com.miniagent.core.MiniAgentEvaluator;
import com.miniagent.core.MiniAgentWorker;
import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

import java.util.List;
import java.util.Map;

/**
 * MiniAgentClient is the overarching facade for the standalone Agent Framework.
 * <p>
 * It integrates the configuration, networking clients, worker, and multi-dimensional
 * evaluator into a cohesive loop. The user simply calls 'executeTaskWithAutomaticRepair' 
 * to handle the entire AI generative-evaluative-repair cycle securely.
 */
public class MiniAgentClient {

    private final AgentConfig config;
    private final MiniAgentWorker worker;
    private final MiniAgentEvaluator evaluator;

    private static final int DEFAULT_MAX_LOOPS = 3;

    /**
     * Instantiates the client based on provided configuration.
     * 
     * @param config Configuration parameters holding keys and target models
     */
    public MiniAgentClient(AgentConfig config) {
        this.config = config;

        // Utilizing Jackson for robust JSON serialization/deserialization internally
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        OpenAiHttpClient openAiHttpClient = new OpenAiHttpClient(config, mapper);
        GeminiHttpClient geminiHttpClient = new GeminiHttpClient(config, mapper);
        PromptFactory promptFactory = new PromptFactory();
        
        com.miniagent.api.ClaudeHttpClient claudeHttpClient = new com.miniagent.api.ClaudeHttpClient(config);
        
        this.worker = new MiniAgentWorker(openAiHttpClient, geminiHttpClient, claudeHttpClient, promptFactory, mapper);
        this.evaluator = new MiniAgentEvaluator(openAiHttpClient, geminiHttpClient, promptFactory);
    }

    /**
     * Exposes the underlying generative worker for simple one-shot tasks
     * where full critic-based recursive repair isn't necessary.
     * 
     * @return the generative worker instance
     */
    public MiniAgentWorker getWorker() {
        return worker;
    }

    /**
     * Exposes the evaluator for tasks that simply need a standalone grade.
     */
    public MiniAgentEvaluator getEvaluator() {
        return evaluator;
    }

    /**
     * The primary entrypoint. Orchestrates generation, then iteratively evaluates 
     * and strictly repairs the drafting based on multi-dimensional scores.
     * 
     * @param domainContext The persona/role of the AI.
     * @param taskInstructions The immediate core goal for generation.
     * @param dataset Mapped background facts.
     * @param liveInjections Instructions forced midway.
     * @param rigidRules Rules the evaluator checks against continuously.
     * @return The final validated StructuredResponse containing the summary, convo, and reasoning.
     */
    public StructuredResponse executeTaskWithAutomaticRepair(
            String domainContext,
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<String> rigidRules
    ) {
        // Step 1: Initial Draft Generation (Worker Phase)
        // Uses the primary OpenAI model configured in AgentConfig
        StructuredResponse currentDraft = worker.generateDraft(
                config.getDefaultOpenaiModel(),
                domainContext,
                taskInstructions,
                dataset,
                liveInjections,
                java.util.Collections.emptyList()
        );

        int loops = 0;
        EvaluationResult lastEvaluation = null;

        // Step 2: Evaluation-Repair Loop
        while (loops < DEFAULT_MAX_LOOPS) {
            // Evaluate using Gemini natively for independent rigorous fact-checking
            lastEvaluation = evaluator.evaluateDraft(
                    config.getDefaultGeminiModel(),
                    true, // true = force Gemini for evaluation
                    currentDraft.getSummary(),
                    rigidRules,
                    dataset,
                    liveInjections,
                    java.util.Collections.emptyList()
            );

            // Step 3: Check Halting Conditions
            if (lastEvaluation.isPass() && !lastEvaluation.hasCriticalFailure()) {
                // If it passes cleanly, break early and return
                break;
            }

            // Step 4: Repair (Prompter + Worker phase)
            // It feeds specific multi-dimensional fixes back into the worker
            currentDraft = worker.repairDraft(
                    config.getDefaultOpenaiModel(),
                    currentDraft.getSummary(),
                    lastEvaluation.getFactualityFixes(),
                    lastEvaluation.getStructureFixes(),
                    lastEvaluation.getMissingInstructions(),
                    dataset
            );

            loops++;
        }

        // Post-loop: Validate if we still failed heavily after max loops and escalate!
        if (lastEvaluation != null && lastEvaluation.hasCriticalFailure()) {
            // Failsafe Escalation Phase: If generation refuses to fix itself, escalate to Topmost Allowed Model
            String topmostModel = config.getTopmostAllowedModel();
            System.out.println("MiniAgent Framework: Repair loops exceeded. Escalating to TOPMOST MODEL: " + topmostModel);
            
            currentDraft = worker.repairDraft(
                    topmostModel,
                    currentDraft.getSummary(),
                    lastEvaluation.getFactualityFixes(),
                    lastEvaluation.getStructureFixes(),
                    lastEvaluation.getMissingInstructions(),
                    dataset
            );
        }

        // Output final state regardless (the framework guarantees the JSON syntax is correct)
        return currentDraft;
    }
}
