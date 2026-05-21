package com.miniagent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.OpenAiHttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TokenCountJudge is a small, fast planning helper for MiniAgent's DeepThink
 * path.
 *
 * MiniAgent has two different kinds of "thinking" decisions:
 *
 * 1. TaskClassifier decides what the task is:
 * code generation, debugging, research, medical, writing, etc.
 *
 * 2. TokenCountJudge estimates how much visible answer space the first worker
 * call should receive.
 *
 * Keeping this as a separate class matters because a task can be classified
 * correctly but still receive the wrong output budget. That is exactly what
 * happens when a large single-file HTML/JS task is given only 6500 tokens: the
 * model starts producing real code, reaches max_output_tokens, and returns an
 * incomplete file. The validator then sees broken/truncated code even though
 * the generator was doing the right thing.
 *
 * This class intentionally stays lightweight:
 * - It uses a cheap/fast OpenAI model by default.
 * - It asks only for a tiny JSON budget decision.
 * - It falls back to deterministic heuristics if the model call fails.
 * - It never blocks the main worker generation for long.
 *
 * Integration rule:
 * TaskClassifier should call this after it has produced and sanitized the
 * TaskClassification. The returned starting token count should become
 * classification.maxAnswerTokens. The worker can then retry with a larger
 * token budget if the provider explicitly reports incomplete output due to
 * max_output_tokens.
 */
public class TokenCountJudge {

    private static final String DEFAULT_JUDGE_MODEL = ModelConstants.GPT_5_NANO;

    private static final int MIN_STARTING_TOKENS = 500;
    private static final int MAX_STARTING_TOKENS = 12_000;

    private static final int DEFAULT_EASY_TOKENS = 1_200;
    private static final int DEFAULT_MEDIUM_TOKENS = 3_000;
    private static final int DEFAULT_HARD_TOKENS = 6_000;

    private static final int DEFAULT_HEAVY_TASK_TOKENS = 9_000;
    private static final int DEFAULT_VERY_HEAVY_TASK_TOKENS = 11_000;

    private static final int FIRST_RETRY_FLOOR = 12_000;
    private static final int ABSOLUTE_RETRY_CAP = 14_000;

    private static final int JUDGE_MAX_OUTPUT_TOKENS = 500;
    private static final Duration JUDGE_TIMEOUT = Duration.ofSeconds(20);

    private final OpenAiHttpClient openAiHttpClient;
    private final ObjectMapper mapper;
    private final String judgeModel;

    /**
     * Creates a TokenCountJudge using the default fast OpenAI model.
     *
     * The OpenAI client is optional at runtime. If it is null, unavailable, or
     * fails, the class still returns a deterministic heuristic decision. This is
     * important because token judging should improve routing quality, not become
     * another mandatory failure point before generation.
     */
    public TokenCountJudge(OpenAiHttpClient openAiHttpClient, ObjectMapper mapper) {
        this(openAiHttpClient, mapper, DEFAULT_JUDGE_MODEL);
    }

    /**
     * Creates a TokenCountJudge with an explicit model.
     *
     * This overload is useful for tests or future routing experiments. The model
     * should remain cheap and fast because this class runs before the expensive
     * worker generation stage. It should not use the same heavy model as the
     * actual coder unless there is a very specific reason.
     */
    public TokenCountJudge(OpenAiHttpClient openAiHttpClient, ObjectMapper mapper, String judgeModel) {
        this.openAiHttpClient = openAiHttpClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.judgeModel = judgeModel == null || judgeModel.isBlank()
                ? DEFAULT_JUDGE_MODEL
                : judgeModel.trim();
    }

