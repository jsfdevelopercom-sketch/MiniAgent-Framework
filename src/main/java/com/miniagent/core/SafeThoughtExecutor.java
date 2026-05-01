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
 * - deterministic draft sanity validation
 *
 * This class should be used by Agent instead of calling MiniAgentWorker
 * directly.
 *
 * Important production rule:
 * Large code generation, serious debugging, architecture, and implementation
 * tasks must not quietly fall from strong models into nano/cheap models. Cheap
 * models are useful for title generation, light synthesis, and simple recovery,
 * but they can collapse a complete app request into a toy demo. This executor
 * therefore selects strong fallback paths whenever the current stage looks like
 * serious code or software-engineering work.
 */
public class SafeThoughtExecutor {

        private final MiniAgentWorker worker;
        private final MiniAgentEvaluator evaluator;
        private final ModelFallbackPolicy fallbackPolicy;
        private final AgentTraceLogger traceLogger;
        private final DraftSanityValidator draftSanityValidator;

        /**
         * Creates a fault-tolerant executor for MiniAgent thought stages.
         *
         * The worker is responsible for draft/repair generation. The evaluator is
         * responsible for critic scoring. The fallback policy controls which models
         * are tried when a provider fails, returns malformed output, or fails sanity
         * validation.
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
         * Executes the initial generation stage.
         *
         * This method is deliberately model-aware at the fallback-policy level.
         * For normal tasks, provider fallback can use cheap models. For serious
         * code/software work, it keeps generation on strong models only. Otherwise,
         * a transient GPT-5.4 failure can cause the first "valid" draft to come from
         * a nano-class model, which is exactly how production-code requests become
         * small toy examples.
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
                boolean seriousCodeContext = isGenerationCodeContext(
                                domainContext,
                                taskInstructions,
                                dataset,
                                liveInjections,
                                history);

                List<String> models = seriousCodeContext
                                ? fallbackPolicy.strongGenerationFallbacks(preferredModel)
                                : fallbackPolicy.generationFallbacks(preferredModel);

                DraftSanityValidator.DraftSanityContext sanityContext = buildSanityContext(
                                taskInstructions,
                                "generation",
                                seriousCodeContext,
                                seriousCodeContext ? 10 : 0);

                logStage(
                                runId,
                                userId,
                                AgentTraceEventType.WARNING,
                                "generation",
                                seriousCodeContext
                                                ? "Using strong generation fallback policy for serious code/software task."
                                                : "Using normal generation fallback policy.",
                                preferredModel,
                                0);

                return executeWithFallback(
                                runId,
                                userId,
                                "generation",
                                models,
                                attemptNumber,
                                model -> worker.generateDraft(
                                                model,
                                                domainContext == null ? "" : domainContext,
                                                taskInstructions == null ? "" : taskInstructions,
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
         * Attempts to repair a malfunctioning draft using targeted fixes.
         *
         * For code repair, fallback must stay on strong models. Repair is not
         * summarization. A cheap model can easily remove features, collapse files
         * into a demo, invent placeholder functions, or damage syntax.
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
                boolean seriousCodeContext = isRepairCodeContext(
                                previousDraft,
                                factualityFixes,
                                structuralFixes,
                                missingInstructions,
                                dataset);

                List<String> models = seriousCodeContext
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
                                seriousCodeContext,
                                seriousCodeContext ? 10 : 0);

                logStage(
                                runId,
                                userId,
                                AgentTraceEventType.WARNING,
                                "repair",
                                seriousCodeContext
                                                ? "Using strong repair fallback policy for serious code/software task."
                                                : "Using normal repair fallback policy.",
                                preferredModel,
                                0);

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
         * Evaluates a draft against rigid system rules and dataset facts.
         *
         * For serious code/software tasks, critic fallback should also remain strong.
         * A weak critic can produce fake middle scores, miss missing features, or let
         * stub code pass because it only sees that the output is syntactically
         * code-like.
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
                boolean seriousCodeContext = isEvaluationCodeContext(
                                draft,
                                rigidRules,
                                dataset,
                                liveInjections,
                                history);

                List<String> models = seriousCodeContext
                                ? fallbackPolicy.strongCriticFallbacks(preferredModel)
                                : fallbackPolicy.criticFallbacks(preferredModel);

                logStage(
                                runId,
                                userId,
                                AgentTraceEventType.WARNING,
                                "evaluation",
                                seriousCodeContext
                                                ? "Using strong critic fallback policy for serious code/software task."
                                                : "Using normal critic fallback policy.",
                                preferredModel,
                                0);

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
         * Core fault-tolerant execution loop used by all thought stages.
         *
         * The loop attempts each candidate model in order. Each output is passed to
         * the stage-specific validator. A model is accepted only when the validator
         * returns null. Otherwise, the failure is logged and the next model is tried.
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
                                                        "Thought stage recovered/succeeded using model: "
                                                                        + safeModel(model),
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
         * Validates a generated/repaired StructuredResponse.
         *
         * The important correction is that DraftSanityValidator now receives a real
         * context instead of empty placeholders. This allows the validator to judge
         * code output against the actual seriousness of the request.
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
         * Empty all-zero critic output is treated as malformed unless the critic
         * provides useful rationale or repair instructions.
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

        /**
         * Builds the sanity context for deterministic draft validation.
         *
         * The existing DraftSanityContext accepts textual task fields, a serious-code
         * boolean, and a complexity integer. We feed it the strongest context this
         * executor has available instead of empty placeholders.
         */
        private DraftSanityValidator.DraftSanityContext buildSanityContext(
                        String taskText,
                        String stage,
                        boolean seriousCodeContext,
                        int expectedComplexity) {
                String query = taskText == null ? "" : taskText;
                String taskKind = stage == null ? "" : stage;

                return DraftSanityValidator.DraftSanityContext.of(
                                query,
                                taskKind,
                                seriousCodeContext,
                                Math.max(0, expectedComplexity));
        }

