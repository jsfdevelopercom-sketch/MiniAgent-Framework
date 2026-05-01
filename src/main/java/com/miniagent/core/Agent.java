package com.miniagent.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Set;
import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;
import com.miniagent.trace.AgentTraceData;
import com.miniagent.trace.AgentTraceEventType;
import com.miniagent.trace.AgentTraceLogger;
import com.miniagent.trace.NoOpAgentTraceLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent is the CEO Orchestrator for MiniAgent.
 *
 * Responsibilities:
 * - Fast response path for simple replies.
 * - DeepThink response path for classifier -> model route -> generate ->
 * evaluate -> repair loop.
 * - Runtime tracing.
 * - Stage-aware token/cost accounting.
 * - Safe recovery through SafeThoughtExecutor and ThoughtRecoveryPolicy.
 *
 * This class should not contain provider-specific HTTP logic.
 * That belongs inside MiniAgentWorker, MiniAgentEvaluator, and the HTTP
 * clients.
 */
public class Agent {

    public static boolean VERBOSE_LOGGING = true;

    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String FAST_RUN_ID_PREFIX = "fast-";
    private static final String DEEP_RUN_ID_PREFIX = "deep-";

    private final MiniAgentWorker worker;
    private final MiniAgentEvaluator evaluator;
    private final TokenCostManager costManager;
    private final OutputSynthesizer synthesizer;

    private final TaskClassifier taskClassifier;
    private final ModelRouter modelRouter;
    private final StopPolicy stopPolicy;

    private final AgentTraceLogger traceLogger;
    private final SafeThoughtExecutor thoughtExecutor;
    private final ThoughtRecoveryPolicy thoughtRecoveryPolicy;
    private final MiniAgentTools tools;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String currentThought = "Idling...";

    public Agent(
            MiniAgentWorker worker,
            MiniAgentEvaluator evaluator,
            TokenCostManager costManager,
            OutputSynthesizer synthesizer,
            TaskClassifier taskClassifier,
            ModelRouter modelRouter,
            StopPolicy stopPolicy,
            AgentTraceLogger traceLogger,
            SafeThoughtExecutor thoughtExecutor,
            ThoughtRecoveryPolicy thoughtRecoveryPolicy,
            MiniAgentTools tools) {
        if (worker == null) {
            throw new IllegalArgumentException("MiniAgentWorker cannot be null.");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("MiniAgentEvaluator cannot be null.");
        }
        if (costManager == null) {
            throw new IllegalArgumentException("TokenCostManager cannot be null.");
        }
        if (synthesizer == null) {
            throw new IllegalArgumentException("OutputSynthesizer cannot be null.");
        }
        if (taskClassifier == null) {
            throw new IllegalArgumentException("TaskClassifier cannot be null.");
        }
        if (modelRouter == null) {
            throw new IllegalArgumentException("ModelRouter cannot be null.");
        }
        if (stopPolicy == null) {
            throw new IllegalArgumentException("StopPolicy cannot be null.");
        }

        this.worker = worker;
        this.evaluator = evaluator;
        this.costManager = costManager;
        this.synthesizer = synthesizer;
        this.taskClassifier = taskClassifier;
        this.modelRouter = modelRouter;
        this.stopPolicy = stopPolicy;
        this.traceLogger = traceLogger == null ? new NoOpAgentTraceLogger() : traceLogger;

        this.thoughtExecutor = thoughtExecutor == null
                ? new SafeThoughtExecutor(
                        worker,
                        evaluator,
                        new ModelFallbackPolicy(),
                        this.traceLogger)
                : thoughtExecutor;

        this.thoughtRecoveryPolicy = thoughtRecoveryPolicy == null
                ? new ThoughtRecoveryPolicy()
                : thoughtRecoveryPolicy;

        this.tools = tools == null ? MiniAgentTools.fromEnvironment() : tools;
    }

    public Agent(
            MiniAgentWorker worker,
            MiniAgentEvaluator evaluator,
            TokenCostManager costManager,
            OutputSynthesizer synthesizer,
            TaskClassifier taskClassifier,
            ModelRouter modelRouter,
            StopPolicy stopPolicy,
            AgentTraceLogger traceLogger) {
        this(
                worker,
                evaluator,
                costManager,
                synthesizer,
                taskClassifier,
                modelRouter,
                stopPolicy,
                traceLogger,
                null,
                null,
                null);
    }
private boolean isLargeCodeGenerationRequest(
        String userQuery,
        TaskClassifier.TaskClassification classification
) {
    String q = userQuery == null ? "" : userQuery.toLowerCase();

    boolean classifiedAsCode =
            classification != null &&
                    (classification.taskType == TaskClassifier.TaskType.CODE_GENERATION ||
                            classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING ||
                            classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN);

    boolean userExplicitlyWantsLargeCode =
            q.contains("complete code") ||
                    q.contains("full code") ||
                    q.contains("entire code") ||
                    q.contains("extremely detailed") ||
                    q.contains("elaborate") ||
                    q.contains("working code") ||
                    q.contains("must not include placeholders") ||
                    q.contains("no placeholders") ||
                    q.contains("like vscode") ||
                    q.contains("visual studio code") ||
                    q.contains("text editor") ||
                    q.contains("production ready") ||
                    q.contains("single file") ||
                    q.contains("html js") ||
                    q.contains("html javascript");

    return classifiedAsCode && userExplicitlyWantsLargeCode;
}
    public String getCurrentThought() {
        return currentThought;
    }

    public TokenCostManager getCostManager() {
        return costManager;
    }

    public void updateThought(String thought) {
        String safeThought = thought == null || thought.isBlank() ? "Working..." : thought.trim();
        this.currentThought = safeThought;
        System.out.println("CEO THOUGHT: " + safeThought);
    }