    /**
     * Produces the best starting token budget for the first worker answer.
     *
     * The method first builds a deterministic heuristic. That heuristic is used
     * as the safety net if the model judge fails, returns malformed JSON, or
     * suggests an unsafe value.
     *
     * The model judge is then asked for a tiny JSON object. Its result is not
     * trusted blindly. It is normalized against task type, difficulty, and the
     * hard one-shot caps used by the rest of MiniAgent.
     */
    public TokenBudgetDecision judge(
            String userTask,
            TaskClassifier.TaskClassification classification) {
        TokenBudgetDecision heuristic = heuristicDecision(userTask, classification);

        if (!isOpenAiUsable()) {
            return heuristic.withSource("HEURISTIC_NO_OPENAI");
        }

        try {
            String rawJson = openAiHttpClient.executeStructuredCall(
                    judgeModel,
                    buildSystemPrompt(),
                    buildUserPrompt(userTask, classification, heuristic),
                    0.0,
                    null,
                    JUDGE_MAX_OUTPUT_TOKENS,
                    JUDGE_TIMEOUT);

            JudgeJson parsed = mapper.readValue(extractJsonObject(rawJson), JudgeJson.class);
            return normalizeModelDecision(parsed, heuristic, userTask, classification);
        } catch (Exception ex) {
            return heuristic.withSource("HEURISTIC_AFTER_JUDGE_FAILURE: " + safeShortMessage(ex));
        }
    }

    /**
     * Builds the system prompt for the model-based judge.
     *
     * The prompt is intentionally narrow. The judge must not solve the user's
     * task. It only estimates visible output space for the first worker call.
     * This keeps the model call cheap and prevents it from drifting into actual
     * code generation.
     */
    private String buildSystemPrompt() {
        return """
                You are TokenCountJudge inside a Java AI agent pipeline.

                Your only job:
                Estimate the starting visible output token budget needed for the first worker answer.

                Do not solve the user task.
                Do not write code.
                Do not explain outside JSON.

                Important constraints:
                - starting_max_output_tokens is for the first one-shot worker call.
                - It must not exceed 12000.
                - Large code can start at 9000 to 12000.
                - Runtime may retry incomplete max_output_tokens responses up to 14000.
                - For simple answers, choose small budgets.
                - For huge complete app/code requests, choose larger budgets.
                - Do not choose 6500 for a full VS-Code-like single-file app; that is too low.
                - Do not choose 12000 for a simple explanation.

                Return only valid JSON:
                {
                  "starting_max_output_tokens": 9000,
                  "first_retry_max_output_tokens": 12000,
                  "second_retry_max_output_tokens": 14000,
                  "absolute_cap_output_tokens": 14000,
                  "needs_chunked_if_incomplete": true,
                  "reason": "Short reason under 30 words."
                }

                JSON only.
                """;
    }

    /**
     * Builds the user prompt for the token judge.
     *
     * It gives the judge the classification and the deterministic heuristic so
     * the model can make a better decision without needing the whole application
     * state. The heuristic also makes the model less likely to return a silly
     * number outside the expected range.
     */
    private String buildUserPrompt(
            String userTask,
            TaskClassifier.TaskClassification classification,
            TokenBudgetDecision heuristic) {
        StringBuilder builder = new StringBuilder();

        builder.append("Classified task metadata:\n");
        builder.append("- taskType: ").append(classification == null ? "UNKNOWN" : classification.taskType)
                .append("\n");
        builder.append("- difficulty: ").append(classification == null ? "UNKNOWN" : classification.difficulty)
                .append("\n");
        builder.append("- pipeline: ").append(classification == null ? "UNKNOWN" : classification.recommendedPipeline)
                .append("\n");
        builder.append("- needsDeepReasoning: ").append(classification != null && classification.needsDeepReasoning)
                .append("\n");
        builder.append("- classifierMaxAnswerTokens: ")
                .append(classification == null ? 0 : classification.maxAnswerTokens)
                .append("\n\n");

        builder.append("Deterministic heuristic suggestion:\n");
        builder.append("- starting: ").append(heuristic.getStartingMaxOutputTokens()).append("\n");
        builder.append("- firstRetry: ").append(heuristic.getFirstRetryMaxOutputTokens()).append("\n");
        builder.append("- secondRetry: ").append(heuristic.getSecondRetryMaxOutputTokens()).append("\n");
        builder.append("- absoluteCap: ").append(heuristic.getAbsoluteCapOutputTokens()).append("\n\n");

        builder.append("User task:\n");
        builder.append(userTask == null ? "" : userTask.trim()).append("\n");

        return builder.toString();
    }