        /**
         * Detects serious code/software generation context.
         *
         * This intentionally combines domain context, task instructions, dataset,
         * live injections, and history because Agent may place the most important
         * routing details in different fields depending on the pathway.
         */
        private boolean isGenerationCodeContext(
                        String domainContext,
                        String taskInstructions,
                        Map<String, Object> dataset,
                        List<String> liveInjections,
                        List<Map<String, String>> history) {
                String combined = combineText(
                                domainContext,
                                taskInstructions,
                                mapToText(dataset),
                                listToText(liveInjections),
                                historyToText(history));

                return isSeriousSoftwareText(combined);
        }

        /**
         * Detects serious code/software repair context from the previous draft and
         * critic instructions.
         */
        private boolean isRepairCodeContext(
                        String previousDraft,
                        List<String> factualityFixes,
                        List<String> structuralFixes,
                        List<String> missingInstructions,
                        Map<String, Object> dataset) {
                String combined = combineText(
                                previousDraft,
                                listToText(factualityFixes),
                                listToText(structuralFixes),
                                listToText(missingInstructions),
                                mapToText(dataset));

                return isSeriousSoftwareText(combined);
        }

        /**
         * Detects serious code/software evaluation context.
         */
        private boolean isEvaluationCodeContext(
                        String draft,
                        List<String> rigidRules,
                        Map<String, Object> dataset,
                        List<String> liveInjections,
                        List<Map<String, String>> history) {
                String combined = combineText(
                                draft,
                                listToText(rigidRules),
                                mapToText(dataset),
                                listToText(liveInjections),
                                historyToText(history));

                return isSeriousSoftwareText(combined);
        }

        /**
         * Builds text for repair-stage sanity context.
         */
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

