package com.miniagent.core;

import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;
import com.miniagent.trace.AgentTraceEventType;
import com.miniagent.trace.AgentTraceLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SafeThoughtExecutor is the guarded execution layer around:
 *
 * - MiniAgentWorker generation
 * - MiniAgentWorker repair
 * - MiniAgentEvaluator criticism/evaluation
 *
 * This class is deliberately NOT a classifier.
 *
 * The actual task identity is decided earlier by:
 *
 * TaskClassifier
 * -> decides task type, difficulty, pipeline, maxAttempts, maxAnswerTokens
 *
 * ModelRouter
 * -> chooses generator, critic, repair, and synthesizer models
 *
 * AgentRunPlan
 * -> freezes those decisions into one execution contract
 *
 * SafeThoughtExecutor only consumes that plan.
 *
 * Why this separation matters:
 *
 * Older MiniAgent code mixed policy in multiple places:
 * - TaskClassifier said one thing.
 * - AgentRunPlan changed attempts/tokens.
 * - SafeThoughtExecutor re-detected "serious code" using keywords.
 * - Worker sometimes used text calls with JSON prompts.
 *
 * That made the runtime unpredictable. A hard code task could become four long
 * fallback attempts, then repair could fall back into structured JSON and undo
 * the freeform fix.
 *
 * This class now keeps the pipeline coherent:
 *
 * - It never reclassifies the prompt.
 * - It uses AgentRunPlan for fallback strength.
 * - It passes AgentRunPlan into Worker generation and Worker repair.
 * - It limits fallback model count for large freeform stages so one request
 * does
 * not silently become a 6-10 minute chain.
 * - It treats "summary too long" as non-fatal for large code/freeform answers
 * when the output is otherwise useful.
 */
public class SafeThoughtExecutor {

        private final MiniAgentWorker worker;
        private final MiniAgentEvaluator evaluator;
        private final ModelFallbackPolicy fallbackPolicy;
        private final AgentTraceLogger traceLogger;
        private final DraftSanityValidator draftSanityValidator;

        /**
         * Creates the guarded stage executor used by Agent.deepThink().
         *
         * The constructor is intentionally strict about Worker and Evaluator because
         * those are mandatory execution dependencies. ModelFallbackPolicy and
         * AgentTraceLogger can be replaced with defaults because they are support
         * services rather than core execution engines.
         */
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
         * Preferred first-draft generation entry point.
         *
         * SafeThoughtExecutor does not decide whether the task is "serious code".
         * It asks AgentRunPlan whether this is a protected/high-attention task and
         * then chooses normal or strong fallback accordingly.
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