    /**
     * Computes a safe deterministic budget without a model call.
     *
     * These heuristics are deliberately simple and conservative. They are not
     * trying to estimate exact token usage. They only prevent clearly bad
     * allocations, such as giving a huge complete HTML app the same budget as a
     * short answer.
     */
    private TokenBudgetDecision heuristicDecision(
            String userTask,
            TaskClassifier.TaskClassification classification) {
        TaskClassifier.TaskType taskType = classification == null || classification.taskType == null
                ? TaskClassifier.TaskType.UNKNOWN
                : classification.taskType;

        TaskClassifier.TaskDifficulty difficulty = classification == null || classification.difficulty == null
                ? TaskClassifier.TaskDifficulty.MEDIUM
                : classification.difficulty;

        boolean veryHeavyTask = looksLikeVeryHeavyTask(userTask);
        boolean heavyTask = veryHeavyTask || looksLikeHeavyTask(userTask) || isCodeTask(taskType);

        int starting;

        if (veryHeavyTask) {
            starting = DEFAULT_VERY_HEAVY_TASK_TOKENS;
        } else if (heavyTask && difficulty == TaskClassifier.TaskDifficulty.HARD) {
            starting = DEFAULT_HEAVY_TASK_TOKENS;
        } else if (heavyTask && difficulty == TaskClassifier.TaskDifficulty.MEDIUM) {
            starting = 6_000;
        } else {
            starting = switch (difficulty) {
                case EASY -> DEFAULT_EASY_TOKENS;
                case MEDIUM -> DEFAULT_MEDIUM_TOKENS;
                case HARD -> DEFAULT_HARD_TOKENS;
            };
        }

        if (classification != null && classification.maxAnswerTokens > 0) {
            starting = Math.max(starting, classification.maxAnswerTokens);
        }

        starting = clamp(starting, MIN_STARTING_TOKENS, MAX_STARTING_TOKENS);

        int firstRetry = heavyTask
                ? Math.max(starting + 2_000, FIRST_RETRY_FLOOR)
                : Math.min(starting + 2_000, MAX_STARTING_TOKENS);

        int secondRetry = heavyTask
                ? ABSOLUTE_RETRY_CAP
                : Math.min(firstRetry + 1_000, MAX_STARTING_TOKENS);

        return new TokenBudgetDecision(
                starting,
                clamp(firstRetry, starting, ABSOLUTE_RETRY_CAP),
                clamp(secondRetry, firstRetry, ABSOLUTE_RETRY_CAP),
                heavyTask ? ABSOLUTE_RETRY_CAP : MAX_STARTING_TOKENS,
                heavyTask,
                "Heuristic budget based on task type and difficulty.",
                "HEURISTIC");
    }

    /**
     * Normalizes the model judge output.
     *
     * The model can recommend a budget, but Java owns safety. This method clamps
     * the model's numbers into the allowed range, guarantees retries are
     * non-decreasing, and preserves the heuristic when the model returns a value
     * that is too small for obvious large code.
     */
    private TokenBudgetDecision normalizeModelDecision(
            JudgeJson parsed,
            TokenBudgetDecision heuristic,
            String userTask,
            TaskClassifier.TaskClassification classification) {
        if (parsed == null) {
            return heuristic.withSource("HEURISTIC_NULL_JUDGE_JSON");
        }

        boolean heavyTask = looksLikeHeavyTask(userTask)
                || looksLikeVeryHeavyTask(userTask)
                || isCodeTask(classification == null ? null : classification.taskType);

        int minimumStart = heavyTask
                ? Math.min(heuristic.getStartingMaxOutputTokens(), MAX_STARTING_TOKENS)
                : MIN_STARTING_TOKENS;

        int starting = parsed.startingMaxOutputTokens > 0
                ? parsed.startingMaxOutputTokens
                : heuristic.getStartingMaxOutputTokens();

        starting = clamp(starting, minimumStart, MAX_STARTING_TOKENS);

        int firstRetry = parsed.firstRetryMaxOutputTokens > 0
                ? parsed.firstRetryMaxOutputTokens
                : heuristic.getFirstRetryMaxOutputTokens();

        int secondRetry = parsed.secondRetryMaxOutputTokens > 0
                ? parsed.secondRetryMaxOutputTokens
                : heuristic.getSecondRetryMaxOutputTokens();

        int absoluteCap = parsed.absoluteCapOutputTokens > 0
                ? parsed.absoluteCapOutputTokens
                : heuristic.getAbsoluteCapOutputTokens();

        absoluteCap = clamp(absoluteCap, starting, ABSOLUTE_RETRY_CAP);
        firstRetry = clamp(firstRetry, starting, absoluteCap);
        secondRetry = clamp(secondRetry, firstRetry, absoluteCap);

        if (heavyTask) {
            firstRetry = Math.max(firstRetry, Math.min(FIRST_RETRY_FLOOR, absoluteCap));
            secondRetry = Math.max(secondRetry, firstRetry);
        }

        String reason = parsed.reason == null || parsed.reason.isBlank()
                ? "Model judge estimated visible output budget."
                : parsed.reason.trim();

        return new TokenBudgetDecision(
                starting,
                firstRetry,
                secondRetry,
                absoluteCap,
                parsed.needsChunkedIfIncomplete || heavyTask,
                reason,
                "MODEL:" + judgeModel);
    }