    /**
     * Fast path for simple/chatty work.
     *
     * This path intentionally avoids recursive critic/repair loops.
     */
    /**
     * Executes a low-latency, single-pass query without complex evaluation loops.
     * 
     * Deep Insight:
     * This method bypasses the DraftSanityValidator and Critic stages. It's designed
     * for trivial tasks (e.g., "What is the time complexity of quicksort?") where the overhead
     * of a full deepThink pipeline would be computationally wasteful and slow.
     */
    public StructuredResponse thinkFast(
            String model,
            String userQuery,
            Map<String, Object> memoryDataset,
            List<Map<String, String>> history,
            String userId,
            Double temperature) {
        String runId = FAST_RUN_ID_PREFIX + UUID.randomUUID();
        String safeUserId = safeUserId(userId);
        Map<String, Object> safeDataset = memoryDataset == null ? new HashMap<>() : memoryDataset;
        List<Map<String, String>> safeHistory = history == null ? Collections.emptyList() : history;
        String safeQuery = userQuery == null ? "" : userQuery;

        updateThought("Parsing quick query for immediate response...");
        long start = System.currentTimeMillis();

        traceLogger.runStarted(
                runId,
                safeUserId,
                preview(safeQuery, 1000),
                mergeMaps(
                        AgentTraceData.datasetSummary(safeDataset),
                        AgentTraceData.historySummary(safeHistory),
                        Map.of("mode", "fast", "requestedModel", model == null ? "" : model)));

        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                updateThought("Dispatching Fast Agent to generate draft...");

                String personaModifier = buildTemperaturePersonaModifier(temperature);
                String generatorModel = model == null || model.isBlank() ? ModelConstants.GPT_4_1_MINI : model.trim();

                long generationStart = System.currentTimeMillis();

                StructuredResponse draft = worker.generateDraft(
                        generatorModel,
                        "You are an assistant. Answer concisely and quickly." + personaModifier,
                        safeQuery,
                        safeDataset,
                        Collections.emptyList(),
                        safeHistory,
                        temperature).normalize();

                recordStageUsage(
                        safeUserId,
                        runId,
                        "fast-generation",
                        generatorModel,
                        safeQuery,
                        draft.getSummary());

                traceLogger.modelEvent(
                        runId,
                        safeUserId,
                        AgentTraceEventType.GENERATION_FINISHED,
                        "fast-generation",
                        generatorModel,
                        "Fast draft generated.",
                        System.currentTimeMillis() - generationStart,
                        estimateTokens(safeQuery),
                        estimateTokens(draft.getSummary()),
                        AgentTraceData.draft(draft, 1200));

                updateThought("Synthesizing fast draft visually into UI schema...");

                long synthesisStart = System.currentTimeMillis();

                StructuredResponse synthesized = synthesizer.synthesize(
                        draft,
                        safeQuery,
                        ModelConstants.GPT_4_1_MINI).normalize();

                recordStageUsage(
                        safeUserId,
                        runId,
                        "fast-synthesis",
                        ModelConstants.GPT_4_1_MINI,
                        draft.getSummary(),
                        synthesized.getSummary());

                traceLogger.modelEvent(
                        runId,
                        safeUserId,
                        AgentTraceEventType.SYNTHESIS_FINISHED,
                        "fast-synthesis",
                        ModelConstants.GPT_4_1_MINI,
                        "Fast synthesis finished.",
                        System.currentTimeMillis() - synthesisStart,
                        estimateTokens(draft.getSummary()),
                        estimateTokens(synthesized.getSummary()),
                        AgentTraceData.draft(synthesized, 1200));

                TokenCostManager.UsageSnapshot runCost = costManager.getRunSnapshot(runId);

                traceLogger.runFinished(
                        runId,
                        safeUserId,
                        true,
                        "Fast generation complete.",
                        mergeMaps(
                                AgentTraceData.draft(synthesized, 1000),
                                costSummary(runCost),
                                Map.of("totalDurationMs", System.currentTimeMillis() - start)));

                return synthesized;
            } catch (Exception e) {
                traceLogger.error(
                        runId,
                        safeUserId,
                        "fast",
                        "Fast generation crashed.",
                        e);

                throw e;
            }
        });

        try {
            StructuredResponse response = future.get(60, TimeUnit.SECONDS);
            updateThought("Fast generation complete in " + (System.currentTimeMillis() - start) + "ms.");
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            updateThought("Fast generation timed out. Falling back to default.");

            StructuredResponse fallback = StructuredResponse.failure(
                    "Sorry, I had to stop thinking to save time.",
                    "FAST_TIMEOUT");

            traceLogger.runFinished(
                    runId,
                    safeUserId,
                    false,
                    "Fast generation timed out.",
                    mergeMaps(
                            AgentTraceData.draft(fallback, 1000),
                            Map.of("totalDurationMs", System.currentTimeMillis() - start)));

            return fallback;
        } catch (Exception e) {
            updateThought("Error during fast generation.");
            throw new RuntimeException("Fast generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * DeepThink path:
     * classify -> route -> generate -> evaluate -> repair/replan ->
     * stop/synthesize.
     */
    /**
     * The primary entry point for complex, multi-stage autonomous reasoning.
     * 
     * Deep Insight:
     * deepThink acts as the central orchestrator for the "Plan -> Think -> Critic -> Repair" loop.
     * It dynamically classifies the task difficulty, routes it to the most cost-effective model,
     * and iteratively refines the output. This ensures high-fidelity results while preventing
     * hallucinated code from reaching the user.
     */
    public StructuredResponse deepThink(
            String requestedModel,
            String userQuery,
            Map<String, Object> memoryDataset,
            List<Map<String, String>> history,
            String userId,
            Double temperature) {
        updateThought("Classifying task before deep reasoning...");

        CompletableFuture<StructuredResponse> future = CompletableFuture.supplyAsync(() -> {
            String runId = DEEP_RUN_ID_PREFIX + UUID.randomUUID();
            String safeUserId = safeUserId(userId);
            String safeQuery = userQuery == null ? "" : userQuery;
            Map<String, Object> safeDataset = memoryDataset == null ? new HashMap<>() : memoryDataset;
            List<Map<String, String>> safeHistory = history == null ? Collections.emptyList() : history;
            RepairMemoryCompressor repairMemory = new RepairMemoryCompressor();

            long runStart = System.currentTimeMillis();

            traceLogger.runStarted(
                    runId,
                    safeUserId,
                    preview(safeQuery, 1000),
                    mergeMaps(
                            AgentTraceData.datasetSummary(safeDataset),
                            AgentTraceData.historySummary(safeHistory),
                            Map.of("mode", "deep", "requestedModel", requestedModel == null ? "" : requestedModel)));

            try {
                if (safeQuery.isBlank()) {
                    StructuredResponse empty = StructuredResponse.failure(
                            "I cannot run DeepThink on an empty task.",
                            "EMPTY_TASK");

                    traceLogger.runFinished(
                            runId,
                            safeUserId,
                            false,
                            "Empty task.",
                            mergeMaps(
                                    AgentTraceData.draft(empty, 1000),
                                    Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                    return empty;
                }

                long classificationStart = System.currentTimeMillis();

                traceLogger.stage(
                        runId,
                        safeUserId,
                        AgentTraceEventType.CLASSIFICATION_STARTED,
                        "classification",
                        "Task classification started.",
                        Map.of("requestedModel", requestedModel == null ? "" : requestedModel));

                TaskClassifier.TaskClassification classification = taskClassifier.classify(
                        safeQuery,
                        TaskClassifier.ClassifierProvider.AUTO);

                recordStageUsage(
                        safeUserId,
                        runId,
                        "classification",
                        classification.providerUsed,
                        safeQuery,
                        String.valueOf(classification));

                traceLogger.modelEvent(
                        runId,
                        safeUserId,
                        AgentTraceEventType.CLASSIFICATION_FINISHED,
                        "classification",
                        classification.providerUsed,
                        "Task classification finished.",
                        System.currentTimeMillis() - classificationStart,
                        estimateTokens(safeQuery),
                        estimateTokens(String.valueOf(classification)),
                        AgentTraceData.classification(classification));
                if (VERBOSE_LOGGING) {
                    System.out.println("\n[VERBOSE] *** CLASSIFICATION: " + classification.taskType + " | PIPELINE: " + classification.recommendedPipeline + " ***\n");
                }
                updateThought(
                        "Task classified as " +
                                classification.taskType +
                                " / " +
                                classification.difficulty +
                                " using pipeline " +
                                classification.recommendedPipeline);

                if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.REFUSE) {
                    StructuredResponse refused = StructuredResponse.failure(
                            "I cannot safely complete this task.",
                            "REFUSE");
                    refused.setThought_process("TaskClassifier routed the request to REFUSE.");

                    TokenCostManager.UsageSnapshot runCost = costManager.getRunSnapshot(runId);

                    traceLogger.runFinished(
                            runId,
                            safeUserId,
                            false,
                            "TaskClassifier routed to REFUSE.",
                            mergeMaps(
                                    AgentTraceData.classification(classification),
                                    AgentTraceData.draft(refused, 1000),
                                    costSummary(runCost),
                                    Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                    return refused;
                }

                if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.ASK_USER_CLARIFICATION) {
                System.out.println("USER CLARIFICATION NEEDED");
                    StructuredResponse clarify = StructuredResponse.fromSummary(
                            "I need one or two more details before I can safely complete this task.");
                    clarify.setThought_process("TaskClassifier routed the request to ASK_USER_CLARIFICATION.");
                    clarify.setRaw("ASK_USER_CLARIFICATION");

                    TokenCostManager.UsageSnapshot runCost = costManager.getRunSnapshot(runId);

                    traceLogger.runFinished(
                            runId,
                            safeUserId,
                            false,
                            "TaskClassifier routed to ASK_USER_CLARIFICATION.",
                            mergeMaps(
                                    AgentTraceData.classification(classification),
                                    AgentTraceData.draft(clarify, 1000),
                                    costSummary(runCost),
                                    Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                    return clarify;
                }

                ModelRoute route = modelRouter.route(classification, requestedModel);
                
                if (VERBOSE_LOGGING) {
                    System.out.println("\n[VERBOSE] *** ROUTER ASSIGNMENTS ***");
                    System.out.println("Generator: " + route.getGeneratorModel());
                    System.out.println("Critic: " + route.getCriticModel());
                    System.out.println("Repair: " + route.getRepairModel());
                    System.out.println("Synthesizer: " + route.getSynthesizerModel());
                    System.out.println("------------------------------------\n");
                }

                traceLogger.stage(
                        runId,
                        safeUserId,
                        AgentTraceEventType.MODEL_ROUTE_SELECTED,
                        "routing",
                        "Model route selected.",
                        AgentTraceData.modelRoute(route));

                AgentRunPlan plan = AgentRunPlan.from(classification, route);

                traceLogger.stage(
                        runId,
                        safeUserId,
                        AgentTraceEventType.RUN_PLAN_CREATED,
                        "planning",
                        "Agent run plan created.",
                        AgentTraceData.runPlan(plan));

                AgentRunState state = new AgentRunState(
                        runId,
                        safeUserId,
                        safeQuery);
System.out.println("MODEL ROUTE "+"Model route selected: generator=" +
                        route.getGeneratorModel() +
                        ", critic=" +
                        route.getCriticModel() +
                        ", repair=" +
                        route.getRepairModel() +
                        ", synthesizer=" +
                        route.getSynthesizerModel());
                        
                updateThought("Model route selected: generator=" +
                        route.getGeneratorModel() +
                        ", critic=" +
                        route.getCriticModel() +
                        ", repair=" +
                        route.getRepairModel() +
                        ", synthesizer=" +
                        route.getSynthesizerModel());

                if (shouldRunToolObservationPhase(classification, plan)) {
                    Map<String, Object> toolObservations = runToolObservationPhase(
                            runId,
                            safeUserId,
                            safeQuery,
                            safeDataset,
                            safeHistory,
                            classification,
                            route,
                            plan,
                            runStart,
                            temperature);

                    if (!toolObservations.isEmpty()) {
                        safeDataset = new HashMap<>(safeDataset);
                        safeDataset.put("__tool_observations", toolObservations);
                    }
                }

                while (true) {
                    StopPolicy.StopDecision beforeDecision = stopPolicy.decide(plan, state);

                    traceLogger.stage(
                            runId,
                            safeUserId,
                            AgentTraceEventType.STOP_DECISION,
                            "stop-policy",
                            "Stop policy checked before attempt.",
                            mergeMaps(
                                    AgentTraceData.stopDecision(beforeDecision),
                                    AgentTraceData.runState(state),
                                    runCostMap(runId)));

                    if (!beforeDecision.shouldContinue()) {
                        StructuredResponse finished = finishFromStopDecision(
                                safeQuery,
                                state,
                                route,
                                beforeDecision);

                        traceLogger.runFinished(
                                runId,
                                safeUserId,
                                beforeDecision.getAction() == StopPolicy.StopDecision.Action.SUCCESS,
                                beforeDecision.getReason(),
                                mergeMaps(
                                        AgentTraceData.runState(state),
                                        AgentTraceData.draft(finished, 1200),
                                        runCostMap(runId),
                                        Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                        return finished;
                    }

                    int nextAttempt = state.getAttemptNumber() + 1;

                    traceLogger.stage(
                            runId,
                            safeUserId,
                            AgentTraceEventType.ATTEMPT_STARTED,
                            "attempt",
                            "Attempt " + nextAttempt + " started.",
                            mergeMaps(
                                    AgentTraceData.runState(state),
                                    runCostMap(runId)));

                    StructuredResponse candidateDraft;

                    if (state.getAttemptNumber() == 0) {
                        candidateDraft = runInitialGenerationStage(
                                runId,
                                safeUserId,
                                safeQuery,
                                safeDataset,
                                safeHistory,
                                classification,
                                route,
                                plan,
                                repairMemory,
                                state,
                                runStart,
                                nextAttempt,
                                temperature);

                        if (candidateDraft == null) {
                            return buildFailedNoDraftResponse(runId, safeUserId, runStart);
                        }
                    } else {
                        candidateDraft = runRepairOrReplanStage(
                                runId,
                                safeUserId,
                                safeQuery,
                                safeDataset,
                                classification,
                                route,
                                plan,
                                repairMemory,
                                state,
                                runStart,
                                nextAttempt,
                                temperature);

                        if (candidateDraft == null) {
                            return finalizePartialAfterRecoveryFailure(
                                    safeQuery,
                                    state,
                                    route,
                                    ThoughtRecoveryDecision
                                            .acceptPartial("Repair/replan failed without a usable candidate draft."),
                                    runId,
                                    safeUserId,
                                    runStart);
                        }
                    }

                    EvaluationResult eval = runEvaluationStage(
                            safeQuery,
                            runId,
                            safeUserId,
                            safeDataset,
                            classification,
                            route,
                            repairMemory,
                            candidateDraft,
                            plan,
                            state,
                            nextAttempt);

                    repairMemory.ingestEvaluation(eval, nextAttempt);
                    state.recordDraftAndEvaluation(candidateDraft, eval);

                    StopPolicy.StopDecision afterDecision = stopPolicy.decide(plan, state);

                    traceLogger.stage(
                            runId,
                            safeUserId,
                            AgentTraceEventType.STOP_DECISION,
                            "stop-policy",
                            "Stop policy checked after attempt.",
                            mergeMaps(
                                    AgentTraceData.stopDecision(afterDecision),
                                    AgentTraceData.runState(state),
                                    runCostMap(runId),
                                    Map.of("repairMemory", repairMemory.renderForRepair())));

                    if (afterDecision.getAction() == StopPolicy.StopDecision.Action.SUCCESS) {
                        state.markSuccess(afterDecision.getReason());

                        StructuredResponse finished = finishFromStopDecision(
                                safeQuery,
                                state,
                                route,
                                afterDecision);

                        traceLogger.runFinished(
                                runId,
                                safeUserId,
                                true,
                                afterDecision.getReason(),
                                mergeMaps(
                                        AgentTraceData.runState(state),
                                        AgentTraceData.draft(finished, 1200),
                                        runCostMap(runId),
                                        Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                        return finished;
                    }

                    if (afterDecision.getAction() == StopPolicy.StopDecision.Action.PARTIAL_STOP ||
                            afterDecision.getAction() == StopPolicy.StopDecision.Action.HARD_STOP) {
                        state.markStopped(afterDecision.getReason());

                        StructuredResponse finished = finishFromStopDecision(
                                safeQuery,
                                state,
                                route,
                                afterDecision);

                        traceLogger.runFinished(
                                runId,
                                safeUserId,
                                false,
                                afterDecision.getReason(),
                                mergeMaps(
                                        AgentTraceData.runState(state),
                                        AgentTraceData.draft(finished, 1200),
                                        runCostMap(runId),
                                        Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                        return finished;
                    }
                }
            } catch (Exception e) {
                traceLogger.error(
                        runId,
                        safeUserId,
                        "deepThink",
                        "DeepThink crashed.",
                        e);

                traceLogger.runFinished(
                        runId,
                        safeUserId,
                        false,
                        "DeepThink crashed: " + e.getMessage(),
                        mergeMaps(
                                runCostMap(runId),
                                Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

                throw e;
            }
        });

        try {
            StructuredResponse response = future.get(300, TimeUnit.SECONDS);
            updateThought("DeepThink completed.");
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            updateThought("DeepThink hit absolute timeout.");

            return StructuredResponse.failure(
                    "I hit the maximum deep-thinking time limit. Please try a smaller task or reduce the requested scope.",
                    "TIMEOUT");
        } catch (Exception e) {
            updateThought("DeepThink crashed.");
            throw new RuntimeException("DeepThink failed: " + e.getMessage(), e);
        }
    }

    private boolean shouldRunToolObservationPhase(
            TaskClassifier.TaskClassification classification,
            AgentRunPlan plan) {
        if (classification == null || plan == null) {
            return false;
        }

        if (tools == null) {
            return false;
        }

        return plan.isEnableTools() ||
                classification.needsTools ||
                classification.needsFileAccess ||
                classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT;
    }

    private Map<String, Object> runToolObservationPhase(
            String runId,
            String safeUserId,
            String safeQuery,
            Map<String, Object> safeDataset,
            List<Map<String, String>> safeHistory,
            TaskClassifier.TaskClassification classification,
            ModelRoute route,
            AgentRunPlan plan,
            long runStart,
            Double temperature) {
        updateThought("Starting bounded tool observation phase...");

        Map<String, Object> observations = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();

        int maxSteps = toolMaxSteps();
        String plannerModel = route.getGeneratorModel();

        traceLogger.stage(
                runId,
                safeUserId,
                AgentTraceEventType.TOOL_LOOP_STARTED,
                "tools",
                "Tool observation loop started.",
                Map.of(
                        "maxSteps", maxSteps,
                        "toolManifest", tools.toolManifestForPrompt()));

        for (int step = 1; step <= maxSteps; step++) {
            updateThought("Tool planning step " + step + " of " + maxSteps + "...");

            String systemPrompt = buildToolPlannerSystemPrompt();
            String userPrompt = buildToolPlannerUserPrompt(
                    safeQuery,
                    classification,
                    safeDataset,
                    safeHistory,
                    observations,
                    step,
                    maxSteps);

            long planningStart = System.currentTimeMillis();

            StructuredResponse plannerDraft;
            try {
                plannerDraft = worker.generateDraft(
                        plannerModel,
                        systemPrompt,
                        userPrompt,
                        safeDataset == null ? Collections.emptyMap() : safeDataset,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        0.0).normalize();

                recordStageUsage(
                        safeUserId,
                        runId,
                        "tool-planning",
                        plannerModel,
                        userPrompt,
                        plannerDraft.getSummary());

                traceLogger.modelEvent(
                        runId,
                        safeUserId,
                        AgentTraceEventType.TOOL_DECISION,
                        "tools",
                        plannerModel,
                        "Tool planner produced decision.",
                        System.currentTimeMillis() - planningStart,
                        estimateTokens(userPrompt),
                        estimateTokens(plannerDraft.getSummary()),
                        AgentTraceData.draft(plannerDraft, 1200));
            } catch (Exception e) {
                traceLogger.error(
                        runId,
                        safeUserId,
                        "tools",
                        "Tool planner crashed.",
                        e);

                observations.put("tool_loop_status", "planner_crashed");
                observations.put("tool_loop_error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                break;
            }

            ToolLoopDecision decision = parseToolLoopDecision(plannerDraft);

            traceLogger.stage(
                    runId,
                    safeUserId,
                    AgentTraceEventType.TOOL_DECISION,
                    "tools",
                    "Parsed tool decision.",
                    decision.toMap());

            if (decision.status == ToolLoopStatus.FINAL) {
                observations.put("tool_loop_status", "final");
                observations.put("tool_loop_final_answer", decision.finalAnswer);
                observations.put("tool_loop_reason", decision.reason);
                break;
            }

            if (decision.status == ToolLoopStatus.NEED_MORE_INFO) {
                observations.put("tool_loop_status", "need_more_info");
                observations.put("tool_loop_reason", decision.reason);
                break;
            }

            if (decision.status != ToolLoopStatus.CALL_TOOL) {
                observations.put("tool_loop_status", "invalid_decision");
                observations.put("tool_loop_reason", decision.reason);
                break;
            }

            if (decision.toolName.isBlank()) {
                observations.put("tool_loop_status", "missing_tool_name");
                break;
            }

            if (!tools.availableToolNames().contains(decision.toolName)) {
                Map<String, Object> failedStep = new LinkedHashMap<>();
                failedStep.put("step", step);
                failedStep.put("tool", decision.toolName);
                failedStep.put("success", false);
                failedStep.put("message", "Unknown tool requested.");
                failedStep.put("availableTools", tools.availableToolNames());
                steps.add(failedStep);
                observations.put("steps", steps);
                continue;
            }

            traceLogger.stage(
                    runId,
                    safeUserId,
                    AgentTraceEventType.TOOL_CALL_STARTED,
                    "tools",
                    "Executing tool: " + decision.toolName,
                    Map.of(
                            "step", step,
                            "tool", decision.toolName,
                            "args", safeToolArgsForTrace(decision.args)));

            long toolStart = System.currentTimeMillis();
            MiniAgentTools.ToolResult toolResult = tools.execute(decision.toolName, decision.args);

            Map<String, Object> compactResult = compactToolResult(toolResult, 6000);

            Map<String, Object> stepRecord = new LinkedHashMap<>();
            stepRecord.put("step", step);
            stepRecord.put("tool", decision.toolName);
            stepRecord.put("args", safeToolArgsForTrace(decision.args));
            stepRecord.put("success", toolResult.success());
            stepRecord.put("message", toolResult.message());
            stepRecord.put("result", compactResult);

            steps.add(stepRecord);
            observations.put("steps", steps);
            observations.put("lastTool", decision.toolName);
            observations.put("lastToolSuccess", toolResult.success());
            observations.put("lastToolMessage", toolResult.message());

            traceLogger.stage(
                    runId,
                    safeUserId,
                    AgentTraceEventType.TOOL_CALL_FINISHED,
                    "tools",
                    "Tool finished: " + decision.toolName,
                    mergeMaps(
                            compactResult,
                            Map.of(
                                    "step", step,
                                    "durationMs", System.currentTimeMillis() - toolStart)));

            if (decision.stopAfterCall) {
                observations.put("tool_loop_status", "stopped_after_call");
                break;
            }
        }

        observations.putIfAbsent("tool_loop_status", "max_steps_or_complete");
        observations.put("tool_loop_step_count", steps.size());

        traceLogger.stage(
                runId,
                safeUserId,
                AgentTraceEventType.TOOL_LOOP_FINISHED,
                "tools",
                "Tool observation loop finished.",
                mergeMaps(
                        Map.of(
                                "stepCount", steps.size(),
                                "status", observations.getOrDefault("tool_loop_status", "")),
                        runCostMap(runId)));

        updateThought("Tool observation phase finished with " + steps.size() + " tool call(s).");

        return observations;
    }

    private String buildToolPlannerSystemPrompt() {
        return """
                You are MiniAgent Tool Planner.

                Your job:
                - Decide whether one tool call is needed next.
                - Return ONLY valid JSON.
                - Do not solve the full task unless enough tool observations already exist.
                - Prefer read-only tools first.
                - Do not request write/edit tools unless the user clearly asked to modify code and writes are enabled by the tool result/config.
                - Do not request command execution unless absolutely needed and command tools are enabled.
                - If enough information is gathered, return status=FINAL.
                - If required external capability is unavailable, return status=FINAL and explain the limitation.

                Available tools:
                %s

                Required JSON schema:
                {
                  "status": "CALL_TOOL | FINAL | NEED_MORE_INFO",
                  "tool": "tool_name_or_empty",
                  "args": {},
                  "reason": "short reason",
                  "stopAfterCall": false,
                  "finalAnswer": "only when status is FINAL"
                }

                JSON only.
                """
                .formatted(tools == null ? "No tools configured." : tools.toolManifestForPrompt());
    }

    private String buildToolPlannerUserPrompt(
            String safeQuery,
            TaskClassifier.TaskClassification classification,
            Map<String, Object> safeDataset,
            List<Map<String, String>> safeHistory,
            Map<String, Object> observations,
            int step,
            int maxSteps) {
        StringBuilder sb = new StringBuilder();

        sb.append("USER TASK:\n")
                .append(safeQuery == null ? "" : safeQuery)
                .append("\n\n");

        sb.append("CLASSIFICATION:\n")
                .append(classification == null ? "unknown" : AgentTraceData.classification(classification))
                .append("\n\n");

        sb.append("STEP:\n")
                .append(step)
                .append(" of ")
                .append(maxSteps)
                .append("\n\n");

        sb.append("DATASET KEYS:\n")
                .append(safeDataset == null ? "none" : safeDataset.keySet())
                .append("\n\n");

        sb.append("RECENT HISTORY SUMMARY:\n")
                .append(compactHistoryForToolPrompt(safeHistory))
                .append("\n\n");

        sb.append("OBSERVATIONS SO FAR:\n")
                .append(compactObjectForPrompt(observations, 10_000))
                .append("\n\n");

        sb.append("DECISION RULES:\n");
        sb.append(
                "- If you need to inspect files, use list_files, search_code, read_file, symbols, slice, or resolve_symbol.\n");
        sb.append("- If the needed file/path is unknown, search/list before read.\n");
        sb.append("- If observations are enough, return FINAL.\n");
        sb.append("- Make only one tool call per decision.\n");
        sb.append("- Return only JSON.\n");

        return sb.toString();
    }

    private ToolLoopDecision parseToolLoopDecision(StructuredResponse draft) {
        String text = firstNonBlank(
                draft == null ? "" : draft.getRaw(),
                draft == null ? "" : draft.getSummary());

        String json = extractJsonObject(text);

        if (json.isBlank()) {
            return ToolLoopDecision.finalAnswer(
                    "Tool planner did not return parseable JSON. Continuing without additional tools.",
                    "unparseable_tool_decision");
        }

        try {
            JsonNode root = mapper.readTree(json);

            String statusText = root.path("status").asText("FINAL").trim().toUpperCase(Locale.ROOT);
            ToolLoopStatus status = switch (statusText) {
                case "CALL_TOOL" -> ToolLoopStatus.CALL_TOOL;
                case "NEED_MORE_INFO" -> ToolLoopStatus.NEED_MORE_INFO;
                default -> ToolLoopStatus.FINAL;
            };

            String tool = root.path("tool").asText("").trim();
            String reason = root.path("reason").asText("").trim();
            String finalAnswer = root.path("finalAnswer").asText("").trim();
            boolean stopAfterCall = root.path("stopAfterCall").asBoolean(false);

            Map<String, Object> args = new LinkedHashMap<>();
            JsonNode argsNode = root.path("args");
            if (argsNode != null && argsNode.isObject()) {
                args = mapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {
                });
            }

            return new ToolLoopDecision(
                    status,
                    safeToolName(tool),
                    args,
                    reason,
                    stopAfterCall,
                    finalAnswer);
        } catch (Exception e) {
            return ToolLoopDecision.finalAnswer(
                    "Tool planner JSON parse failed: " + e.getMessage(),
                    "tool_decision_parse_failure");
        }
    }

    private Map<String, Object> compactToolResult(MiniAgentTools.ToolResult result, int maxChars) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (result == null) {
            data.put("success", false);
            data.put("message", "null tool result");
            return data;
        }

        data.put("success", result.success());
        data.put("tool", result.tool());
        data.put("message", result.message());

        Map<String, Object> raw = result.data();
        Map<String, Object> compact = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            compact.put(entry.getKey(), compactValue(entry.getValue(), maxChars));
        }

        data.put("data", compact);

        return data;
    }

    private Object compactValue(Object value, int maxChars) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            int count = 0;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= 30) {
                    out.put("_truncated", true);
                    break;
                }

                out.put(String.valueOf(entry.getKey()), compactValue(entry.getValue(), Math.max(500, maxChars / 2)));
                count++;
            }

            return out;
        }

        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            int limit = Math.min(list.size(), 30);

            for (int i = 0; i < limit; i++) {
                out.add(compactValue(list.get(i), Math.max(500, maxChars / 2)));
            }

            if (list.size() > limit) {
                out.add("...[TRUNCATED " + (list.size() - limit) + " more item(s)]");
            }

            return out;
        }

        String text = String.valueOf(value);
        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars) + "\n...[TRUNCATED]";
    }

    private Map<String, Object> safeToolArgsForTrace(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> safe = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            String key = entry.getKey();

            if (key.toLowerCase(Locale.ROOT).contains("token") ||
                    key.toLowerCase(Locale.ROOT).contains("secret") ||
                    key.toLowerCase(Locale.ROOT).contains("password")) {
                safe.put(key, "[REDACTED]");
            } else {
                safe.put(key, compactValue(entry.getValue(), 2000));
            }
        }

        return safe;
    }

    private String compactHistoryForToolPrompt(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "No history.";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 6);

        for (int i = start; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            if (msg == null) {
                continue;
            }

            String role = msg.getOrDefault("role", "unknown");
            String content = msg.getOrDefault("content", "");
            sb.append(role)
                    .append(": ")
                    .append(preview(content, 800))
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private String compactObjectForPrompt(Object value, int maxChars) {
        try {
            String json = mapper.writeValueAsString(value == null ? Map.of() : value);
            if (json.length() <= maxChars) {
                return json;
            }
            return json.substring(0, maxChars) + "...[TRUNCATED]";
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end >= start) {
            return cleaned.substring(start, end + 1);
        }

        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }

    private String safeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "";
        }

        return toolName.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
    }

    private int toolMaxSteps() {
        String value = System.getenv("MINIAGENT_TOOL_MAX_STEPS");
        if (value == null || value.isBlank()) {
            return 6;
        }

        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(20, parsed));
        } catch (NumberFormatException ignored) {
            return 6;
        }
    }

    private enum ToolLoopStatus {
        CALL_TOOL,
        FINAL,
        NEED_MORE_INFO
    }

    private static final class ToolLoopDecision {
        private final ToolLoopStatus status;
        private final String toolName;
        private final Map<String, Object> args;
        private final String reason;
        private final boolean stopAfterCall;
        private final String finalAnswer;

        private ToolLoopDecision(
                ToolLoopStatus status,
                String toolName,
                Map<String, Object> args,
                String reason,
                boolean stopAfterCall,
                String finalAnswer) {
            this.status = status == null ? ToolLoopStatus.FINAL : status;
            this.toolName = toolName == null ? "" : toolName;
            this.args = args == null ? Map.of() : new LinkedHashMap<>(args);
            this.reason = reason == null ? "" : reason;
            this.stopAfterCall = stopAfterCall;
            this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
        }

        private static ToolLoopDecision finalAnswer(String finalAnswer, String reason) {
            return new ToolLoopDecision(
                    ToolLoopStatus.FINAL,
                    "",
                    Map.of(),
                    reason,
                    false,
                    finalAnswer);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status.name());
            map.put("toolName", toolName);
            map.put("args", args);
            map.put("reason", reason);
            map.put("stopAfterCall", stopAfterCall);
            map.put("finalAnswer", finalAnswer);
            return map;
        }
    }

    private StructuredResponse runInitialGenerationStage(
            String runId,
            String safeUserId,
            String safeQuery,
            Map<String, Object> safeDataset,
            List<Map<String, String>> safeHistory,
            TaskClassifier.TaskClassification classification,
            ModelRoute route,
            AgentRunPlan plan,
            RepairMemoryCompressor repairMemory,
            AgentRunState state,
            long runStart,
            int nextAttempt,
            Double temperature) {
        updateThought("Generating first deep draft with safe fallback executor...");

        ThoughtCallResult<StructuredResponse> generationResult = thoughtExecutor.generateDraft(
                runId,
                safeUserId,
                route.getGeneratorModel(),
                buildDomainContext(classification, temperature),
                buildInitialTaskInstruction(safeQuery, classification),
                safeDataset,
                Collections.emptyList(),
                plan.isAllowFullHistory() ? safeHistory : Collections.emptyList(),
                route.getGeneratorTemperature(),
                nextAttempt);

        if (!generationResult.isSuccess()) {
            repairMemory.ingestFailures(generationResult.getFailures());

            ThoughtRecoveryDecision recoveryDecision = thoughtRecoveryPolicy.decideAfterFailure(
                    plan,
                    state,
                    generationResult.getFailures(),
                    repairMemory);

            traceLogger.warning(
                    runId,
                    safeUserId,
                    "generation-recovery",
                    "Generation failed. Recovery decision: " + recoveryDecision.getAction(),
                    Map.of(
                            "reason", recoveryDecision.getReason(),
                            "failures", generationResult.compactFailureText(),
                            "repairMemory", repairMemory.renderForRepair()));

            if (state.getBestDraft() != null) {
                finalizePartialAfterRecoveryFailure(
                        safeQuery,
                        state,
                        route,
                        recoveryDecision,
                        runId,
                        safeUserId,
                        runStart);
            }

            return null;
        }

        StructuredResponse candidateDraft = generationResult.getValue().normalize();

        if (VERBOSE_LOGGING) {
            System.out.println("\n[VERBOSE] *** MODEL SELECTED FOR INITIAL GENERATION: " + generationResult.getModelUsed().toUpperCase() + " ***");
            System.out.println("[VERBOSE] --- FIRST DRAFT ---");
            System.out.println(candidateDraft.getSummary());
            System.out.println("-----------------------------\n");
        }

        recordStageUsage(
                safeUserId,
                runId,
                "generation",
                generationResult.getModelUsed(),
                safeQuery,
                candidateDraft.getSummary());

        traceLogger.stage(
                runId,
                safeUserId,
                AgentTraceEventType.GENERATION_FINISHED,
                "generation",
                "Initial draft generated successfully.",
                mergeMaps(
                        AgentTraceData.draft(candidateDraft, 1800),
                        runCostMap(runId),
                        Map.of("modelUsed", generationResult.getModelUsed())));

        return candidateDraft;
    }

    private StructuredResponse runRepairOrReplanStage(
            String runId,
            String safeUserId,
            String safeQuery,
            Map<String, Object> safeDataset,
            TaskClassifier.TaskClassification classification,
            ModelRoute route,
            AgentRunPlan plan,
            RepairMemoryCompressor repairMemory,
            AgentRunState state,
            long runStart,
            int nextAttempt,
            Double temperature) {
        updateThought("Repairing best draft using professional repair memory. Attempt " +
                nextAttempt +
                " of " +
                plan.getMaxAttempts());

        StructuredResponse best = state.getBestDraft();
        String bestRaw = best != null && best.getRaw() != null && !best.getRaw().isBlank()
                ? best.getRaw()
                : best != null ? best.getSummary() : "";

        String compressedMemory = repairMemory.renderForRepair(12, 3200);

        List<String> factualityFixes = new ArrayList<>();
        factualityFixes.add(compressedMemory);
        factualityFixes.addAll(extractFactualityFixes(state));

        List<String> structuralFixes = new ArrayList<>();
        structuralFixes.addAll(extractStructureFixes(state));

        List<String> missingInstructions = new ArrayList<>();
        missingInstructions.addAll(extractMissingInstructions(state));

        ThoughtCallResult<StructuredResponse> repairResult = thoughtExecutor.repairDraft(
                runId,
                safeUserId,
                route.getRepairModel(),
                bestRaw,
                factualityFixes,
                structuralFixes,
                missingInstructions,
                safeDataset,
                nextAttempt);

        if (!repairResult.isSuccess()) {
            repairMemory.ingestFailures(repairResult.getFailures());

            ThoughtRecoveryDecision recoveryDecision = thoughtRecoveryPolicy.decideAfterFailure(
                    plan,
                    state,
                    repairResult.getFailures(),
                    repairMemory);

            traceLogger.warning(
                    runId,
                    safeUserId,
                    "repair-recovery",
                    "Repair failed. Recovery decision: " + recoveryDecision.getAction(),
                    Map.of(
                            "reason", recoveryDecision.getReason(),
                            "failures", repairResult.compactFailureText(),
                            "repairMemory", repairMemory.renderForRepair()));

            if (recoveryDecision.getAction() == ThoughtRecoveryAction.REPLAN_FROM_SCRATCH &&
                    state.getAttemptNumber() + 1 < plan.getMaxAttempts()) {

                ThoughtCallResult<StructuredResponse> replanResult = thoughtExecutor.generateDraft(
                        runId,
                        safeUserId,
                        route.getGeneratorModel(),
                        buildDomainContext(classification, temperature),
                        buildReplanTaskInstruction(
                                safeQuery,
                                classification,
                                repairMemory.renderForRepair()),
                        safeDataset,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        route.getGeneratorTemperature(),
                        nextAttempt);

                if (replanResult.isSuccess()) {
                    StructuredResponse candidateDraft = replanResult.getValue().normalize();

                    recordStageUsage(
                            safeUserId,
                            runId,
                            "replan",
                            replanResult.getModelUsed(),
                            safeQuery + "\n" + repairMemory.renderForRepair(),
                            candidateDraft.getSummary());

                    traceLogger.stage(
                            runId,
                            safeUserId,
                            AgentTraceEventType.GENERATION_FINISHED,
                            "replan",
                            "Replan generation completed successfully.",
                            mergeMaps(
                                    AgentTraceData.draft(candidateDraft, 1800),
                                    runCostMap(runId),
                                    Map.of("modelUsed", replanResult.getModelUsed())));

                    return candidateDraft;
                }

                repairMemory.ingestFailures(replanResult.getFailures());
            }

            finalizePartialAfterRecoveryFailure(
                    safeQuery,
                    state,
                    route,
                    recoveryDecision,
                    runId,
                    safeUserId,
                    runStart);

            return null;
        }

            StructuredResponse candidateDraft = repairResult.getValue().normalize();

            if (VERBOSE_LOGGING) {
                System.out.println("\n[VERBOSE] *** MODEL SELECTED FOR REPAIR: " + repairResult.getModelUsed().toUpperCase() + " ***");
                System.out.println("[VERBOSE] --- REPAIR INSTRUCTIONS ---");
                System.out.println("Factuality Fixes: " + factualityFixes);
                System.out.println("Structural Fixes: " + structuralFixes);
                System.out.println("Missing Instructions: " + missingInstructions);
                System.out.println("[VERBOSE] --- REPAIRED DRAFT ---");
                System.out.println(candidateDraft.getSummary());
                System.out.println("-----------------------------\n");
            }

            recordStageUsage(
                safeUserId,
                runId,
                "repair",
                repairResult.getModelUsed(),
                bestRaw + "\n" + compressedMemory,
                candidateDraft.getSummary());

        traceLogger.stage(
                runId,
                safeUserId,
                AgentTraceEventType.REPAIR_FINISHED,
                "repair",
                "Repair completed successfully.",
                mergeMaps(
                        AgentTraceData.draft(candidateDraft, 1800),
                        runCostMap(runId),
                        Map.of("modelUsed", repairResult.getModelUsed())));

        return candidateDraft;
    }

    private EvaluationResult runEvaluationStage(
            String safeQuery,
            String runId,
            String safeUserId,
            Map<String, Object> safeDataset,
            TaskClassifier.TaskClassification classification,
            ModelRoute route,
            RepairMemoryCompressor repairMemory,
            StructuredResponse candidateDraft,
            AgentRunPlan plan,
            AgentRunState state,
            int nextAttempt) {
        updateThought("Evaluating draft with safe critic fallback...");

        ThoughtCallResult<EvaluationResult> evaluationResult = thoughtExecutor.evaluateDraft(
                runId,
                safeUserId,
                route.getCriticModel(),
                candidateDraft.getSummary(),
                buildRigidRules(safeQuery, classification),
                safeDataset,
                Collections.emptyList(),
                Collections.emptyList(),
                nextAttempt);

        EvaluationResult eval;

        if (!evaluationResult.isSuccess()) {
            repairMemory.ingestFailures(evaluationResult.getFailures());

            ThoughtRecoveryDecision recoveryDecision = thoughtRecoveryPolicy.decideAfterFailure(
                    plan,
                    state,
                    evaluationResult.getFailures(),
                    repairMemory);

            traceLogger.warning(
                    runId,
                    safeUserId,
                    "evaluation-recovery",
                    "Evaluation failed. Continuing conservatively with synthetic failed evaluation.",
                    Map.of(
                            "reason", recoveryDecision.getReason(),
                            "failures", evaluationResult.compactFailureText()));

            eval = syntheticEvaluationFromFailures(evaluationResult.getFailures());

            recordStageUsage(
                    safeUserId,
                    runId,
                    "evaluation-failed",
                    route.getCriticModel(),
                    candidateDraft.getSummary(),
                    eval.getGeneralRationale());
        } else {
            eval = evaluationResult.getValue();

            if (VERBOSE_LOGGING) {
                System.out.println("\n[VERBOSE] *** MODEL SELECTED FOR EVALUATION CRITIC: " + evaluationResult.getModelUsed().toUpperCase() + " ***");
                System.out.println("[VERBOSE] --- CRITIC EVALUATION RESULT ---");
                System.out.println("Pass: " + eval.isPass());
                System.out.println("Rationale: " + eval.getGeneralRationale());
                System.out.println("------------------------------------------\n");
            }

            recordStageUsage(
                    safeUserId,
                    runId,
                    "evaluation",
                    evaluationResult.getModelUsed(),
                    candidateDraft.getSummary(),
                    eval.getGeneralRationale());

            traceLogger.stage(
                    runId,
                    safeUserId,
                    AgentTraceEventType.EVALUATION_FINISHED,
                    "evaluation",
                    "Evaluation completed successfully.",
                    mergeMaps(
                            AgentTraceData.evaluation(eval),
                            runCostMap(runId),
                            Map.of("modelUsed", evaluationResult.getModelUsed())));
        }

        return eval;
    }

    private StructuredResponse finishFromStopDecision(
            String userQuery,
            AgentRunState state,
            ModelRoute route,
            StopPolicy.StopDecision decision) {
        StructuredResponse best = state.getBestDraft();

        if (best == null) {
            return StructuredResponse.failure(
                    "The agent could not produce a usable draft. Reason: " + decision.getReason(),
                    "NO_DRAFT");
        }

        best.normalize();

        if (decision.getAction() == StopPolicy.StopDecision.Action.HARD_STOP) {
            StructuredResponse partial = StructuredResponse.fromSummary(
                    best.getSummary() +
                            "\n\n---\n\nPartial result returned because: " +
                            decision.getReason());
            partial.setThought_process("Hard stop with best available draft.");
            partial.setRaw(best.getRaw());
            return partial.normalize();
        }

        updateThought("Synthesizing best draft into final user-facing response...");

        long synthesisStart = System.currentTimeMillis();

        StructuredResponse synthesized = synthesizer.synthesize(
                best,
                userQuery,
                route.getSynthesizerModel()).normalize();

        if (VERBOSE_LOGGING) {
            System.out.println("\n[VERBOSE] *** MODEL SELECTED FOR FINAL SYNTHESIS: " + route.getSynthesizerModel().toUpperCase() + " ***");
            System.out.println("[VERBOSE] --- FINAL DRAFT ---");
            System.out.println(synthesized.getSummary());
            System.out.println("-----------------------------\n");
        }

        recordStageUsage(
                state.getUserId(),
                state.getRunId(),
                "synthesis",
                route.getSynthesizerModel(),
                best.getSummary(),
                synthesized.getSummary());

        traceLogger.modelEvent(
                state.getRunId(),
                state.getUserId(),
                AgentTraceEventType.SYNTHESIS_FINISHED,
                "synthesis",
                route.getSynthesizerModel(),
                "Synthesis finished.",
                System.currentTimeMillis() - synthesisStart,
                estimateTokens(best.getSummary()),
                estimateTokens(synthesized.getSummary()),
                mergeMaps(
                        AgentTraceData.draft(synthesized, 1200),
                        runCostMap(state.getRunId())));

        if (decision.getAction() == StopPolicy.StopDecision.Action.PARTIAL_STOP) {
            String existingSummary = synthesized.getSummary();
            synthesized.setSummary(
                    existingSummary +
                            "\n\n---\n\nNote: This is the best available result. Stop reason: " +
                            decision.getReason());
        }

        return synthesized.normalize();
    }

    private StructuredResponse finalizePartialAfterRecoveryFailure(
            String userQuery,
            AgentRunState state,
            ModelRoute route,
            ThoughtRecoveryDecision recoveryDecision,
            String runId,
            String safeUserId,
            long runStart) {
        StopPolicy.StopDecision stopDecision = recoveryDecision.getAction() == ThoughtRecoveryAction.HARD_STOP
                ? StopPolicy.StopDecision.hardStop(recoveryDecision.getReason())
                : StopPolicy.StopDecision.partialStop(recoveryDecision.getReason());

        StructuredResponse finished = finishFromStopDecision(userQuery, state, route, stopDecision);

        traceLogger.runFinished(
                runId,
                safeUserId,
                false,
                recoveryDecision.getReason(),
                mergeMaps(
                        AgentTraceData.runState(state),
                        AgentTraceData.draft(finished, 1200),
                        runCostMap(runId),
                        Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

        return finished;
    }

    private StructuredResponse buildFailedNoDraftResponse(
            String runId,
            String safeUserId,
            long runStart) {
        StructuredResponse failed = StructuredResponse.failure(
                "DeepThink could not produce a usable first draft.",
                "GENERATION_FAILED");

        traceLogger.runFinished(
                runId,
                safeUserId,
                false,
                "Generation failed before usable draft.",
                mergeMaps(
                        AgentTraceData.draft(failed, 1000),
                        runCostMap(runId),
                        Map.of("totalDurationMs", System.currentTimeMillis() - runStart)));

        return failed;
    }

    private String buildDomainContext(
            TaskClassifier.TaskClassification classification,
            Double temperature) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a precise MiniAgent worker. ");
        sb.append("You must solve the user task directly and avoid unnecessary verbosity. ");

       if (classification.taskType == TaskClassifier.TaskType.CODE_GENERATION ||
        classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING) {
    sb.append("For code tasks, produce complete, compile-ready, runnable code. ");
    sb.append("Do not use placeholders, TODOs, ghost methods, pseudo-code, omitted sections, or undefined helper references. ");
    sb.append("If the user asks for elaborate code, provide the full implementation even if long. ");
    sb.append("Do not summarize code unless the user explicitly asks for a summary. ");
    sb.append("Do not replace requested code with explanations. ");
}

        if (classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN) {
            sb.append("For architecture tasks, give concrete components, responsibilities, and flow. ");
        }

        if (classification.taskType == TaskClassifier.TaskType.MEDICAL) {
            sb.append(
                    "For medical tasks, avoid hallucinated clinical facts and clearly separate given data from interpretation. ");
        }

        if (temperature != null) {
            if (temperature <= 0.3) {
                sb.append("Keep tone professional, structured, and conservative. ");
            } else if (temperature >= 1.2) {
                sb.append("Use a more creative style only if the task permits it. ");
            }
        }

        return sb.toString();
    }

private String buildInitialTaskInstruction(
        String userQuery,
        TaskClassifier.TaskClassification classification
) {
    return """
            Complete the user's task.

            Task type: %s
            Difficulty: %s
            Pipeline: %s

            User task:
            %s

            Requirements:
            - Solve the task directly.
            - Do not mention internal agent stages.
            - Do not include irrelevant history.
            - If code is requested, provide complete code.
            - If the user asks for a full/elaborate/working implementation, produce the full implementation.
            - Do not provide a toy example when the user asks for a serious app-level implementation.
            - Do not return only imports, snippets, setup lines, or partial fragments.
            - Do not say “additional functionality can be added later.”
            - Do not use placeholders, TODOs, “for brevity”, “implementation omitted”, or pseudo-code.
            """.formatted(
            classification.taskType,
            classification.difficulty,
            classification.recommendedPipeline,
            userQuery
    );
}
    private String buildReplanTaskInstruction(
            String userQuery,
            TaskClassifier.TaskClassification classification,
            String repairMemory) {
        return """
                Replan and solve the user's task from scratch.

                The previous approach failed. Do not continue the old draft blindly.

                Task type: %s
                Difficulty: %s
                Pipeline: %s

                User task:
                %s

                Prior failure memory:
                %s

                Requirements:
                - Produce a fresh, complete answer.
                - Avoid every repeated failure listed above.
                - Do not mention internal agent stages.
                - If code is requested, provide complete compile-ready code.
                """.formatted(
                classification.taskType,
                classification.difficulty,
                classification.recommendedPipeline,
                userQuery,
                repairMemory == null || repairMemory.isBlank() ? "No repair memory." : repairMemory);
    }

    private List<String> buildRigidRules(String userQuery, TaskClassifier.TaskClassification classification) {
        List<String> rules = new ArrayList<>();

        rules.add("The output must directly satisfy the original user task: " + userQuery);
        rules.add("The output must not invent facts not present in the task or dataset.");
        rules.add("The output must be internally consistent.");
        rules.add("The output must avoid empty, generic, or evasive content.");

        if (classification.taskType == TaskClassifier.TaskType.CODE_GENERATION ||
                classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING) {
            rules.add("All code must be complete and compile-ready where possible.");
            rules.add("No TODO placeholders are allowed.");
            rules.add("No undefined helper methods or ghost references are allowed.");
            rules.add("Imports, classes, and methods must be consistent.");
        }

        if (classification.taskType == TaskClassifier.TaskType.MEDICAL) {
            rules.add("Medical output must not add unsupported clinical details.");
            rules.add("Medical output must use medically sensible terminology.");
        }

        if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR) {
            rules.add("The answer must show a logically ordered solution.");
        }

        return rules;
    }

    private EvaluationResult syntheticEvaluationFromFailures(List<ThoughtFailureRecord> failures) {
        EvaluationResult result = new EvaluationResult();
        result.setPass(false);
        result.setFailureType("CRITIC_MALFORMED");
        result.setFactualityScore(40);
        result.setStructureScore(40);
        result.setStyleScore(50);
        result.setInstructionAdherenceScore(40);

        List<String> repairInstructions = new ArrayList<>();
        List<EvaluationResult.CriticIssue> issues = new ArrayList<>();

        if (failures == null || failures.isEmpty()) {
            repairInstructions.add("Critic failed without a concrete failure record. Repair conservatively.");
            issues.add(new EvaluationResult.CriticIssue(
                    "major",
                    "Critic failed without details.",
                    "Repair conservatively and retry evaluation."));
        } else {
            for (ThoughtFailureRecord failure : failures) {
                if (failure == null) {
                    continue;
                }

                repairInstructions.add(failure.toRepairLine());
                issues.add(new EvaluationResult.CriticIssue(
                        failure.getSeverity() >= 8 ? "critical" : "major",
                        failure.getMessage(),
                        failure.getFixHint()));
            }
        }

        result.setRepairInstructions(repairInstructions);
        result.setIssues(issues);
        result.setGeneralRationale("Synthetic failed evaluation created because critic stage failed.");
        result.setRawOutput("SYNTHETIC_EVALUATION_FROM_CRITIC_FAILURE");

        return result;
    }

    private List<String> extractFactualityFixes(AgentRunState state) {
        if (state == null || state.getLatestEvaluation() == null) {
            return Collections.emptyList();
        }

        List<String> fixes = state.getLatestEvaluation().getFactualityFixes();
        return fixes == null ? Collections.emptyList() : fixes;
    }

    private List<String> extractStructureFixes(AgentRunState state) {
        if (state == null || state.getLatestEvaluation() == null) {
            return Collections.emptyList();
        }

        List<String> fixes = state.getLatestEvaluation().getStructureFixes();
        return fixes == null ? Collections.emptyList() : fixes;
    }

    private List<String> extractMissingInstructions(AgentRunState state) {
        if (state == null || state.getLatestEvaluation() == null) {
            return Collections.emptyList();
        }

        List<String> missing = state.getLatestEvaluation().getMissingInstructions();
        return missing == null ? Collections.emptyList() : missing;
    }

    private void recordStageUsage(
            String userId,
            String runId,
            String stage,
            String model,
            String inputText,
            String outputText) {
        try {
            costManager.addModelUsage(
                    safeUserId(userId),
                    runId == null || runId.isBlank() ? "unknown-run" : runId,
                    stage == null || stage.isBlank() ? "unknown-stage" : stage,
                    model == null || model.isBlank() ? "unknown-model" : model,
                    estimateTokens(inputText),
                    estimateTokens(outputText),
                    true);
        } catch (Exception e) {
            System.err.println("[COST WARNING] Failed to record usage: " + e.getMessage());
        }
    }

    private Map<String, Object> runCostMap(String runId) {
        try {
            TokenCostManager.UsageSnapshot runCost = costManager.getRunSnapshot(runId);
            return costSummary(runCost);
        } catch (Exception e) {
            return Map.of("costError", e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    private Map<String, Object> costSummary(TokenCostManager.UsageSnapshot runCost) {
        if (runCost == null) {
            return Map.of(
                    "runTokens", 0,
                    "runCostInr", 0.0,
                    "runCallCount", 0);
        }

        return Map.of(
                "runTokens", runCost.getTotalTokens(),
                "runInputTokens", runCost.getInputTokens(),
                "runOutputTokens", runCost.getOutputTokens(),
                "runCostInr", runCost.getTotalCostInr(),
                "runCallCount", runCost.getCallCount(),
                "runEstimatedCallCount", runCost.getEstimatedCallCount());
    }

    private String buildTemperaturePersonaModifier(Double temperature) {
        if (temperature == null) {
            return "";
        }

        if (temperature <= 0.3) {
            return " Output MUST be exceptionally professional, structured, and clinical.";
        }

        if (temperature >= 1.2) {
            return " Output may be playful and experimental only if the user task permits it.";
        }

        return "";
    }

    private int estimateTokens(String text) {
        return TokenCostManager.estimateTokens(text);
    }

    private String safeUserId(String userId) {
        return userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
    }

    @SafeVarargs
    private final Map<String, Object> mergeMaps(Map<String, Object>... maps) {
        Map<String, Object> merged = new HashMap<>();

        if (maps == null) {
            return merged;
        }

        for (Map<String, Object> map : maps) {
            if (map == null || map.isEmpty()) {
                continue;
            }

            merged.putAll(map);
        }

        return merged;
    }

    private String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);

        if (text.length() <= safeMax) {
            return text;
        }

        return text.substring(0, safeMax) + "...[TRUNCATED]";
    }
}
