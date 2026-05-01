package com.miniagent.core;

import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;
import com.miniagent.trace.AgentTraceEventType;
import com.miniagent.trace.AgentTraceLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SafeThoughtExecutor wraps worker/evaluator model calls with:
 * - provider fallback
 * - blank-output detection
 * - malformed-output detection
 * - safety-block detection
 * - exception classification
 * - compact failure records
 *
 * This class should be used by Agent instead of calling MiniAgentWorker
 * directly.
 */
public class SafeThoughtExecutor {

    private final MiniAgentWorker worker;
    private final MiniAgentEvaluator evaluator;
    private final ModelFallbackPolicy fallbackPolicy;
    private final AgentTraceLogger traceLogger;
    private final DraftSanityValidator draftSanityValidator;

    public SafeThoughtExecutor(
            MiniAgentWorker worker,
            MiniAgentEvaluator evaluator,
            ModelFallbackPolicy fallbackPolicy,
            AgentTraceLogger traceLogger) {
        if (worker == null) {
            throw new IllegalArgumentException("MiniAgentWorker cannot be null.");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("MiniAgentEvaluator cannot be null.");
        }

        this.worker = worker;
        this.evaluator = evaluator;
        this.fallbackPolicy = fallbackPolicy == null ? new ModelFallbackPolicy() : fallbackPolicy;
        this.traceLogger = traceLogger;
        this.draftSanityValidator = new DraftSanityValidator();
    }

    /**
     * Executes the initial generation stage, attempting to produce a structured draft.
     * 
     * Deep Insight:
     * This method doesn't just call the model; it orchestrates a fallback loop. If the primary
     * model throws an exception or returns a blank/corrupt JSON payload, it will seamlessly
     * shift to the next configured fallback model (e.g., GPT-4o -> GPT-4o-mini).
     * This is critical for maintaining high availability in production environments.
     */
    public ThoughtCallResult<StructuredResponse> generateDraft(
            String runId,
            String userId,
            String preferredModel,
            String domainContext,
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history,
            Double temperature,
            int attemptNumber) {
        List<String> models = fallbackPolicy.generationFallbacks(preferredModel);

        return executeWithFallback(
                runId,
                userId,
                "generation",
                models,
                attemptNumber,
                model -> worker.generateDraft(
                        model,
                        domainContext,
                        taskInstructions,
                        dataset == null ? Collections.emptyMap() : dataset,
                        liveInjections == null ? Collections.emptyList() : liveInjections,
                        history == null ? Collections.emptyList() : history,
                        temperature),
                (model, response) -> validateStructuredResponse("generation", model, attemptNumber, response));
    }

    /**
     * Attempts to repair a malfunctioning draft using targeted fixes.
     * 
     * Deep Insight:
     * Instead of a blind retry, this incorporates 'factualityFixes', 'structuralFixes',
     * and 'missingInstructions' fed back from the Critic. By explicitly passing these
     * into the model context, it turns a failure loop into a deterministic improvement cycle.
     */
    public ThoughtCallResult<StructuredResponse> repairDraft(
            String runId,
            String userId,
            String preferredModel,
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset,
            int attemptNumber) {
        List<String> models = fallbackPolicy.repairFallbacks(preferredModel);

        return executeWithFallback(
                runId,
                userId,
                "repair",
                models,
                attemptNumber,
                model -> worker.repairDraft(
                        model,
                        previousDraft == null ? "" : previousDraft,
                        factualityFixes == null ? Collections.emptyList() : factualityFixes,
                        structuralFixes == null ? Collections.emptyList() : structuralFixes,
                        missingInstructions == null ? Collections.emptyList() : missingInstructions,
                        dataset == null ? Collections.emptyMap() : dataset),
                (model, response) -> validateStructuredResponse("repair", model, attemptNumber, response));
    }

