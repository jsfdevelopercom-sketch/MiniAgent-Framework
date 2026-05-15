package com.miniagent.core;

import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;
import com.miniagent.trace.AgentTraceEventType;
import com.miniagent.trace.AgentTraceLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SafeThoughtExecutor wraps MiniAgentWorker and MiniAgentEvaluator calls with
 * reliable fallback, validation, and recovery-friendly failure records.
 *
 * Important architectural rule:
 * This class must NOT classify the user's task again.
 *
 * Task identity, difficulty, budget, pipeline, tool need, and depth are already
 * decided earlier by:
 *
 * TaskClassifier -> ModelRouter -> AgentRunPlan
 *
 * SafeThoughtExecutor should only consume that decision. It should not run
 * keyword-based "serious code" detection or secretly override the classifier.
 *
 * Why this matters:
 * - Code is only one category of hard tasks.
 * - Medical reasoning, research, architecture, financial/legal reasoning,
 * summarization of large context, and tool-heavy tasks can require equal or
 * stronger fallback protection.
 * - Repeating classification inside this class creates contradictory behavior:
 * the classifier may say "HARD RESEARCH" but this class may treat it as normal
 * because it does not look like code.
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
         * New preferred generation entry point.
         *
         * The AgentRunPlan is the source of truth for:
         * - task type
         * - difficulty
         * - deep reasoning need
         * - tool/web/file need
         * - pipeline
         * - answer token budget
         *
         * This method uses those decisions to choose normal fallback or protected
         * fallback. It does not inspect the prompt text to guess whether the task is
         * "serious code".
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
                        int attemptNumber,
                        AgentRunPlan plan) {

                boolean protectedQualityTask = shouldUseProtectedFallback(plan);

                List<String> models = protectedQualityTask
                                ? fallbackPolicy.strongGenerationFallbacks(preferredModel)
                                : fallbackPolicy.generationFallbacks(preferredModel);

                DraftSanityValidator.DraftSanityContext sanityContext = buildSanityContext(
                                taskInstructions,
                                "generation",
                                plan);

                logPolicyDecision(
                                runId,
                                userId,
                                "generation",
                                preferredModel,
                                protectedQualityTask,
                                plan);

                return executeWithFallback(
                                runId,
                                userId,
                                "generation",
                                models,
                                attemptNumber,
                                model -> worker.generateDraft(
                                                model,
                                                safe(domainContext),
                                                safe(taskInstructions),
                                                dataset == null ? Collections.emptyMap() : dataset,
                                                liveInjections == null ? Collections.emptyList() : liveInjections,
                                                history == null ? Collections.emptyList() : history,
                                                temperature),
                                (model, response) -> validateStructuredResponse(
                                                "generation",
                                                model,
                                                attemptNumber,
                                                response,
                                                sanityContext));
        }

        /**
         * Backward-compatible overload.
         *
         * Keep this so older callers still compile. It deliberately does not run any
         * local keyword classifier. If the caller does not provide an AgentRunPlan,
         * this executor uses the conservative normal fallback path.
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

                return generateDraft(
                                runId,
                                userId,
                                preferredModel,
                                domainContext,
                                taskInstructions,
                                dataset,
                                liveInjections,
                                history,
                                temperature,
                                attemptNumber,
                                null);
        }

        /**
         * New preferred repair entry point.
         *
         * Repair quality is also controlled by AgentRunPlan. A hard medical answer,
         * a research answer, or a major architecture answer should not drop into weak
         * fallback simply because it is not code.
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
                        int attemptNumber,
                        AgentRunPlan plan) {

                boolean protectedQualityTask = shouldUseProtectedFallback(plan);

                List<String> models = protectedQualityTask
                                ? fallbackPolicy.strongRepairFallbacks(preferredModel)
                                : fallbackPolicy.repairFallbacks(preferredModel);

                String repairContextText = buildRepairContextText(
                                previousDraft,
                                factualityFixes,
                                structuralFixes,
                                missingInstructions,
                                dataset);

                DraftSanityValidator.DraftSanityContext sanityContext = buildSanityContext(
                                repairContextText,
                                "repair",
                                plan);

                logPolicyDecision(
                                runId,
                                userId,
                                "repair",
                                preferredModel,
                                protectedQualityTask,
                                plan);

                return executeWithFallback(
                                runId,
                                userId,
                                "repair",
                                models,
                                attemptNumber,
                                model -> worker.repairDraft(
                                                model,
                                                safe(previousDraft),
                                                factualityFixes == null ? Collections.emptyList() : factualityFixes,
                                                structuralFixes == null ? Collections.emptyList() : structuralFixes,
                                                missingInstructions == null ? Collections.emptyList()
                                                                : missingInstructions,
                                                dataset == null ? Collections.emptyMap() : dataset),
                                (model, response) -> validateStructuredResponse(
                                                "repair",
                                                model,
                                                attemptNumber,
                                                response,
                                                sanityContext));
        }

        /**
         * Backward-compatible overload.
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

                return repairDraft(
                                runId,
                                userId,
                                preferredModel,
                                previousDraft,
                                factualityFixes,
                                structuralFixes,
                                missingInstructions,
                                dataset,
                                attemptNumber,
                                null);
        }

        /**
         * New preferred evaluation entry point.
         *
         * The critic fallback policy is also plan-driven. Hard research, medical,
         * architecture, and tool-heavy answers need a strong critic as much as code
         * does.
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
                        int attemptNumber,
                        AgentRunPlan plan) {

                boolean protectedQualityTask = shouldUseProtectedFallback(plan);

                List<String> models = protectedQualityTask
                                ? fallbackPolicy.strongCriticFallbacks(preferredModel)
                                : fallbackPolicy.criticFallbacks(preferredModel);

                logPolicyDecision(
                                runId,
                                userId,
                                "evaluation",
                                preferredModel,
                                protectedQualityTask,
                                plan);

                return executeWithFallback(
                                runId,
                                userId,
                                "evaluation",
                                models,
                                attemptNumber,
                                model -> evaluator.evaluateDraft(
                                                model,
                                                model != null && model.toLowerCase(Locale.ROOT).startsWith("gemini"),
                                                safe(draft),
                                                rigidRules == null ? Collections.emptyList() : rigidRules,
                                                dataset == null ? Collections.emptyMap() : dataset,
                                                liveInjections == null ? Collections.emptyList() : liveInjections,
                                                history == null ? Collections.emptyList() : history),
                                (model, evaluation) -> validateEvaluation(model, attemptNumber, evaluation));
        }

        /**
         * Backward-compatible overload.
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

                return evaluateDraft(
                                runId,
                                userId,
                                preferredModel,
                                draft,
                                rigidRules,
                                dataset,
                                liveInjections,
                                history,
                                attemptNumber,
                                null);
        }

        /**
         * Shared fallback execution loop.
         *
         * This method does not care why a model list was selected. Its only job is:
         * - call the candidate model
         * - validate the output
         * - record a compact failure if needed
         * - move to the next candidate
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

                                logStage(
                                                runId,
                                                userId,
                                                AgentTraceEventType.WARNING,
                                                stage,
                                                "Attempting thought stage with model: " + safeModel(model),
                                                model,
                                                0);

                                T value = operation.execute(model);

                                ThoughtFailureRecord validationFailure = validator.validate(model, value);

                                if (validationFailure == null) {
                                        logStage(
                                                        runId,
                                                        userId,
                                                        AgentTraceEventType.WARNING,
                                                        stage,
                                                        "Thought stage succeeded using model: " + safeModel(model),
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

        /**
         * Validates a generated or repaired StructuredResponse.
         *
         * DraftSanityValidator still receives a code-sanity boolean because that
         * validator appears to have code-specific checks. The important change is:
         * the boolean now comes from TaskClassification.taskType, not from another
         * prompt keyword detector hidden inside this executor.
         */
        private ThoughtFailureRecord validateStructuredResponse(
                        String stage,
                        String model,
                        int attemptNumber,
                        StructuredResponse response,
                        DraftSanityValidator.DraftSanityContext sanityContext) {

                if (response == null) {
                        return ThoughtFailureRecord.of(
                                        stage.equals("repair") ? ThoughtFailureType.REPAIR_FAILED
                                                        : ThoughtFailureType.EMPTY_OUTPUT,
                                        stage,
                                        model,
                                        attemptNumber,
                                        "Model returned null StructuredResponse.",
                                        "Retry with fallback model.",
                                        8,
                                        true);
                }

                response.normalize();

                String summary = safe(response.getSummary());
                String raw = safe(response.getRaw());
                String combined = (summary + " " + raw).toLowerCase(Locale.ROOT);

                if (summary.isBlank() && raw.isBlank()) {
                        return ThoughtFailureRecord.of(
                                        stage.equals("repair") ? ThoughtFailureType.REPAIR_FAILED
                                                        : ThoughtFailureType.EMPTY_OUTPUT,
                                        stage,
                                        model,
                                        attemptNumber,
                                        "Model returned blank summary and blank raw output.",
                                        "Retry with fallback model or regenerate from scratch.",
                                        8,
                                        true);
                }

                if (combined.contains("empty json returned")
                                || combined.contains("generated an empty response")
                                || combined.contains("empty response payload")) {

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

                if (combined.contains("safety filters")
                                || combined.contains("safety filter")
                                || combined.contains("blocked this prompt")) {

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

                DraftSanityValidator.DraftSanityResult sanity = draftSanityValidator.validate(response, sanityContext);

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

        /**
         * Validates critic output.
         *
         * A critic response with all zero scores and no explanation/fixes is almost
         * always a malformed or empty structured response, not a legitimate critique.
         */
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

                boolean allScoresZero = evaluation.getFactualityScore() == 0
                                && evaluation.getStructureScore() == 0
                                && evaluation.getStyleScore() == 0
                                && evaluation.getInstructionAdherenceScore() == 0;

                boolean hasRationale = evaluation.getGeneralRationale() != null
                                && !evaluation.getGeneralRationale().isBlank();

                boolean hasFixes = hasItems(evaluation.getFactualityFixes())
                                || hasItems(evaluation.getStructureFixes())
                                || hasItems(evaluation.getStyleFixes())
                                || hasItems(evaluation.getMissingInstructions())
                                || hasItems(evaluation.getRepairInstructions())
                                || hasItems(evaluation.getIssues());

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

        /**
         * Decides whether this stage should use strong/protected fallback.
         *
         * This is not classification. It only translates the already-created
         * AgentRunPlan into fallback strength.
         *
         * Protected fallback is used for any high-attention task, not only code:
         * - HARD tasks
         * - deep reasoning tasks
         * - tool/web/file tasks
         * - large token budget tasks
         * - architecture/research/medical/code/tool-required task types
         * - PLAN_THINK_CRITIC_REPAIR and TOOL_AGENT pipelines
         */
        private boolean shouldUseProtectedFallback(AgentRunPlan plan) {
                if (plan == null) {
                        return false;
                }

                if (plan.getMaxAnswerTokens() >= 6000) {
                        return true;
                }

                TaskClassifier.TaskClassification classification = plan.getClassification();

                if (classification == null) {
                        return false;
                }

                if (classification.difficulty == TaskClassifier.TaskDifficulty.HARD) {
                        return true;
                }

                if (classification.needsDeepReasoning
                                || classification.needsTools
                                || classification.needsWeb
                                || classification.needsFileAccess) {
                        return true;
                }

                if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR
                                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT) {
                        return true;
                }

                TaskClassifier.TaskType taskType = classification.taskType;

                return taskType == TaskClassifier.TaskType.CODE_GENERATION
                                || taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                                || taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN
                                || taskType == TaskClassifier.TaskType.RESEARCH
                                || taskType == TaskClassifier.TaskType.MEDICAL
                                || taskType == TaskClassifier.TaskType.TOOL_REQUIRED;
        }

        /**
         * Enables code-specific deterministic sanity checks only when the classifier
         * said the task is code generation or code debugging.
         *
         * Do not infer this from raw text here. That was the old bug.
         */
        private boolean shouldEnableCodeSanity(AgentRunPlan plan) {
                if (plan == null || plan.getClassification() == null) {
                        return false;
                }

                TaskClassifier.TaskType taskType = plan.getClassification().taskType;

                return taskType == TaskClassifier.TaskType.CODE_GENERATION
                                || taskType == TaskClassifier.TaskType.CODE_DEBUGGING;
        }

        /**
         * Builds a complexity score for DraftSanityValidator from the existing plan.
         *
         * This is not a second classifier. It is just a compact numeric translation
         * of decisions already made by TaskClassifier and AgentRunPlan.
         */
        private int expectedComplexityFromPlan(AgentRunPlan plan) {
                if (plan == null) {
                        return 0;
                }

                int complexity = 3;

                TaskClassifier.TaskClassification classification = plan.getClassification();

                if (classification != null) {
                        if (classification.difficulty == TaskClassifier.TaskDifficulty.EASY) {
                                complexity = 2;
                        } else if (classification.difficulty == TaskClassifier.TaskDifficulty.MEDIUM) {
                                complexity = 5;
                        } else if (classification.difficulty == TaskClassifier.TaskDifficulty.HARD) {
                                complexity = 8;
                        }

                        if (classification.needsDeepReasoning) {
                                complexity = Math.max(complexity, 7);
                        }

                        if (classification.needsTools || classification.needsWeb || classification.needsFileAccess) {
                                complexity = Math.max(complexity, 7);
                        }

                        if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR
                                        || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT) {
                                complexity = Math.max(complexity, 8);
                        }
                }

                if (plan.getMaxAnswerTokens() >= 12000) {
                        complexity = Math.max(complexity, 10);
                } else if (plan.getMaxAnswerTokens() >= 8000) {
                        complexity = Math.max(complexity, 9);
                } else if (plan.getMaxAnswerTokens() >= 6000) {
                        complexity = Math.max(complexity, 8);
                }

                return clamp(complexity, 0, 10);
        }

        private DraftSanityValidator.DraftSanityContext buildSanityContext(
                        String taskText,
                        String stage,
                        AgentRunPlan plan) {

                String query = safe(taskText);
                String taskKind = buildTaskKind(stage, plan);
                boolean codeSanity = shouldEnableCodeSanity(plan);
                int expectedComplexity = expectedComplexityFromPlan(plan);

                return DraftSanityValidator.DraftSanityContext.of(
                                query,
                                taskKind,
                                codeSanity,
                                expectedComplexity);
        }

        private String buildTaskKind(String stage, AgentRunPlan plan) {
                String safeStage = safe(stage);

                if (plan == null || plan.getClassification() == null) {
                        return safeStage;
                }

                TaskClassifier.TaskClassification classification = plan.getClassification();

                String taskType = classification.taskType == null
                                ? "UNKNOWN"
                                : classification.taskType.name();

                String difficulty = classification.difficulty == null
                                ? "UNKNOWN"
                                : classification.difficulty.name();

                String pipeline = classification.recommendedPipeline == null
                                ? "UNKNOWN"
                                : classification.recommendedPipeline.name();

                return safeStage + "/" + taskType + "/" + difficulty + "/" + pipeline;
        }

        private String buildRepairContextText(
                        String previousDraft,
                        List<String> factualityFixes,
                        List<String> structuralFixes,
                        List<String> missingInstructions,
                        Map<String, Object> dataset) {

                return combineText(
                                previousDraft,
                                listToText(factualityFixes),
                                listToText(structuralFixes),
                                listToText(missingInstructions),
                                mapToText(dataset));
        }

        private void logPolicyDecision(
                        String runId,
                        String userId,
                        String stage,
                        String preferredModel,
                        boolean protectedQualityTask,
                        AgentRunPlan plan) {

                if (traceLogger == null) {
                        return;
                }

                Map<String, Object> data = planDebugData(plan);
                data.put("model", safeModel(preferredModel));
                data.put("protectedQualityTask", protectedQualityTask);

                traceLogger.stage(
                                runId,
                                userId,
                                AgentTraceEventType.WARNING,
                                stage,
                                protectedQualityTask
                                                ? "Using protected fallback policy from AgentRunPlan."
                                                : "Using normal fallback policy from AgentRunPlan.",
                                data);
        }

        private Map<String, Object> planDebugData(AgentRunPlan plan) {
                Map<String, Object> data = new LinkedHashMap<>();

                if (plan == null) {
                        data.put("planPresent", false);
                        return data;
                }

                data.put("planPresent", true);
                data.put("maxAttempts", plan.getMaxAttempts());
                data.put("successThreshold", plan.getSuccessThreshold());
                data.put("maxAnswerTokens", plan.getMaxAnswerTokens());
                data.put("maxWallClockMs",
                                plan.getMaxWallClockTime() == null ? 0 : plan.getMaxWallClockTime().toMillis());

                TaskClassifier.TaskClassification classification = plan.getClassification();

                if (classification != null) {
                        data.put("taskType", classification.taskType == null ? "" : classification.taskType.name());
                        data.put("difficulty",
                                        classification.difficulty == null ? "" : classification.difficulty.name());
                        data.put("pipeline", classification.recommendedPipeline == null ? ""
                                        : classification.recommendedPipeline.name());
                        data.put("needsDeepReasoning", classification.needsDeepReasoning);
                        data.put("needsTools", classification.needsTools);
                        data.put("needsWeb", classification.needsWeb);
                        data.put("needsFileAccess", classification.needsFileAccess);
                        data.put("providerUsed", safe(classification.providerUsed));
                }

                return data;
        }

        private void logFailure(
                        String runId,
                        String userId,
                        String stage,
                        ThoughtFailureRecord failure) {

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
                                                "model", safe(failure.getModel()),
                                                "attempt", failure.getAttemptNumber(),
                                                "message", safe(failure.getMessage()),
                                                "fixHint", safe(failure.getFixHint()),
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
                                                "model", safe(model),
                                                "durationMs", durationMs));
        }

        private String combineText(String... parts) {
                if (parts == null || parts.length == 0) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (String part : parts) {
                        if (part != null && !part.isBlank()) {
                                if (builder.length() > 0) {
                                        builder.append('\n');
                                }
                                builder.append(part);
                        }
                }

                return builder.toString();
        }

        private String listToText(List<String> lines) {
                if (lines == null || lines.isEmpty()) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (String line : lines) {
                        if (line != null && !line.isBlank()) {
                                if (builder.length() > 0) {
                                        builder.append('\n');
                                }
                                builder.append(line);
                        }
                }

                return builder.toString();
        }

        private String mapToText(Map<String, Object> dataset) {
                if (dataset == null || dataset.isEmpty()) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (Map.Entry<String, Object> entry : dataset.entrySet()) {
                        if (entry == null) {
                                continue;
                        }

                        String key = safe(entry.getKey());
                        Object valueObject = entry.getValue();
                        String value = valueObject == null ? "" : String.valueOf(valueObject);

                        if (!key.isBlank() || !value.isBlank()) {
                                if (builder.length() > 0) {
                                        builder.append('\n');
                                }
                                builder.append(key).append(": ").append(value);
                        }
                }

                return builder.toString();
        }

        private boolean hasItems(List<?> values) {
                return values != null && !values.isEmpty();
        }

        private int clamp(int value, int min, int max) {
                return Math.max(min, Math.min(max, value));
        }

        private String safeModel(String model) {
                return model == null || model.isBlank() ? "unknown" : model;
        }

        private String safe(String value) {
                return value == null ? "" : value;
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