package com.miniagent.core;

import java.util.Locale;

/**
 * ModelRouter maps an already-sanitized TaskClassification to concrete models.
 *
 * This class intentionally does only model routing. It must not decide:
 *
 * - max attempts
 * - max answer tokens
 * - stage timeouts
 * - freeform-vs-structured output mode
 * - whether synthesis should be skipped
 *
 * Those decisions belong to TaskClassifier and AgentRunPlan. Keeping this class
 * narrow makes the MiniAgent flow predictable:
 *
 * TaskClassifier -> AgentRunPlan -> ModelRouter -> SafeThoughtExecutor ->
 * Worker/Evaluator/Synthesizer
 *
 * Production coding rule:
 * serious code and architecture should keep the strongest OpenAI generator
 * first.
 * Speed problems should be solved by stage-aware timeouts/token budgets and
 * freeform text mode, not by silently demoting hard coding to a weaker model.
 */
public class ModelRouter {

    private static final String OPENAI_CHEAP = ModelConstants.GPT_5_NANO;
    private static final String OPENAI_STRONG = ModelConstants.GPT_5_4;

    private static final String GEMINI_CHEAP = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private static final String GEMINI_STRONG = ModelConstants.GEMINI_3_1_PRO_PREVIEW;

    private static final String CLAUDE_CHEAP = ModelConstants.CLAUDE_HAIKU_4_5;
    private static final String CLAUDE_STRONG = ModelConstants.CLAUDE_SONNET_4_6;

    private static final double DEFAULT_GENERATOR_TEMPERATURE = 0.2d;
    private static final double LOW_GENERATOR_TEMPERATURE = 0.1d;
    private static final double CRITIC_TEMPERATURE = 0.0d;
    private static final double REPAIR_TEMPERATURE = 0.1d;
    private static final double SYNTHESIS_TEMPERATURE = 0.0d;

    /*
     * ModelRoute still stores primitive double values. For models where a custom
     * temperature must be omitted at HTTP level, this placeholder is only a route
     * object value. Provider clients must still omit temperature from requests
     * for GPT-5/o-series/deep-research style models.
     */
    private static final double TEMPERATURE_OMITTED_PLACEHOLDER = 1.0d;

    /**
     * Creates a stage route for the task.
     *
     * The method consumes the classification produced by TaskClassifier. It does
     * not look at the raw user prompt and does not repeat task detection. That is
     * important because code is only one kind of hard task; research, medical,
     * architecture, and tool tasks also need careful routing.
     */
    public ModelRoute route(TaskClassifier.TaskClassification classification, String requestedModel) {
        if (classification == null) {
            /*
             * Missing classification should be rare. Keep the system alive with a
             * cheap/default route rather than crashing before the caller can produce
             * an error response.
             */
            return easyDefault();
        }

        if (hasExplicitRequestedModel(requestedModel)) {
            /*
             * A user/UI-selected model is honored for ordinary work. For serious
             * code/architecture, singleModelRoute will upgrade weak choices so Deep
             * mode cannot be accidentally sabotaged by a cheap frontend selection.
             */
            return singleModelRoute(requestedModel.trim(), classification);
        }

        TaskClassifier.RecommendedPipeline pipeline = safePipeline(classification);
        TaskClassifier.TaskType taskType = safeTaskType(classification);
        TaskClassifier.TaskDifficulty difficulty = safeDifficulty(classification);

        if (pipeline == TaskClassifier.RecommendedPipeline.DIRECT_ANSWER
                || pipeline == TaskClassifier.RecommendedPipeline.ASK_USER_CLARIFICATION
                || pipeline == TaskClassifier.RecommendedPipeline.REFUSE) {
            return easyDefault();
        }

        if (pipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT) {
            return routeToolTask(taskType, difficulty);
        }

        if (isCodeOrArchitectureTask(taskType)) {
            return routeCodeOrArchitectureTask(difficulty);
        }

        if (isMedicalOrResearchTask(taskType)) {
            return routeMedicalOrResearchTask(difficulty);
        }

        if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
            return hardGeneralRoute();
        }

        if (difficulty == TaskClassifier.TaskDifficulty.MEDIUM) {
            return mediumGeneralRoute();
        }