    /**
     * Evaluates a draft against rigid system rules and dataset facts.
     * 
     * Deep Insight:
     * The evaluation stage acts as a hard gate. If a model generates a hallucinated clinical 
     * fact or a Code snippet with ghost methods, this stage catches it. Fallback logic is 
     * employed here because a broken critic would otherwise lead to infinite repair loops.
     */
    public ThoughtCallResult<EvaluationResult> evaluateDraft(
            String runId,
            String userId,
            String preferredModel,
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history,
            int attemptNumber) {
        List<String> models = fallbackPolicy.criticFallbacks(preferredModel);

        return executeWithFallback(
                runId,
                userId,
                "evaluation",
                models,
                attemptNumber,
                model -> evaluator.evaluateDraft(
                        model,
                        model != null && model.toLowerCase(Locale.ROOT).startsWith("gemini"),
                        draft == null ? "" : draft,
                        rigidRules == null ? Collections.emptyList() : rigidRules,
                        dataset == null ? Collections.emptyMap() : dataset,
                        liveInjections == null ? Collections.emptyList() : liveInjections,
                        history == null ? Collections.emptyList() : history),
                (model, evaluation) -> validateEvaluation(model, attemptNumber, evaluation));
    }

    /**
     * Core fault-tolerant execution loop used by all Thought stages.
     * 
     * Deep Insight:
     * This generic engine iterates through the fallback model list. It attempts the operation,
     * passes the output to the stage-specific validator (e.g., checking for empty JSON),
     * and if it fails, records a compact ThoughtFailureRecord. Only when all models are exhausted
     * does it return a failure array, allowing ThoughtRecoveryPolicy to intervene.
     */
    private <T> ThoughtCallResult<T> executeWithFallback(
            String runId,
            String userId,
            String stage,
            List<String> models,
            int attemptNumber,
            ModelOperation<T> operation,
            ResultValidator<T> validator) {
        List<ThoughtFailureRecord> failures = new ArrayList<>();

        if (models == null || models.isEmpty()) {
            failures.add(ThoughtFailureRecord.of(
                    ThoughtFailureType.MODEL_EXCEPTION,
                    stage,
                    "none",
                    attemptNumber,
                    "No model candidates available for stage.",
                    "Configure ModelFallbackPolicy with at least one model.",
                    9,
                    false));
            return ThoughtCallResult.failure(failures);
        }

        for (String model : models) {
            try {
                long startedAt = System.currentTimeMillis();
                T value = operation.execute(model);

                ThoughtFailureRecord validationFailure = validator.validate(model, value);
                if (validationFailure == null) {
                    logStage(
                            runId,
                            userId,
                            AgentTraceEventType.WARNING,
                            stage,
                            "Thought stage recovered/succeeded using model: " + model,
                            model,
                            System.currentTimeMillis() - startedAt);
                    return ThoughtCallResult.success(value, model, failures);
                }

                failures.add(validationFailure);
                logFailure(runId, userId, stage, validationFailure);
            } catch (Exception exception) {
                ThoughtFailureRecord failure = ThoughtFailureRecord.fromException(
                        exception,
                        stage,
                        model,
                        attemptNumber);
                failures.add(failure);
                logFailure(runId, userId, stage, failure);
            }
        }

        return ThoughtCallResult.failure(failures);
    }