                models = selectStageModelCandidates("generation", models, plan);

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
                                models,
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
                                                temperature,
                                                plan),
                                (model, response) -> validateStructuredResponse(
                                                "generation",
                                                model,
                                                attemptNumber,
                                                response,
                                                sanityContext,
                                                plan));
        }

        /**
         * Backward-compatible generation overload.
         *
         * Older callers still compile. Without AgentRunPlan, this class intentionally
         * uses conservative normal behavior instead of trying to guess task type from
         * keywords.
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
         * Preferred repair entry point.
         *
         * Critical coherence rule:
         *
         * If the first draft was a freeform/code answer, repair must stay freeform.
         * Otherwise MiniAgent can generate a good direct code answer and then corrupt
         * it by forcing the repair stage back into JSON.
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

                models = selectStageModelCandidates("repair", models, plan);

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
                                models,
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
                                                dataset == null ? Collections.emptyMap() : dataset,
                                                null,
                                                plan),
                                (model, response) -> validateStructuredResponse(
                                                "repair",
                                                model,
                                                attemptNumber,
                                                response,
                                                sanityContext,
                                                plan));
        }

        /**
         * Backward-compatible repair overload.
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
         * Preferred evaluation entry point.
         *
         * Evaluation can still use a structured critic. The critic is supposed to
         * return scores/fixes, not the full final code. The important thing here is
         * that critic fallback strength is also plan-driven, not code-keyword driven.
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

                models = selectStageModelCandidates("evaluation", models, plan);

                logPolicyDecision(
                                runId,
                                userId,
                                "evaluation",
                                preferredModel,
                                protectedQualityTask,
                                models,
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
         * Backward-compatible evaluation overload.
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
         * Shared fallback loop.
         *
         * This method is deliberately dumb. It does not know whether a task is code,
         * medical, research, architecture, or normal chat. By the time execution gets
         * here, the candidate model list has already been selected and trimmed.
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
         * Limits model fan-out per stage.
         *
         * This is important for commercial latency.
         *
         * If GPT-5.x worker generation is allowed 115 seconds and the fallback list
         * contains four models, the "one attempt" stage can still become a many-minute
         * request. That is exactly the kind of behavior users perceive as broken.
         *
         * Current policy:
         *
         * - Large freeform generation:
         * Try only the selected first model. If it times out, report failure upward.
         * Do not silently burn minutes on multiple weaker/alternate generators.
         *
         * - Large freeform repair:
         * Same rule. One bounded repair call.
         *
         * - Evaluation:
         * Allow up to two critics because critic calls should be smaller and quicker.
         *
         * - Normal tasks:
         * Allow up to two candidates.
         */
        private List<String> selectStageModelCandidates(
                        String stage,
                        List<String> models,
                        AgentRunPlan plan) {
                if (models == null || models.isEmpty()) {
                        return Collections.emptyList();
                }

                int maxCandidates = 2;

                if (plan != null && plan.shouldUseFreeformWorkerOutput()) {
                        if ("generation".equals(stage) || "repair".equals(stage)) {
                                maxCandidates = 1;
                        } else if ("evaluation".equals(stage)) {
                                maxCandidates = 2;
                        }
                }

                LinkedHashSet<String> unique = new LinkedHashSet<>();

                for (String model : models) {
                        if (model == null || model.isBlank()) {
                                continue;
                        }

                        unique.add(model.trim());

                        if (unique.size() >= maxCandidates) {
                                break;
                        }
                }

                return new ArrayList<>(unique);
        }

        /**
         * Validates generated/repaired StructuredResponse.
         *
         * The response may have come from:
         *
         * - structured JSON mode, where the model itself emitted StructuredResponse
         * - freeform text mode, where MiniAgentWorker wrapped direct text locally
         *
         * This validator treats both as normal StructuredResponse objects.
         */
        private ThoughtFailureRecord validateStructuredResponse(
                        String stage,
                        String model,
                        int attemptNumber,
                        StructuredResponse response,
                        DraftSanityValidator.DraftSanityContext sanityContext,
                        AgentRunPlan plan) {
                if (response == null) {
                        return ThoughtFailureRecord.of(
                                        "repair".equals(stage) ? ThoughtFailureType.REPAIR_FAILED
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
                                        "repair".equals(stage) ? ThoughtFailureType.REPAIR_FAILED
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
                        if (shouldIgnoreNonFatalSanityFailureForLargeFreeform(plan, sanity, response)) {
                                return null;
                        }

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
         * Downgrades length-only sanity failures for large code/freeform answers.
         *
         * A common bad loop is:
         *
         * User: "Generate complete code."
         * Worker: generates long code.
         * Validator: fails with SUMMARY_TOO_LONG.
         * Repair: compresses/truncates the code.
         *
         * That is wrong. For code generation, long output is often the desired result.
         * Length can be a warning, but it should not kill an otherwise useful draft.
         */
        private boolean shouldIgnoreNonFatalSanityFailureForLargeFreeform(
                        AgentRunPlan plan,
                        DraftSanityValidator.DraftSanityResult sanity,
                        StructuredResponse response) {
                if (plan == null || sanity == null || response == null) {
                        return false;
                }

                if (!plan.shouldUseFreeformWorkerOutput()) {
                        return false;
                }

                String compact = safe(sanity.compactSummary()).toUpperCase(Locale.ROOT);

                boolean mentionsLength = compact.contains("SUMMARY_TOO_LONG")
                                || compact.contains("TOO_LONG")
                                || compact.contains("TOO LONG");

                if (!mentionsLength) {
                        return false;
                }

                /*
                 * Do not ignore serious code-quality failures. Missing code, placeholder
                 * content, raw JSON leakage, or broken fences are different from length.
                 */
                boolean mentionsHardFailure = compact.contains("EXPECTED_CODE_MISSING")
                                || compact.contains("PLACEHOLDER_CONTENT")
                                || compact.contains("RAW_JSON_LEAK")
                                || compact.contains("UNCLOSED_CODE_FENCE")
                                || compact.contains("EMPTY");

                if (mentionsHardFailure) {
                        return false;
                }

                String visible = safe(response.getSummary());

                return visible.length() >= 1000;
        }

        /**
         * Validates critic output.
         *
         * A critic response with all zero scores and no rationale/fixes is almost
         * always malformed JSON or an empty model response, not a real critique.
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
         * Decides whether this stage should use protected fallback.
         *
         * This is not a new classifier. It only translates AgentRunPlan into fallback
         * strength.
         */
        private boolean shouldUseProtectedFallback(AgentRunPlan plan) {
                if (plan == null) {
                        return false;
                }

                if (plan.shouldUseFreeformWorkerOutput()) {
                        return true;
                }

                if (plan.getMaxAnswerTokens() >= 5500) {
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
         * Converts AgentRunPlan into the validator's numeric complexity scale.
         *
         * This is not a second classifier. It only translates already-decided plan
         * values into the validator's older interface.
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

                /*
                 * Current one-shot cap is around 7000, so the old 12000/8000 thresholds
                 * no longer make sense.
                 */
                if (plan.getMaxAnswerTokens() >= 6500) {
                        complexity = Math.max(complexity, 9);
                } else if (plan.getMaxAnswerTokens() >= 5500) {
                        complexity = Math.max(complexity, 8);
                } else if (plan.getMaxAnswerTokens() >= 4000) {
                        complexity = Math.max(complexity, 7);
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
                        List<String> selectedModels,
                        AgentRunPlan plan) {
                if (traceLogger == null) {
                        return;
                }

                Map<String, Object> data = planDebugData(plan);
                data.put("preferredModel", safeModel(preferredModel));
                data.put("selectedModels", selectedModels == null ? Collections.emptyList() : selectedModels);
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
                data.put(
                                "maxWallClockMs",
                                plan.getMaxWallClockTime() == null ? 0 : plan.getMaxWallClockTime().toMillis());
                data.put("freeformWorkerOutput", plan.shouldUseFreeformWorkerOutput());
                data.put("skipLargeAnswerSynthesis", plan.shouldSkipLargeAnswerSynthesis());

                TaskClassifier.TaskClassification classification = plan.getClassification();

                if (classification != null) {
                        data.put("taskType", classification.taskType == null ? "" : classification.taskType.name());
                        data.put("difficulty",
                                        classification.difficulty == null ? "" : classification.difficulty.name());
                        data.put(
                                        "pipeline",
                                        classification.recommendedPipeline == null ? ""
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

        /**
         * Returns true when a list contains at least one item.
         *
         * The evaluator model may return empty lists for fixes/issues. This helper
         * keeps the null/empty checks readable in validation code.
         */
        private boolean hasItems(List<?> values) {
                return values != null && !values.isEmpty();
        }

        /**
         * Clamps a numeric value into a closed interval.
         *
         * SafeThoughtExecutor uses this only for derived diagnostic values such as
         * complexity. Runtime attempts and answer budgets are owned by AgentRunPlan.
         */
        private int clamp(int value, int min, int max) {
                return Math.max(min, Math.min(max, value));
        }

        /**
         * Normalizes model names for logs and failure records.
         */
        private String safeModel(String model) {
                return model == null || model.isBlank() ? "unknown" : model.trim();
        }

        /**
         * Converts nullable text into an empty string.
         *
         * This avoids scattered null checks when building prompts, traces, and
         * compact failure messages.
         */
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