        return easyDefault();
    }

    /**
     * Route for easy/direct tasks.
     *
     * These tasks should stay cheap and fast. Deep hard-code safeguards are not
     * needed here because AgentRunPlan and SafeThoughtExecutor will not send these
     * tasks through a large freeform worker path.
     */
    private ModelRoute easyDefault() {
        return createRoute(
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                DEFAULT_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Route for tool-heavy tasks.
     *
     * Tool tasks can require reliable reasoning before and after observations.
     * Hard or code-like tool tasks use the strong route. Simpler tool tasks keep
     * the strong generator but cheap critic/synthesis to avoid unnecessary cost.
     */
    private ModelRoute routeToolTask(
            TaskClassifier.TaskType taskType,
            TaskClassifier.TaskDifficulty difficulty) {
        if (difficulty == TaskClassifier.TaskDifficulty.HARD || isCodeOrArchitectureTask(taskType)) {
            return createRoute(
                    OPENAI_STRONG,
                    CLAUDE_STRONG,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    LOW_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        return createRoute(
                OPENAI_STRONG,
                OPENAI_CHEAP,
                OPENAI_STRONG,
                OPENAI_CHEAP,
                LOW_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Route for code generation, debugging, and architecture.
     *
     * This is the path that matters for the Agent-Nero "serious code" failure:
     * generator and repair stay on the strong OpenAI model, while the critic is
     * Claude Sonnet for hard work. OutputSynthesizer should later skip rewriting
     * large code, so the synthesizer model remains cheap.
     */
    private ModelRoute routeCodeOrArchitectureTask(TaskClassifier.TaskDifficulty difficulty) {
        if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
            return createRoute(
                    OPENAI_STRONG,
                    CLAUDE_STRONG,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    LOW_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        return createRoute(
                OPENAI_STRONG,
                OPENAI_CHEAP,
                OPENAI_STRONG,
                OPENAI_CHEAP,
                LOW_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Route for medical and research tasks.
     *
     * These are not code, but they can be equally high-stakes. Hard tasks get a
     * stronger critic so a weak JSON evaluator does not pass a brittle answer.
     */
    private ModelRoute routeMedicalOrResearchTask(TaskClassifier.TaskDifficulty difficulty) {
        if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
            return createRoute(
                    OPENAI_STRONG,
                    CLAUDE_STRONG,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    LOW_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        return createRoute(
                OPENAI_STRONG,
                OPENAI_CHEAP,
                OPENAI_STRONG,
                OPENAI_CHEAP,
                LOW_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Route for hard non-code/non-medical general reasoning.
     *
     * The generator remains strong, but critic/synth can stay cheaper unless the
     * classifier has identified a more specific high-risk task type.
     */
    private ModelRoute hardGeneralRoute() {
        return createRoute(
                OPENAI_STRONG,
                OPENAI_CHEAP,
                OPENAI_STRONG,
                OPENAI_CHEAP,
                LOW_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Route for medium general work.
     *
     * This is deliberately economical. If classification says the task is medium
     * but code/medical/research-specific, earlier branch methods already handled
     * it.
     */
    private ModelRoute mediumGeneralRoute() {
        return createRoute(
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                DEFAULT_GENERATOR_TEMPERATURE,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Builds a route when the caller explicitly selected a model.
     *
     * Deep coding/architecture requests are upgraded to the strong OpenAI model
     * even if the caller passed a cheap model. This keeps DeepThink semantics
     * sane: a user asking for serious code should not receive toy output because
     * a frontend drop-down sent nano/flash by accident.
     */
    private ModelRoute singleModelRoute(
            String requestedModel,
            TaskClassifier.TaskClassification classification) {
        String finalModel = cleanModel(requestedModel, OPENAI_CHEAP);

        TaskClassifier.TaskType taskType = safeTaskType(classification);
        TaskClassifier.TaskDifficulty difficulty = safeDifficulty(classification);

        boolean seriousTask = difficulty == TaskClassifier.TaskDifficulty.HARD
                || isCodeOrArchitectureTask(taskType)
                || taskType == TaskClassifier.TaskType.MEDICAL
                || taskType == TaskClassifier.TaskType.RESEARCH;

        if (isCodeOrArchitectureTask(taskType)) {
            finalModel = OPENAI_STRONG;
        }

        double generatorTemperature = seriousTask
                ? LOW_GENERATOR_TEMPERATURE
                : DEFAULT_GENERATOR_TEMPERATURE;

        return createRoute(
                finalModel,
                finalModel,
                finalModel,
                OPENAI_CHEAP,
                generatorTemperature,
                CRITIC_TEMPERATURE,
                REPAIR_TEMPERATURE,
                SYNTHESIS_TEMPERATURE);
    }

    /**
     * Central route factory.
     *
     * All branch methods call this one place so temperature normalization and
     * model-name fallback logic cannot diverge across routes.
     */
    private ModelRoute createRoute(
            String generatorModel,
            String criticModel,
            String repairModel,
            String synthesizerModel,
            double generatorTemperature,
            double criticTemperature,
            double repairTemperature,
            double synthesizerTemperature) {
        return new ModelRoute(
                cleanModel(generatorModel, OPENAI_CHEAP),
                cleanModel(criticModel, OPENAI_CHEAP),
                cleanModel(repairModel, OPENAI_CHEAP),
                cleanModel(synthesizerModel, OPENAI_CHEAP),
                sanitizeTemperature(generatorModel, generatorTemperature),
                sanitizeTemperature(criticModel, criticTemperature),
                sanitizeTemperature(repairModel, repairTemperature),
                sanitizeTemperature(synthesizerModel, synthesizerTemperature));
    }

    /**
     * Produces a safe primitive temperature for ModelRoute.
     *
     * ModelRoute cannot hold null. For models that should not receive custom
     * temperature, return a placeholder and rely on the HTTP client to omit the
     * field from the real provider payload.
     */
    private double sanitizeTemperature(String model, double temperature) {
        if (!supportsTemperature(model)) {
            return TEMPERATURE_OMITTED_PLACEHOLDER;
        }

        if (Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            return DEFAULT_GENERATOR_TEMPERATURE;
        }

        if (temperature < 0.0d) {
            return 0.0d;
        }

        if (temperature > 2.0d) {
            return 2.0d;
        }

        return temperature;
    }

    /**
     * Returns whether the model should receive a custom temperature field.
     *
     * This is a routing-side mirror of the provider-client check. The client is
     * still the final authority because it knows the exact request shape.
     */
    private boolean supportsTemperature(String model) {
        if (model == null || model.isBlank()) {
            return true;
        }

        String normalized = model.toLowerCase(Locale.ROOT).trim();

        return !(normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4")
                || normalized.contains("deep-research"));
    }

    /**
     * Determines whether a requested model should override automatic routing.
     */
    private boolean hasExplicitRequestedModel(String requestedModel) {
        return requestedModel != null
                && !requestedModel.isBlank()
                && !"mixed".equalsIgnoreCase(requestedModel.trim())
                && !"auto".equalsIgnoreCase(requestedModel.trim());
    }

    /**
     * Identifies task types that require serious code/architecture routing.
     */
    private boolean isCodeOrArchitectureTask(TaskClassifier.TaskType taskType) {
        return taskType == TaskClassifier.TaskType.CODE_GENERATION
                || taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                || taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN;
    }

    /**
     * Identifies task types that need high-attention reasoning but are not code.
     */
    private boolean isMedicalOrResearchTask(TaskClassifier.TaskType taskType) {
        return taskType == TaskClassifier.TaskType.MEDICAL
                || taskType == TaskClassifier.TaskType.RESEARCH;
    }

    /**
     * Null-safe task type extraction.
     */
    private TaskClassifier.TaskType safeTaskType(TaskClassifier.TaskClassification classification) {
        if (classification == null || classification.taskType == null) {
            return TaskClassifier.TaskType.UNKNOWN;
        }

        return classification.taskType;
    }

    /**
     * Null-safe difficulty extraction.
     */
    private TaskClassifier.TaskDifficulty safeDifficulty(TaskClassifier.TaskClassification classification) {
        if (classification == null || classification.difficulty == null) {
            return TaskClassifier.TaskDifficulty.MEDIUM;
        }

        return classification.difficulty;
    }

    /**
     * Null-safe pipeline extraction.
     */
    private TaskClassifier.RecommendedPipeline safePipeline(TaskClassifier.TaskClassification classification) {
        if (classification == null || classification.recommendedPipeline == null) {
            return TaskClassifier.RecommendedPipeline.THINK_CRITIC_REPAIR;
        }

        return classification.recommendedPipeline;
    }

    /**
     * Returns a trimmed model value or a deterministic fallback.
     */
    private String cleanModel(String model, String fallback) {
        if (model == null || model.isBlank()) {
            return fallback;
        }

        return model.trim();
    }
}