        /**
         * Broad detector for serious software/code contexts.
         *
         * This is not the primary task classifier. The model classifier/router has
         * already done that earlier. This detector is a safety guard used only to
         * choose strong fallback policy and meaningful sanity validation context.
         */
        private boolean isSeriousSoftwareText(String text) {
                if (text == null || text.isBlank()) {
                        return false;
                }

                String s = text.toLowerCase(Locale.ROOT);

                boolean softwareSignal = s.contains("code") ||
                                s.contains("coding") ||
                                s.contains("program") ||
                                s.contains("script") ||
                                s.contains("software") ||
                                s.contains("html") ||
                                s.contains("css") ||
                                s.contains("javascript") ||
                                s.contains("typescript") ||
                                s.contains("java") ||
                                s.contains("kotlin") ||
                                s.contains("python") ||
                                s.contains("c++") ||
                                s.contains("cpp") ||
                                s.contains("c#") ||
                                s.contains("csharp") ||
                                s.contains("go ") ||
                                s.contains("golang") ||
                                s.contains("rust") ||
                                s.contains("swift") ||
                                s.contains("php") ||
                                s.contains("ruby") ||
                                s.contains("sql") ||
                                s.contains("json") ||
                                s.contains("xml") ||
                                s.contains("yaml") ||
                                s.contains("gradle") ||
                                s.contains("maven") ||
                                s.contains("spring") ||
                                s.contains("android") ||
                                s.contains("compose") ||
                                s.contains("react") ||
                                s.contains("node") ||
                                s.contains("backend") ||
                                s.contains("frontend") ||
                                s.contains("api") ||
                                s.contains("server") ||
                                s.contains("editor") ||
                                s.contains("ide") ||
                                s.contains("debug") ||
                                s.contains("compile") ||
                                s.contains("runtime") ||
                                s.contains("function ") ||
                                s.contains("class ") ||
                                s.contains("<html") ||
                                s.contains("<script") ||
                                s.contains("<style");

                boolean seriousnessSignal = s.contains("complete") ||
                                s.contains("fully") ||
                                s.contains("professional") ||
                                s.contains("production") ||
                                s.contains("detailed") ||
                                s.contains("advanced") ||
                                s.contains("visual studio") ||
                                s.contains("vs code") ||
                                s.contains("full file") ||
                                s.contains("entire file") ||
                                s.contains("working") ||
                                s.contains("runnable") ||
                                s.contains("no stub") ||
                                s.contains("no placeholder") ||
                                s.contains("heavy") ||
                                s.contains("all features") ||
                                s.contains("connected");

                boolean answerAlreadyLooksLikeCode = s.contains("```") ||
                                s.contains("<!doctype html") ||
                                s.contains("public class") ||
                                s.contains("import ") ||
                                s.contains("package ") ||
                                s.contains("const ") ||
                                s.contains("let ") ||
                                s.contains("var ") ||
                                s.contains("document.getelementbyid") ||
                                s.contains("addeventlistener") ||
                                s.contains("localstorage") ||
                                s.contains("monaco.editor");

                return (softwareSignal && seriousnessSignal) || answerAlreadyLooksLikeCode;
        }

        /**
         * Combines nullable text chunks into one searchable string.
         */
        private String combineText(String... parts) {
                if (parts == null) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (String part : parts) {
                        if (part != null && !part.isBlank()) {
                                if (!builder.isEmpty()) {
                                        builder.append('\n');
                                }
                                builder.append(part);
                        }
                }

                return builder.toString();
        }

        /**
         * Converts a nullable list to text.
         */
        private String listToText(List<String> lines) {
                if (lines == null || lines.isEmpty()) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (String line : lines) {
                        if (line != null && !line.isBlank()) {
                                if (!builder.isEmpty()) {
                                        builder.append('\n');
                                }
                                builder.append(line);
                        }
                }

                return builder.toString();
        }

        /**
         * Converts history messages to compact text.
         */
        private String historyToText(List<Map<String, String>> history) {
                if (history == null || history.isEmpty()) {
                        return "";
                }

                StringBuilder builder = new StringBuilder();

                for (Map<String, String> item : history) {
                        if (item == null || item.isEmpty()) {
                                continue;
                        }

                        String role = safe(item.get("role"));
                        String content = safe(item.get("content"));

                        if (!content.isBlank()) {
                                if (!builder.isEmpty()) {
                                        builder.append('\n');
                                }
                                builder.append(role).append(": ").append(content);
                        }
                }

                return builder.toString();
        }

        /**
         * Converts a dataset map into compact searchable text.
         */
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
                                if (!builder.isEmpty()) {
                                        builder.append('\n');
                                }
                                builder.append(key).append(": ").append(value);
                        }
                }

                return builder.toString();
        }

        /**
         * Logs a failed thought stage.
         */
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

        /**
         * Logs non-failure stage progress.
         */
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

        /**
         * Null-safe model display.
         */
        private String safeModel(String model) {
                return model == null || model.isBlank() ? "unknown" : model;
        }

        /**
         * Null-safe string.
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