    /**
     * Checks whether the OpenAI client can be called.
     *
     * The judge must never break local/dev deployments that do not have OpenAI
     * configured. If the key is absent, the deterministic heuristic is good
     * enough to keep generation moving.
     */
    private boolean isOpenAiUsable() {
        try {
            return openAiHttpClient != null
                    && openAiHttpClient.getConfig() != null
                    && openAiHttpClient.getConfig().getOpenaiApiKey() != null
                    && !openAiHttpClient.getConfig().getOpenaiApiKey().isBlank();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Detects code tasks from the classifier enum.
     *
     * This helper intentionally stays enum-based instead of keyword-based where
     * possible. The classifier has already done the semantic work, so this class
     * should reuse that decision instead of repeating a second classifier.
     */
    private boolean isCodeTask(TaskClassifier.TaskType taskType) {
        return taskType == TaskClassifier.TaskType.CODE_GENERATION
                || taskType == TaskClassifier.TaskType.CODE_DEBUGGING
                || taskType == TaskClassifier.TaskType.ARCHITECTURE_DESIGN;
    }

    /**
     * Detects large tasks from user wording.
     *
     * This is only a fallback guard around the classifier. It catches explicit
     * user language such as "complete", "full working", or "no placeholders"
     * that strongly implies the answer needs more visible output tokens.
     */
    private boolean looksLikeHeavyTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean heavyContext = containsAny(
                q,
                "code",
                "html",
                "index.html",
                "javascript",
                "typescript",
                "java",
                "python",
                "app",
                "editor",
                "frontend",
                "backend",
                "class",
                "method",
                "api",
                "essay", "report", "case summary", "book", "thesis");

        boolean large = containsAny(
                q,
                "complete",
                "full",
                "working",
                "production",
                "production-grade",
                "complex",
                "elaborate",
                "advanced",
                "all features",
                "no placeholder",
                "no placeholders",
                "single file",
                "entire file", "entire document");

        return heavyContext && large;
    }

    /**
     * Detects the specific class of prompts that almost never fit into the old
     * 6500-token budget.
     */
    private boolean looksLikeVeryHeavyTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean massiveScope = containsAny(
                q,
                "ide", "entire codebase", "full app", "complete application", "large document",
                "massive", "entire book", "full book");

        boolean completeFeatureSet = containsAny(
                q,
                "all features",
                "complete with",
                "complete code",
                "full working",
                "complex complete");

        boolean heavyOutput = containsAny(
                q,
                "index.html",
                "html",
                "javascript", "essay", "report", "book");

        return massiveScope && completeFeatureSet && heavyOutput;
    }


