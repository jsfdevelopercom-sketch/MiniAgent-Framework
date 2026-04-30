package com.miniagent.core;

import java.time.Duration;
import java.util.Objects;

/**
 * AgentRunPlan is the immutable execution contract for one user task.
 *
 * It combines:
 * - Task classification
 * - Model route
 * - Budget
 * - Stop limits
 * - Context policy
 */
public class AgentRunPlan {

    private final TaskClassifier.TaskClassification classification;
    private final ModelRoute modelRoute;

    private final int maxAttempts;
    private final int successThreshold;
    private final int maxAnswerTokens;
    private final Duration maxWallClockTime;

    private final boolean allowFullHistory;
    private final boolean enableRepairMemory;
    private final boolean enableBestAnswerTracking;
    private final boolean enablePlanner;
    private final boolean enableTools;

    public AgentRunPlan(
            TaskClassifier.TaskClassification classification,
            ModelRoute modelRoute,
            int maxAttempts,
            int successThreshold,
            int maxAnswerTokens,
            Duration maxWallClockTime,
            boolean allowFullHistory,
            boolean enableRepairMemory,
            boolean enableBestAnswerTracking,
            boolean enablePlanner,
            boolean enableTools) {
        this.classification = Objects.requireNonNull(classification, "classification cannot be null.");
        this.modelRoute = Objects.requireNonNull(modelRoute, "modelRoute cannot be null.");

        this.maxAttempts = clamp(maxAttempts, 1, 6);
        this.successThreshold = clamp(successThreshold, 6, 10);
        this.maxAnswerTokens = clamp(maxAnswerTokens, 500, 12000);

        if (maxWallClockTime == null || maxWallClockTime.isNegative() || maxWallClockTime.isZero()) {
            this.maxWallClockTime = Duration.ofSeconds(180);
        } else {
            this.maxWallClockTime = maxWallClockTime;
        }

        this.allowFullHistory = allowFullHistory;
        this.enableRepairMemory = enableRepairMemory;
        this.enableBestAnswerTracking = enableBestAnswerTracking;
        this.enablePlanner = enablePlanner;
        this.enableTools = enableTools;
    }

    public static AgentRunPlan from(
            TaskClassifier.TaskClassification classification,
            ModelRoute modelRoute) {
        boolean hard = classification.difficulty == TaskClassifier.TaskDifficulty.HARD;
        boolean toolAgent = classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT;
        boolean planner = classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;

        int attempts = classification.maxAttempts > 0 ? classification.maxAttempts : (hard ? 4 : 3);
        int threshold = classification.successThreshold > 0 ? classification.successThreshold : 8;
        int maxTokens = classification.maxAnswerTokens > 0 ? classification.maxAnswerTokens : (hard ? 5000 : 3000);

        Duration wallClock = hard ? Duration.ofSeconds(240) : Duration.ofSeconds(120);

        return new AgentRunPlan(
                classification,
                modelRoute,
                attempts,
                threshold,
                maxTokens,
                wallClock,
                false,
                true,
                true,
                planner,
                toolAgent);
    }

    public TaskClassifier.TaskClassification getClassification() {
        return classification;
    }

    public ModelRoute getModelRoute() {
        return modelRoute;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public int getMaxAnswerTokens() {
        return maxAnswerTokens;
    }

    public Duration getMaxWallClockTime() {
        return maxWallClockTime;
    }

    public boolean isAllowFullHistory() {
        return allowFullHistory;
    }

    public boolean isEnableRepairMemory() {
        return enableRepairMemory;
    }

    public boolean isEnableBestAnswerTracking() {
        return enableBestAnswerTracking;
    }

    public boolean isEnablePlanner() {
        return enablePlanner;
    }

    public boolean isEnableTools() {
        return enableTools;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}