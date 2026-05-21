package com.miniagent.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable execution contract for one MiniAgent run.
 *
 * AgentRunPlan sits immediately after TaskClassifier and ModelRouter in the
 * control flow:
 *
 * user request
 * -> TaskClassifier decides task type, difficulty, attempts, answer-token
 * budget
 * -> ModelRouter chooses generator / critic / repair / synthesizer models
 * -> AgentRunPlan freezes those decisions into one object
 * -> SafeThoughtExecutor, MiniAgentWorker, Evaluator, and Synthesizer consume
 * this object
 *
 * This class deliberately does not re-classify the raw prompt. It also does not
 * inflate the
 * classifier's maxAttempts or maxAnswerTokens. The classifier is the policy
 * owner; this class
 * is only the defensive boundary that prevents malformed or old classification
 * objects from
 * creating runaway loops or huge one-shot requests.
 *
 * The most important production rule here is simple:
 *
 * If a later class needs to know whether to use freeform generation, repair
 * memory, full
 * history, or synthesis bypass, it should ask AgentRunPlan instead of
 * re-reading the user
 * prompt and inventing its own logic.
 */
public class AgentRunPlan {

    /**
     * Commercial wall-clock targets.
     *
     * These are run-level limits used by the orchestrator and stop policy. Provider
     * clients
     * still own their stage-level HTTP timeouts. Keeping these numbers here makes
     * the total
     * DeepThink budget predictable: a hard one-shot code request should finish or
     * fail safely
     * within a few minutes, not silently run for ten minutes.
     */
    private static final Duration SIMPLE_WALL_CLOCK = Duration.ofSeconds(90);
    private static final Duration MEDIUM_WALL_CLOCK = Duration.ofSeconds(180);
    private static final Duration HARD_WALL_CLOCK = Duration.ofSeconds(285);
    private static final Duration TOOL_WALL_CLOCK = Duration.ofSeconds(285);

    /**
     * One-shot answer budget limits.
     *
     * This is not the model context window. This is the visible/final-answer budget
     * that the
     * current non-chunked pipeline should try to produce in one worker call. Larger
     * software
     * generation should eventually move to project/chunk mode instead of raising
     * this cap.
     */
    private static final int MIN_ANSWER_TOKENS = 500;
    private static final int MAX_ONE_SHOT_ANSWER_TOKENS = 14_000;

    /**
     * Attempt limits for the current commercial pipeline.
     *
     * A single "attempt" may already include generation, evaluation, and maybe one
     * repair.
     * Letting this rise to four or six attempts makes the user wait while the same
     * broken path
     * repeats. The classifier may recommend one or two attempts; this class only
     * clamps.
     */
    private static final int MIN_ATTEMPTS = 1;
    private static final int MAX_ATTEMPTS = 2;

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

    /**
     * Creates an immutable execution plan.
     *
     * Most production code should call AgentRunPlan.from(...). This constructor
     * remains public
     * because tests and older wiring may still build plans directly. It therefore
     * performs the
     * same defensive clamping as the factory.
     *
     * The constructor does not infer task category from text. It trusts the
     * TaskClassification
     * object and makes the object safe to consume.
     */
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

        /*
         * Attempts are classifier-owned. The clamp exists only to protect the runtime
         * from old
         * objects or tests that still pass 0/4/6. Do not change this back to a broad
         * 1..6 range
         * unless the whole pipeline is made chunk/project-aware.
         */
        this.maxAttempts = clamp(maxAttempts, MIN_ATTEMPTS, MAX_ATTEMPTS);

        /*
         * The evaluator and stop policy use this threshold to decide whether a draft is
         * good
         * enough. Extreme values make the loop either too lax or impossible to satisfy.
         */
        this.successThreshold = clamp(successThreshold, 6, 10);

        /*
         * Keep the one-shot budget aligned with TaskClassifier and provider clients.
         * Raising
         * this alone will not create large software safely; it will usually just create
         * slower,
         * more fragile one-shot calls.
         */
        this.maxAnswerTokens = clamp(maxAnswerTokens, MIN_ANSWER_TOKENS, MAX_ONE_SHOT_ANSWER_TOKENS);

        /*
         * If a caller passes no wall-clock budget, choose a budget from the
         * classification.
         * If a caller passes an old 500s/900s value, cap it back to the intended class
         * budget.
         */
        if (maxWallClockTime == null || maxWallClockTime.isZero() || maxWallClockTime.isNegative()) {
            this.maxWallClockTime = wallClockFor(classification);
        } else {
            this.maxWallClockTime = capWallClock(maxWallClockTime, classification);
        }