    /**
     * Extracts a JSON object from provider output.
     *
     * Structured calls should already return JSON, but this helper makes the
     * judge tolerant of fenced JSON or small accidental text around the object.
     */
    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }

        String clean = raw.trim();

        if (clean.startsWith("```json")) {
            clean = clean.substring(7).trim();
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3).trim();
            }
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3).trim();
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3).trim();
            }
        }

        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');

        if (start >= 0 && end >= start) {
            return clean.substring(start, end + 1);
        }

        return clean;
    }

    /**
     * Shortens exception messages for classifier metadata and logs.
     */
    private String safeShortMessage(Exception exception) {
        if (exception == null) {
            return "unknown";
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }

        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    /**
     * Checks whether a text contains any of the supplied phrases.
     */
    private boolean containsAny(String text, String... terms) {
        if (text == null || terms == null) {
            return false;
        }

        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Clamps a number into a safe inclusive range.
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * JSON shape returned by the model judge.
     *
     * It is private because other classes should not depend on provider JSON.
     * They should depend on the normalized TokenBudgetDecision object instead.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class JudgeJson {

        @JsonProperty("starting_max_output_tokens")
        public int startingMaxOutputTokens;

        @JsonProperty("first_retry_max_output_tokens")
        public int firstRetryMaxOutputTokens;

        @JsonProperty("second_retry_max_output_tokens")
        public int secondRetryMaxOutputTokens;

        @JsonProperty("absolute_cap_output_tokens")
        public int absoluteCapOutputTokens;

        @JsonProperty("needs_chunked_if_incomplete")
        public boolean needsChunkedIfIncomplete;

        public String reason = "";
    }

    /**
     * Normalized token-budget decision used by TaskClassifier and AgentRunPlan.
     *
     * Only startingMaxOutputTokens needs to be stored in the existing
     * TaskClassification JSON-compatible field today. The retry values are
     * still useful in logs and can be carried into AgentRunPlan later if the
     * project chooses to make retry budgets first-class.
     */
    public static class TokenBudgetDecision {

        private final int startingMaxOutputTokens;
        private final int firstRetryMaxOutputTokens;
        private final int secondRetryMaxOutputTokens;
        private final int absoluteCapOutputTokens;
        private final boolean needsChunkedIfIncomplete;
        private final String reason;
        private final String source;

        /**
         * Constructs a normalized token decision.
         *
         * The constructor does not perform heavy validation because values are
         * normalized by the factory methods above. It still stores safe defaults
         * for nullable text fields so logs never throw NullPointerException.
         */
        public TokenBudgetDecision(
                int startingMaxOutputTokens,
                int firstRetryMaxOutputTokens,
                int secondRetryMaxOutputTokens,
                int absoluteCapOutputTokens,
                boolean needsChunkedIfIncomplete,
                String reason,
                String source) {
            this.startingMaxOutputTokens = startingMaxOutputTokens;
            this.firstRetryMaxOutputTokens = firstRetryMaxOutputTokens;
            this.secondRetryMaxOutputTokens = secondRetryMaxOutputTokens;
            this.absoluteCapOutputTokens = absoluteCapOutputTokens;
            this.needsChunkedIfIncomplete = needsChunkedIfIncomplete;
            this.reason = reason == null ? "" : reason;
            this.source = source == null ? "" : source;
        }

        public int getStartingMaxOutputTokens() {
            return startingMaxOutputTokens;
        }

        public int getFirstRetryMaxOutputTokens() {
            return firstRetryMaxOutputTokens;
        }

        public int getSecondRetryMaxOutputTokens() {
            return secondRetryMaxOutputTokens;
        }

        public int getAbsoluteCapOutputTokens() {
            return absoluteCapOutputTokens;
        }

        public boolean isNeedsChunkedIfIncomplete() {
            return needsChunkedIfIncomplete;
        }

        public String getReason() {
            return reason;
        }

        public String getSource() {
            return source;
        }

        /**
         * Returns the same token values with a different source label.
         *
         * This is useful when the heuristic is reused because the model judge is
         * unavailable or failed.
         */
        private TokenBudgetDecision withSource(String newSource) {
            return new TokenBudgetDecision(
                    startingMaxOutputTokens,
                    firstRetryMaxOutputTokens,
                    secondRetryMaxOutputTokens,
                    absoluteCapOutputTokens,
                    needsChunkedIfIncomplete,
                    reason,
                    newSource);
        }

        @Override
        public String toString() {
            return "TokenBudgetDecision{" +
                    "startingMaxOutputTokens=" + startingMaxOutputTokens +
                    ", firstRetryMaxOutputTokens=" + firstRetryMaxOutputTokens +
                    ", secondRetryMaxOutputTokens=" + secondRetryMaxOutputTokens +
                    ", absoluteCapOutputTokens=" + absoluteCapOutputTokens +
                    ", needsChunkedIfIncomplete=" + needsChunkedIfIncomplete +
                    ", reason='" + reason + '\'' +
                    ", source='" + source + '\'' +
                    '}';
        }
    }
}