    private ThoughtFailureRecord validateStructuredResponse(
            String stage,
            String model,
            int attemptNumber,
            StructuredResponse response) {
        if (response == null) {
            return ThoughtFailureRecord.of(
                    stage.equals("repair") ? ThoughtFailureType.REPAIR_FAILED : ThoughtFailureType.EMPTY_OUTPUT,
                    stage,
                    model,
                    attemptNumber,
                    "Model returned null StructuredResponse.",
                    "Retry with fallback model.",
                    8,
                    true);
        }

        response.normalize();

        String summary = response.getSummary();
        String raw = response.getRaw();
        String combined = (summary + " " + raw).toLowerCase(Locale.ROOT);

        if (summary.isBlank() && raw.isBlank()) {
            return ThoughtFailureRecord.of(
                    stage.equals("repair") ? ThoughtFailureType.REPAIR_FAILED : ThoughtFailureType.EMPTY_OUTPUT,
                    stage,
                    model,
                    attemptNumber,
                    "Model returned blank summary and blank raw output.",
                    "Retry with fallback model or regenerate from scratch.",
                    8,
                    true);
        }

        if (combined.contains("empty json returned") ||
                combined.contains("generated an empty response") ||
                combined.contains("empty response payload")) {
            return ThoughtFailureRecord.of(
                    ThoughtFailureType.EMPTY_SUMMARY,
                    stage,
                    model,
                    attemptNumber,
                    "Model returned an empty/corrupted JSON placeholder.",
                    "Retry with fallback model and smaller prompt.",
                    7,
                    true);
        }

        if (combined.contains("safety filters") ||
                combined.contains("safety filter") ||
                combined.contains("blocked this prompt")) {
            return ThoughtFailureRecord.of(
                    ThoughtFailureType.MODEL_SAFETY_BLOCKED,
                    stage,
                    model,
                    attemptNumber,
                    "Model safety layer blocked the prompt.",
                    "Do not retry the same unsafe prompt. Return best safe partial result.",
                    9,
                    false);
        }

        DraftSanityValidator.DraftSanityContext context = DraftSanityValidator.DraftSanityContext.of(
                "",
                "",
                false,
                0);

        DraftSanityValidator.DraftSanityResult sanity = draftSanityValidator.validate(response, context);

        if (!sanity.isPassed()) {
            ThoughtFailureType type = sanity.hasCriticalIssues()
                    ? ThoughtFailureType.STRUCTURAL_FAILURE
                    : ThoughtFailureType.UNKNOWN;

            return ThoughtFailureRecord.of(
                    type,
                    stage,
                    model,
                    attemptNumber,
                    "Draft failed deterministic sanity validation: " + sanity.compactSummary(),
                    String.join("\n", sanity.toRepairInstructions()),
                    sanity.hasCriticalIssues() ? 9 : 7,
                    true);
        }

        return null;
    }

    private ThoughtFailureRecord validateEvaluation(
            String model,
            int attemptNumber,
            EvaluationResult evaluation) {
        if (evaluation == null) {
            return ThoughtFailureRecord.of(
                    ThoughtFailureType.CRITIC_MALFORMED,
                    "evaluation",
                    model,
                    attemptNumber,
                    "Critic returned null EvaluationResult.",
                    "Retry critic with fallback model.",
                    7,
                    true);
        }

        boolean allScoresZero = evaluation.getFactualityScore() == 0 &&
                evaluation.getStructureScore() == 0 &&
                evaluation.getStyleScore() == 0 &&
                evaluation.getInstructionAdherenceScore() == 0;

        boolean hasRationale = evaluation.getGeneralRationale() != null &&
                !evaluation.getGeneralRationale().isBlank();

        boolean hasFixes = !evaluation.getFactualityFixes().isEmpty() ||
                !evaluation.getStructureFixes().isEmpty() ||
                !evaluation.getStyleFixes().isEmpty() ||
                !evaluation.getMissingInstructions().isEmpty() ||
                !evaluation.getRepairInstructions().isEmpty() ||
                !evaluation.getIssues().isEmpty();

        if (allScoresZero && !hasRationale && !hasFixes) {
            return ThoughtFailureRecord.of(
                    ThoughtFailureType.CRITIC_MALFORMED,
                    "evaluation",
                    model,
                    attemptNumber,
                    "Critic produced unusable empty evaluation.",
                    "Retry critic with fallback model.",
                    7,
                    true);
        }

        return null;
    }

    private void logFailure(String runId, String userId, String stage, ThoughtFailureRecord failure) {
        if (traceLogger == null || failure == null) {
            return;
        }

        traceLogger.warning(
                runId,
                userId,
                stage,
                "Thought failure: " + failure.getType(),
                Map.of(
                        "type", failure.getType().name(),
                        "model", failure.getModel(),
                        "attempt", failure.getAttemptNumber(),
                        "message", failure.getMessage(),
                        "fixHint", failure.getFixHint(),
                        "severity", failure.getSeverity(),
                        "recoverable", failure.isRecoverable()));
    }

    private void logStage(
            String runId,
            String userId,
            AgentTraceEventType type,
            String stage,
            String message,
            String model,
            long durationMs) {
        if (traceLogger == null) {
            return;
        }

        traceLogger.stage(
                runId,
                userId,
                type,
                stage,
                message,
                Map.of(
                        "model", model == null ? "" : model,
                        "durationMs", durationMs));
    }

    @FunctionalInterface
    private interface ModelOperation<T> {
        T execute(String model) throws Exception;
    }

    @FunctionalInterface
    private interface ResultValidator<T> {
        ThoughtFailureRecord validate(String model, T result);
    }
}
