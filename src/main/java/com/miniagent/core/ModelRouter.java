package com.miniagent.core;

/**
 * ModelRouter converts TaskClassification into concrete model choices.
 *
 * It keeps the Agent orchestration clean:
 * - Agent controls execution.
 * - TaskClassifier classifies.
 * - ModelRouter chooses models.
 */
public class ModelRouter {

    private static final String OPENAI_CHEAP = "gpt-5-nano-2025-08-07";
    private static final String OPENAI_STRONG = "gpt-5.4-2026-03-05";

    private static final String GEMINI_CHEAP = "gemini-3.1-flash-lite-preview";
    private static final String GEMINI_STRONG = "gemini-3.1-pro-preview";

    private static final String CLAUDE_CHEAP = "claude-haiku-4-5-20251001";
    private static final String CLAUDE_STRONG = "claude-sonnet-4-6";

    /**
     * Routes models based on task classification.
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
            return new ModelRoute(
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    0.2,
                    0.0,
                    0.1,
                    0.0);
        }

        if (pipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT) {
            return new ModelRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    0.2,
                    0.0,
                    0.1,
                    0.0);
        }

        if (taskType == TaskClassifier.TaskType.CODE_DEBUGGING ||
                taskType == TaskClassifier.TaskType.CODE_GENERATION ||
                taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN) {

            if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
                return new ModelRoute(
                        OPENAI_STRONG,
                        CLAUDE_STRONG,
                        OPENAI_STRONG,
                        OPENAI_CHEAP,
                        0.2,
                        0.0,
                        0.1,
                        0.0);
            }

            return new ModelRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    0.2,
                    0.0,
                    0.1,
                    0.0);
        }

        if (taskType == TaskClassifier.TaskType.MEDICAL ||
                taskType == TaskClassifier.TaskType.RESEARCH) {

            if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
                return new ModelRoute(
                        OPENAI_STRONG,
                        CLAUDE_STRONG,
                        OPENAI_STRONG,
                        OPENAI_CHEAP,
                        0.1,
                        0.0,
                        0.1,
                        0.0);
            }

            return new ModelRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    0.1,
                    0.0,
                    0.1,
                    0.0);
        }

        if (difficulty == TaskClassifier.TaskDifficulty.HARD) {
            return new ModelRoute(
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    OPENAI_STRONG,
                    OPENAI_CHEAP,
                    0.2,
                    0.0,
                    0.1,
                    0.0);
        }

        if (difficulty == TaskClassifier.TaskDifficulty.MEDIUM) {
            return new ModelRoute(
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    OPENAI_CHEAP,
                    0.2,
                    0.0,
                    0.1,
                    0.0);
        }

        return easyDefault();
    }

    private ModelRoute easyDefault() {
        return new ModelRoute(
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                OPENAI_CHEAP,
                0.2,
                0.0,
                0.1,
                0.0);
    }

    private ModelRoute singleModelRoute(String model, TaskClassifier.TaskClassification classification) {
        double generatorTemp = 0.2;

        if (classification != null &&
                (classification.taskType == TaskClassifier.TaskType.MEDICAL ||
                        classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING ||
                        classification.taskType == TaskClassifier.TaskType.CODE_GENERATION)) {
            generatorTemp = 0.1;
        }

        String finalModel = model;
        if (classification != null &&
                (classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING ||
                        classification.taskType == TaskClassifier.TaskType.CODE_GENERATION ||
                        classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN)) {
            finalModel = OPENAI_STRONG;
        }

        return new ModelRoute(
                finalModel,
                finalModel,
                finalModel,
                OPENAI_CHEAP,
                generatorTemp,
                0.0,
                0.1,
                0.0);
    }
}
