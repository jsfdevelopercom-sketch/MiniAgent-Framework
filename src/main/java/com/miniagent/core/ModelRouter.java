package com.miniagent.core;

import java.util.Locale;

/**
 * ModelRouter converts TaskClassification into concrete model choices.
 *
 * Responsibilities:
 * - Keep Agent orchestration clean.
 * - Keep model selection in one predictable place.
 * - Choose generator, critic, repair, and synthesizer models.
 * - Avoid provider-invalid temperature settings for reasoning/high-control
 * models.
 *
 * Important:
 * Some OpenAI reasoning/high-control models reject custom temperature values.
 * For those models this router emits null temperature values. That way even if
 * a downstream worker accidentally forwards route temperatures, the OpenAI HTTP
 * payload builder has no custom temperature value to send.
 */
public class ModelRouter {

    private static final String OPENAI_CHEAP = ModelConstants.GPT_5_NANO;
    private static final String OPENAI_STRONG = ModelConstants.GPT_5_4;

    private static final String GEMINI_CHEAP = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private static final String GEMINI_STRONG = ModelConstants.GEMINI_3_1_PRO_PREVIEW;

    private static final String CLAUDE_CHEAP = ModelConstants.CLAUDE_HAIKU_4_5;
    private static final String CLAUDE_STRONG = ModelConstants.CLAUDE_SONNET_4_6;

    private static final Double DEFAULT_GENERATOR_TEMPERATURE = 0.2;
    private static final Double LOW_GENERATOR_TEMPERATURE = 0.1;
    private static final Double CRITIC_TEMPERATURE = 0.0;
    private static final Double REPAIR_TEMPERATURE = 0.1;
    private static final Double SYNTHESIS_TEMPERATURE = 0.0;

    /**
     * Routes models based on task classification.
     *
     * The returned route is already sanitized:
     * - GPT-5 / o-series / deep-research models get null temperatures.
     * - Gemini / Claude / normal chat models may keep their intended temperatures.
     */
    public ModelRoute route(TaskClassifier.TaskClassification classification, String requestedModel) {
        if (classification == null) {
            return easyDefault();
        }

        if (requestedModel != null && !requestedModel.isBlank() && !"mixed".equalsIgnoreCase(requestedModel)) {
            return singleModelRoute(requestedModel.trim(), classification);
        }

        TaskClassifier.TaskDifficulty difficulty = classification.difficulty;
        TaskClassifier.TaskType taskType = classification.taskType;
        TaskClassifier.RecommendedPipeline pipeline = classification.recommendedPipeline;

        if (pipeline == TaskClassifier.RecommendedPipeline.DIRECT_ANSWER) {
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

        if (pipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT) {
            return createRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    DEFAULT_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        if (taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                || taskType == TaskClassifier.TaskType.CODE_GENERATION
                || taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN) {

            if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
                return createRoute(
                        OPENAI_STRONG,
                        CLAUDE_STRONG,
                        OPENAI_STRONG,
                        OPENAI_CHEAP,
                        DEFAULT_GENERATOR_TEMPERATURE,
                        CRITIC_TEMPERATURE,
                        REPAIR_TEMPERATURE,
                        SYNTHESIS_TEMPERATURE);
            }

            return createRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    DEFAULT_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        if (taskType == TaskClassifier.TaskType.MEDICAL
                || taskType == TaskClassifier.TaskType.RESEARCH) {

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

        if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
            return createRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    DEFAULT_GENERATOR_TEMPERATURE,
                    CRITIC_TEMPERATURE,
                    REPAIR_TEMPERATURE,
                    SYNTHESIS_TEMPERATURE);
        }

        if (difficulty == TaskClassifier.TaskDifficulty.MEDIUM) {
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

        return easyDefault();
    }

    /**
     * Default route for easy or unclassified tasks.
     *
     * Even though this route uses cheaper OpenAI models, temperature is still
     * sanitized before the ModelRoute is returned.
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
     * Builds a route when the caller requested a specific model.
     *
     * Serious code and architecture tasks are deliberately upgraded to the
     * strong OpenAI model instead of honoring a weak frontend model choice.
     */
    private ModelRoute singleModelRoute(String model, TaskClassifier.TaskClassification classification) {
        String finalModel = model;
        Double generatorTemperature = DEFAULT_GENERATOR_TEMPERATURE;

        if (classification != null
                && (classification.taskType == TaskClassifier.TaskType.MEDICAL
                        || classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                        || classification.taskType == TaskClassifier.TaskType.CODE_GENERATION)) {
            generatorTemperature = LOW_GENERATOR_TEMPERATURE;
        }

        if (classification != null
                && (classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                        || classification.taskType == TaskClassifier.TaskType.CODE_GENERATION
                        || classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN)) {
            finalModel = OPENAI_STRONG;
        }

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
     * Central ModelRoute factory.
     *
     * Never call the ModelRoute constructor directly from routing branches.
     * This method is the safety gate that removes unsupported temperatures.
     */
 /**
 * Central ModelRoute factory.
 *
 * Do not return null temperatures here because the existing ModelRoute class
 * stores primitive double fields. Returning null causes Java auto-unboxing
 * to throw NullPointerException before DeepThink even starts.
 *
 * For high-control OpenAI models, we use 1.0 as a safe neutral value at the
 * route level. The OpenAI HTTP client should still omit temperature entirely
 * for these models using its supportsTemperature(...) guard.
 */
private ModelRoute createRoute(
        String generatorModel,
        String criticModel,
        String repairModel,
        String synthesizerModel,
        Double generatorTemperature,
        Double criticTemperature,
        Double repairTemperature,
        Double synthesizerTemperature) {

    return new ModelRoute(
            generatorModel,
            criticModel,
            repairModel,
            synthesizerModel,
            sanitizeTemperature(generatorModel, generatorTemperature),
            sanitizeTemperature(criticModel, criticTemperature),
            sanitizeTemperature(repairModel, repairTemperature),
            sanitizeTemperature(synthesizerModel, synthesizerTemperature));
}

/**
 * Returns a safe primitive temperature value for ModelRoute.
 *
 * Important:
 * ModelRoute currently uses primitive double fields, so this method must
 * never return null.
 */
private double sanitizeTemperature(String model, Double temperature) {
    if (temperature == null) {
        return 1.0;
    }

    if (!supportsTemperature(model)) {
        return 1.0;
    }

    if (temperature.isNaN() || temperature.isInfinite()) {
        return 1.0;
    }

    if (temperature < 0.0) {
        return 0.0;
    }

    if (temperature > 2.0) {
        return 2.0;
    }

    return temperature;
}

/**
 * Returns whether a model can safely receive a custom temperature field.
 *
 * GPT-5 family, o-series, and deep-research models should not receive
 * custom temperature values in OpenAI request payloads.
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
}