        this.allowFullHistory = allowFullHistory;
        this.enableRepairMemory = enableRepairMemory;
        this.enableBestAnswerTracking = enableBestAnswerTracking;
        this.enablePlanner = enablePlanner;
        this.enableTools = enableTools;
    }

    /**
     * Builds the normal production plan from classifier output and the selected
     * model route.
     *
     * This is the only place where classification becomes executable policy. Notice
     * that the
     * values come from TaskClassification first; fallback defaults are used only
     * for old or
     * malformed classification objects where fields are missing or zero.
     */
    public static AgentRunPlan from(
            TaskClassifier.TaskClassification classification,
            ModelRoute modelRoute) {
        Objects.requireNonNull(classification, "classification cannot be null.");
        Objects.requireNonNull(modelRoute, "modelRoute cannot be null.");

        int attempts = attemptsFromClassification(classification);
        int threshold = successThresholdFromClassification(classification);
        int maxTokens = answerTokensFromClassification(classification);

        boolean planner = classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
        boolean toolAgent = classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT;

        boolean allowFullHistory = shouldAllowFullHistory(classification);
        boolean repairMemory = shouldEnableRepairMemory(classification, attempts);
        boolean bestTracking = shouldEnableBestAnswerTracking(classification, attempts);
        boolean tools = toolAgent || classification.needsTools || classification.needsWeb
                || classification.needsFileAccess;

        return new AgentRunPlan(
                classification,
                modelRoute,
                attempts,
                threshold,
                maxTokens,
                wallClockFor(classification),
                allowFullHistory,
                repairMemory,
                bestTracking,
                planner,
                tools);
    }

    /**
     * Returns the classifier object that owns task identity and high-level
     * execution policy.
     */
    public TaskClassifier.TaskClassification getClassification() {
        return classification;
    }

    /**
     * Returns the selected model route for generator, critic, repair, and
     * synthesis.
     */
    public ModelRoute getModelRoute() {
        return modelRoute;
    }

    /** Returns how many generate/evaluate/repair attempts the run may make. */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** Returns the evaluator score threshold used by StopPolicy. */
    public int getSuccessThreshold() {
        return successThreshold;
    }

    /** Returns the one-shot visible-answer target budget for worker generation. */
    public int getMaxAnswerTokens() {
        return maxAnswerTokens;
    }

    /**
     * Returns the total wall-clock budget that the orchestration layer should
     * respect.
     */
    public Duration getMaxWallClockTime() {
        return maxWallClockTime;
    }

    /** Returns whether the worker may receive full conversation history. */
    public boolean isAllowFullHistory() {
        return allowFullHistory;
    }

    /**
     * Returns whether repair memory should be built and fed into repair attempts.
     */
    public boolean isEnableRepairMemory() {
        return enableRepairMemory;
    }

    /**
     * Returns whether the run should remember the best draft even when later stages
     * fail.
     */
    public boolean isEnableBestAnswerTracking() {
        return enableBestAnswerTracking;
    }

    /** Returns whether planning semantics are enabled for this run. */
    public boolean isEnablePlanner() {
        return enablePlanner;
    }

    /** Returns whether the tool-observation phase is allowed for this run. */
    public boolean isEnableTools() {
        return enableTools;
    }

    /**
     * Returns true for code generation and code debugging tasks.
     *
     * This helper prevents every downstream class from writing its own
     * prompt-keyword detector.
     */
    public boolean isCodeTask() {
        return classification.taskType == TaskClassifier.TaskType.CODE_GENERATION
                || classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING;
    }

    /**
     * Returns true for answers that should be generated as direct text rather than
     * JSON.
     *
     * Freeform mode is not only for code. Architecture, research, and hard deep
     * explanations can
     * also be too large or too brittle for JSON-wrapped generation.
     */
    public boolean isLargeFreeformTask() {
        return isCodeTask()
                || classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN
                || classification.taskType == TaskClassifier.TaskType.RESEARCH
                || classification.difficulty == TaskClassifier.TaskDifficulty.HARD
                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
    }

    /**
     * Tells MiniAgentWorker whether first-draft generation should be freeform text.
     *
     * Refusals and clarification requests should stay structured/simple. Everything
     * large enough
     * to be user-facing code or long-form reasoning should not be forced into a
     * JSON object.
     */
    public boolean shouldUseFreeformWorkerOutput() {
        if (classification.needsUserClarification) {
            return false;
        }

        if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.REFUSE) {
            return false;
        }

        return isLargeFreeformTask();
    }

    /**
     * Tells OutputSynthesizer whether to skip model-based final synthesis.
     *
     * Code should generally be returned exactly as produced by the worker. A cheap
     * final model can
     * corrupt syntax, collapse long implementations, or turn a complete app into a
     * summary.
     */
    public boolean shouldSkipLargeAnswerSynthesis() {
        return isCodeTask() || (shouldUseFreeformWorkerOutput() && maxAnswerTokens >= 5500);
    }

    /**
     * Reads attempts from the classifier with a safe legacy fallback.
     *
     * Old TaskClassifier versions returned maxAttempts=0. If such an object reaches
     * this method,
     * we choose a conservative value instead of inflating hard tasks to four
     * attempts.
     */
    private static int attemptsFromClassification(TaskClassifier.TaskClassification classification) {
        if (classification.maxAttempts > 0) {
            return clamp(classification.maxAttempts, MIN_ATTEMPTS, MAX_ATTEMPTS);
        }

        if (classification.taskType == TaskClassifier.TaskType.CODE_GENERATION
                || classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                || classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN) {
            return 1;
        }

        if (classification.difficulty == TaskClassifier.TaskDifficulty.HARD
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.DIRECT_ANSWER
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.ASK_USER_CLARIFICATION
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.REFUSE) {
            return 2;
        }

        return 1;
    }

    /** Reads the evaluator threshold from classification with a safe default. */
    private static int successThresholdFromClassification(TaskClassifier.TaskClassification classification) {
        int fallback = classification.difficulty == TaskClassifier.TaskDifficulty.EASY ? 7 : 8;
        int value = classification.successThreshold > 0 ? classification.successThreshold : fallback;
        return clamp(value, 6, 10);
    }

    /**
     * Reads the answer-token budget from classification with task-aware fallback
     * defaults.
     */
    private static int answerTokensFromClassification(TaskClassifier.TaskClassification classification) {
        int value = classification.maxAnswerTokens > 0
                ? classification.maxAnswerTokens
                : defaultAnswerTokensFor(classification);

        return clamp(value, MIN_ANSWER_TOKENS, MAX_ONE_SHOT_ANSWER_TOKENS);
    }

    /**
     * Provides sane token defaults only when an old classifier did not provide one.
     */
    private static int defaultAnswerTokensFor(TaskClassifier.TaskClassification classification) {
        if (classification.taskType == TaskClassifier.TaskType.CODE_GENERATION
                || classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING) {
            return switch (classification.difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4500;
                case HARD -> 6500;
            };
        }

        if (classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN
                || classification.taskType == TaskClassifier.TaskType.RESEARCH) {
            return switch (classification.difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4000;
                case HARD -> 5500;
            };
        }

        return switch (classification.difficulty) {
            case EASY -> 1000;
            case MEDIUM -> 2500;
            case HARD -> 4500;
        };
    }

    /** Chooses a total run budget from task/tool difficulty. */
    private static Duration wallClockFor(TaskClassifier.TaskClassification classification) {
        if (classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.TOOL_AGENT
                || classification.needsTools
                || classification.needsWeb
                || classification.needsFileAccess) {
            return TOOL_WALL_CLOCK;
        }

        if (classification.difficulty == TaskClassifier.TaskDifficulty.HARD
                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR) {
            return HARD_WALL_CLOCK;
        }

        if (classification.difficulty == TaskClassifier.TaskDifficulty.MEDIUM
                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.THINK_CRITIC_REPAIR) {
            return MEDIUM_WALL_CLOCK;
        }

        return SIMPLE_WALL_CLOCK;
    }

    /** Caps externally provided wall-clock values to the plan's task category. */
    private static Duration capWallClock(Duration requested, TaskClassifier.TaskClassification classification) {
        Duration maxAllowed = wallClockFor(classification);
        return requested.compareTo(maxAllowed) > 0 ? maxAllowed : requested;
    }

    /** Decides whether full history is useful enough to justify prompt growth. */
    private static boolean shouldAllowFullHistory(TaskClassifier.TaskClassification classification) {
        return classification.difficulty == TaskClassifier.TaskDifficulty.HARD
                || classification.taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                || classification.taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN
                || classification.taskType == TaskClassifier.TaskType.RESEARCH
                || classification.needsFileAccess;
    }

    /**
     * Enables repair memory only when there is an actual later attempt that can use
     * it.
     */
    private static boolean shouldEnableRepairMemory(
            TaskClassifier.TaskClassification classification,
            int attempts) {
        return attempts > 1
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.DIRECT_ANSWER
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.ASK_USER_CLARIFICATION
                && classification.recommendedPipeline != TaskClassifier.RecommendedPipeline.REFUSE;
    }

    /** Keeps the best partial draft when multiple stages are allowed to run. */
    private static boolean shouldEnableBestAnswerTracking(
            TaskClassifier.TaskClassification classification,
            int attempts) {
        return attempts > 1
                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.THINK_CRITIC_REPAIR
                || classification.recommendedPipeline == TaskClassifier.RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
    }

    /** Small local clamp helper used by all defensive bounds in this class. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}