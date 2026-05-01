package com.miniagent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * TaskClassifier is the first routing brain of MiniAgent.
 *
 * It does NOT solve the user's task.
 * It only classifies the task and recommends which MiniAgent pipeline should
 * handle it.
 *
 * Important design rule:
 * - Use a cheap, fast model here.
 * - Do not use deep thinking models for classification.
 * - Do not pass whole conversation history here.
 */
public class TaskClassifier {

    private static final String DEFAULT_OPENAI_CLASSIFIER_MODEL = ModelConstants.GPT_5_NANO;
    private static final String DEFAULT_GEMINI_CLASSIFIER_MODEL = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private static final String DEFAULT_CLAUDE_CLASSIFIER_MODEL = ModelConstants.CLAUDE_HAIKU_4_5;

    private final OpenAiHttpClient openAiClient;
    private final GeminiHttpClient geminiClient;
    private final ClaudeHttpClient claudeClient;
    private final ObjectMapper mapper;

    private final String openAiClassifierModel;
    private final String geminiClassifierModel;
    private final String claudeClassifierModel;

    public TaskClassifier(
            OpenAiHttpClient openAiClient,
            GeminiHttpClient geminiClient,
            ClaudeHttpClient claudeClient,
            ObjectMapper mapper) {
        this(
                openAiClient,
                geminiClient,
                claudeClient,
                mapper,
                DEFAULT_OPENAI_CLASSIFIER_MODEL,
                DEFAULT_GEMINI_CLASSIFIER_MODEL,
                DEFAULT_CLAUDE_CLASSIFIER_MODEL);
    }

    public TaskClassifier(
            OpenAiHttpClient openAiClient,
            GeminiHttpClient geminiClient,
            ClaudeHttpClient claudeClient,
            ObjectMapper mapper,
            String openAiClassifierModel,
            String geminiClassifierModel,
            String claudeClassifierModel) {
        this.openAiClient = openAiClient;
        this.geminiClient = geminiClient;
        this.claudeClient = claudeClient;
        this.mapper = mapper != null ? mapper : new ObjectMapper();

        this.openAiClassifierModel = cleanModel(openAiClassifierModel, DEFAULT_OPENAI_CLASSIFIER_MODEL);
        this.geminiClassifierModel = cleanModel(geminiClassifierModel, DEFAULT_GEMINI_CLASSIFIER_MODEL);
        this.claudeClassifierModel = cleanModel(claudeClassifierModel, DEFAULT_CLAUDE_CLASSIFIER_MODEL);
    }

    /**
     * Classifies the task using AUTO provider selection.
     *
     * AUTO means:
     * 1. Prefer OpenAI if configured.
     * 2. Then Gemini if configured.
     * 3. Then Claude if configured.
     */
    public TaskClassification classify(String userTask) {
        return classify(userTask, ClassifierProvider.AUTO);
    }

    /**
     * Classifies the task using requested provider.
     */
    public TaskClassification classify(String userTask, ClassifierProvider preferredProvider) {
        String normalizedTask = validateAndNormalizeTask(userTask);
        ClassifierProvider provider = preferredProvider != null ? preferredProvider : ClassifierProvider.AUTO;

        List<ClassifierProvider> providerOrder = buildProviderOrder(provider);
        List<String> failures = new ArrayList<>();

        for (ClassifierProvider currentProvider : providerOrder) {
            try {
                String rawJson = invokeProvider(currentProvider, normalizedTask);
                TaskClassification classification = parseAndNormalize(rawJson, currentProvider, normalizedTask);
                return classification;
            } catch (Exception ex) {
                failures.add(currentProvider + ": " + ex.getMessage());
            }
        }

        return fallbackClassification(normalizedTask, failures);
    }

    private String invokeProvider(ClassifierProvider provider, String userTask) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userTask);

        return switch (provider) {
            case OPENAI -> {
                ensureOpenAiAvailable();
                yield openAiClient.executeStructuredCall(
                        openAiClassifierModel,
                        systemPrompt,
                        userPrompt,
                        0.0,
                        null);
            }
            case GEMINI -> {
                ensureGeminiAvailable();
                yield geminiClient.executeStructuredCall(
                        geminiClassifierModel,
                        systemPrompt,
                        userPrompt,
                        0.0,
                        null);
            }
            case CLAUDE -> {
                ensureClaudeAvailable();
                yield claudeClient.executeStructuredCall(
                        claudeClassifierModel,
                        systemPrompt,
                        userPrompt,
                        0.0,
                        null);
            }
            case AUTO -> throw new IllegalArgumentException("AUTO should be resolved before provider invocation.");
        };
    }

    private String buildSystemPrompt() {
        return """
                You are TaskClassifier, a strict routing component inside a Java AI agent system.

                Your job:
                - Classify the user's task.
                - Recommend the cheapest safe MiniAgent pipeline.
                - Return only valid JSON.
                - Do not solve the task.
                - Do not explain the classification outside JSON.

                Important rules:
                - Prefer EASY unless the task clearly requires multiple steps.
                - Set needs_deep_reasoning=true only for complex code, architecture, debugging, medical/legal/financial reasoning, math, research, or multi-step planning.
                - Set needs_tools=true only if external action is needed: reading files, running code, web search, browser control, API calls, database access, calendar/email, or live state.
                - Set needs_web=true only if current/latest/niche information is required.
                - Set needs_file_access=true only if user asks to inspect/upload/read/modify files or project code.
                - Set needs_user_clarification=true only if the task cannot be safely attempted from the provided input.
                - Set recommended_pipeline=DIRECT_ANSWER for simple tasks.
                - Set recommended_pipeline=THINK_CRITIC_REPAIR for medium quality-sensitive tasks.
                - Set recommended_pipeline=PLAN_THINK_CRITIC_REPAIR for hard multi-step non-tool tasks.
                - Set recommended_pipeline=TOOL_AGENT for tasks requiring tools or external observations.
                - Set recommended_pipeline=ASK_USER_CLARIFICATION if essential information is missing.
                - Set recommended_pipeline=REFUSE only for unsafe or disallowed tasks.

                Output JSON schema:
                {
                  "task_type": "GENERAL_QA | CODE_GENERATION | CODE_DEBUGGING | ARCHITECTURE_DESIGN | WRITING | SUMMARIZATION | MEDICAL | RESEARCH | TOOL_REQUIRED | UNKNOWN",
                  "difficulty": "EASY | MEDIUM | HARD",
                  "needs_deep_reasoning": true,
                  "needs_tools": true,
                  "needs_web": true,
                  "needs_file_access": true,
                  "needs_user_clarification": true,
                  "recommended_pipeline": "DIRECT_ANSWER | THINK_CRITIC_REPAIR | PLAN_THINK_CRITIC_REPAIR | TOOL_AGENT | ASK_USER_CLARIFICATION | REFUSE",
                  "max_attempts": 1,
                  "success_threshold": 8,
                  "max_answer_tokens": 1200,
                  "reason": "Short reason under 30 words."
                }

                JSON only.
                """;
    }

    private String buildUserPrompt(String userTask) {
        return """
                Classify this user task.

                USER_TASK:
                %s
                """.formatted(userTask);
    }

    private TaskClassification parseAndNormalize(String rawJson, ClassifierProvider providerUsed, String normalizedTask) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Classifier returned blank JSON.");
        }

        TaskClassification classification = mapper.readValue(rawJson, TaskClassification.class);

        TaskType taskType = classification.taskType != null ? classification.taskType : TaskType.UNKNOWN;
        TaskDifficulty difficulty = classification.difficulty != null ? classification.difficulty
                : TaskDifficulty.MEDIUM;
        RecommendedPipeline pipeline = classification.recommendedPipeline != null
                ? classification.recommendedPipeline
                : RecommendedPipeline.THINK_CRITIC_REPAIR;

        int maxAttempts = clamp(classification.maxAttempts, 1, 5, defaultAttemptsFor(pipeline, difficulty));
        int successThreshold = clamp(classification.successThreshold, 6, 10, 8);
        int maxAnswerTokens = clamp(classification.maxAnswerTokens, 500, 8000,
                defaultMaxAnswerTokensFor(taskType, difficulty));

        boolean needsTools = classification.needsTools;
        boolean needsWeb = classification.needsWeb;
        boolean needsFileAccess = classification.needsFileAccess;

        if (needsWeb || needsFileAccess) {
            needsTools = true;
        }

        if (needsTools && pipeline != RecommendedPipeline.ASK_USER_CLARIFICATION
                && pipeline != RecommendedPipeline.REFUSE) {
            pipeline = RecommendedPipeline.TOOL_AGENT;
        }

        String reason = classification.reason;
        if (reason == null || reason.isBlank()) {
            reason = "Classified by " + providerUsed + ".";
        }
        
        boolean needsDeepReasoning = classification.needsDeepReasoning;
        
if (looksLikeLargeCodeTask(normalizedTask)) {
    taskType = TaskType.CODE_GENERATION;
    difficulty = TaskDifficulty.HARD;
    pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
    maxAttempts = Math.max(maxAttempts, 3);
    maxAnswerTokens = Math.max(maxAnswerTokens, 12000);
    needsDeepReasoning = true;
}
        return new TaskClassification(
                taskType,
                difficulty,
                needsDeepReasoning,
                needsTools,
                needsWeb,
                needsFileAccess,
                classification.needsUserClarification,
                pipeline,
                maxAttempts,
                successThreshold,
                maxAnswerTokens,
                trimReason(reason),
                providerUsed.name());
    }
private static boolean looksLikeLargeCodeTask(String task) {
    String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

    boolean code =
            q.contains("code") ||
                    q.contains("html") ||
                    q.contains("javascript") ||
                    q.contains("java") ||
                    q.contains("python") ||
                    q.contains("app") ||
                    q.contains("editor");

    boolean large =
            q.contains("complete") ||
                    q.contains("full") ||
                    q.contains("working") ||
                    q.contains("elaborate") ||
                    q.contains("extremely detailed") ||
                    q.contains("no placeholder") ||
                    q.contains("must not include placeholders") ||
                    q.contains("like vscode") ||
                    q.contains("visual studio code");

    return code && large;
}
    private TaskClassification fallbackClassification(String userTask, List<String> failures) {
        TaskType taskType = roughTaskType(userTask);
        TaskDifficulty difficulty = roughDifficulty(userTask);
        RecommendedPipeline pipeline = difficulty == TaskDifficulty.HARD
                ? RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR
                : RecommendedPipeline.THINK_CRITIC_REPAIR;

        String reason = "Model classification failed; used deterministic fallback.";

        if (failures != null && !failures.isEmpty()) {
            reason = reason + " First error: " + failures.get(0);
        }

        return new TaskClassification(
                taskType,
                difficulty,
                difficulty != TaskDifficulty.EASY,
                false,
                false,
                false,
                false,
                pipeline,
                defaultAttemptsFor(pipeline, difficulty),
                8,
                defaultMaxAnswerTokensFor(taskType, difficulty),
                trimReason(reason),
                "FALLBACK");
    }

    private List<ClassifierProvider> buildProviderOrder(ClassifierProvider preferredProvider) {
        List<ClassifierProvider> providers = new ArrayList<>();

        if (preferredProvider == ClassifierProvider.AUTO) {
            providers.add(ClassifierProvider.OPENAI);
            providers.add(ClassifierProvider.GEMINI);
            providers.add(ClassifierProvider.CLAUDE);
            return providers;
        }

        providers.add(preferredProvider);

        if (preferredProvider != ClassifierProvider.OPENAI) {
            providers.add(ClassifierProvider.OPENAI);
        }
        if (preferredProvider != ClassifierProvider.GEMINI) {
            providers.add(ClassifierProvider.GEMINI);
        }
        if (preferredProvider != ClassifierProvider.CLAUDE) {
            providers.add(ClassifierProvider.CLAUDE);
        }

        return providers;
    }

    private void ensureOpenAiAvailable() {
        if (openAiClient == null) {
            throw new IllegalStateException("OpenAI client is null.");
        }
        if (openAiClient.getConfig() == null ||
                openAiClient.getConfig().getOpenaiApiKey() == null ||
                openAiClient.getConfig().getOpenaiApiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured.");
        }
    }

    private void ensureGeminiAvailable() {
        if (geminiClient == null) {
            throw new IllegalStateException("Gemini client is null.");
        }
        if (geminiClient.getConfig() == null ||
                geminiClient.getConfig().getGeminiApiKey() == null ||
                geminiClient.getConfig().getGeminiApiKey().isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
    }

    private void ensureClaudeAvailable() {
        if (claudeClient == null) {
            throw new IllegalStateException("Claude client is null.");
        }
        if (claudeClient.getConfig() == null ||
                claudeClient.getConfig().getClaudeApiKey() == null ||
                claudeClient.getConfig().getClaudeApiKey().isBlank()) {
            throw new IllegalStateException("Claude API key is not configured.");
        }
    }

    private String validateAndNormalizeTask(String userTask) {
        if (userTask == null || userTask.isBlank()) {
            throw new IllegalArgumentException("Cannot classify an empty task.");
        }

        String cleaned = userTask.trim();

        int maxCharacters = 8000;
        if (cleaned.length() > maxCharacters) {
            cleaned = cleaned.substring(0, maxCharacters);
        }

        return cleaned;
    }

    private static String cleanModel(String model, String fallback) {
        if (model == null || model.isBlank()) {
            return fallback;
        }
        return model.trim();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int defaultAttemptsFor(RecommendedPipeline pipeline, TaskDifficulty difficulty) {
        if (pipeline == RecommendedPipeline.DIRECT_ANSWER) {
            return 1;
        }
        if (pipeline == RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR) {
            return difficulty == TaskDifficulty.HARD ? 4 : 3;
        }
        if (pipeline == RecommendedPipeline.TOOL_AGENT) {
            return 1;
        }
        if (pipeline == RecommendedPipeline.ASK_USER_CLARIFICATION || pipeline == RecommendedPipeline.REFUSE) {
            return 1;
        }
        return difficulty == TaskDifficulty.EASY ? 2 : 3;
    }

    private static int defaultMaxAnswerTokensFor(TaskType taskType, TaskDifficulty difficulty) {
        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return difficulty == TaskDifficulty.HARD ? 6000 : 4000;
        }
        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return difficulty == TaskDifficulty.HARD ? 5000 : 3000;
        }
        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return 2500;
        }
        return difficulty == TaskDifficulty.EASY ? 1200 : 2500;
    }

    private static String trimReason(String reason) {
        if (reason == null) {
            return "";
        }
        String cleaned = reason.trim().replaceAll("\\s+", " ");
        int maxLength = 240;
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength);
    }

    private static TaskType roughTaskType(String task) {
        String lower = task.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "java", "python", "code", "class", "method", "compile", "bug", "exception",
                "spring boot")) {
            if (containsAny(lower, "error", "bug", "fix", "compile", "exception", "stack trace")) {
                return TaskType.CODE_DEBUGGING;
            }
            return TaskType.CODE_GENERATION;
        }

        if (containsAny(lower, "architecture", "design", "system", "agent", "pipeline")) {
            return TaskType.ARCHITECTURE_DESIGN;
        }

        if (containsAny(lower, "summarize", "summary")) {
            return TaskType.SUMMARIZATION;
        }

        if (containsAny(lower, "write", "rewrite", "caption", "email", "letter")) {
            return TaskType.WRITING;
        }

        if (containsAny(lower, "patient", "diagnosis", "dose", "inj", "medical", "icu", "abg")) {
            return TaskType.MEDICAL;
        }

        if (containsAny(lower, "search", "latest", "current", "research", "paper", "github", "reddit")) {
            return TaskType.RESEARCH;
        }

        return TaskType.GENERAL_QA;
    }

    private static TaskDifficulty roughDifficulty(String task) {
        String lower = task.toLowerCase(Locale.ROOT);
        int length = task.length();

        if (length > 2500) {
            return TaskDifficulty.HARD;
        }

        if (containsAny(lower, "architecture", "recursive", "agent", "debug", "compile", "production", "security",
                "medical", "legal", "financial")) {
            return TaskDifficulty.HARD;
        }

        if (length > 700 || containsAny(lower, "code", "java", "spring", "api", "refactor", "detailed")) {
            return TaskDifficulty.MEDIUM;
        }

        return TaskDifficulty.EASY;
    }

    private static boolean containsAny(String text, String... terms) {
        if (text == null) {
            return false;
        }

        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }

        return false;
    }

    public enum ClassifierProvider {
        AUTO,
        OPENAI,
        GEMINI,
        CLAUDE
    }

    public enum TaskType {
        GENERAL_QA,
        CODE_GENERATION,
        CODE_DEBUGGING,
        ARCHITECTURE_DESIGN,
        WRITING,
        SUMMARIZATION,
        MEDICAL,
        RESEARCH,
        TOOL_REQUIRED,
        UNKNOWN
    }

    public enum TaskDifficulty {
        EASY,
        MEDIUM,
        HARD
    }

    public enum RecommendedPipeline {
        DIRECT_ANSWER,
        THINK_CRITIC_REPAIR,
        PLAN_THINK_CRITIC_REPAIR,
        TOOL_AGENT,
        ASK_USER_CLARIFICATION,
        REFUSE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskClassification {

        public TaskType taskType;
        public TaskDifficulty difficulty;
        public boolean needsDeepReasoning;
        public boolean needsTools;
        public boolean needsWeb;
        public boolean needsFileAccess;
        public boolean needsUserClarification;
        public RecommendedPipeline recommendedPipeline;
        public int maxAttempts;
        public int successThreshold;
        public int maxAnswerTokens;
        public String reason;
        public String providerUsed;

        public TaskClassification() {
        }

        public TaskClassification(
                TaskType taskType,
                TaskDifficulty difficulty,
                boolean needsDeepReasoning,
                boolean needsTools,
                boolean needsWeb,
                boolean needsFileAccess,
                boolean needsUserClarification,
                RecommendedPipeline recommendedPipeline,
                int maxAttempts,
                int successThreshold,
                int maxAnswerTokens,
                String reason,
                String providerUsed) {
            this.taskType = Objects.requireNonNullElse(taskType, TaskType.UNKNOWN);
            this.difficulty = Objects.requireNonNullElse(difficulty, TaskDifficulty.MEDIUM);
            this.needsDeepReasoning = needsDeepReasoning;
            this.needsTools = needsTools;
            this.needsWeb = needsWeb;
            this.needsFileAccess = needsFileAccess;
            this.needsUserClarification = needsUserClarification;
            this.recommendedPipeline = Objects.requireNonNullElse(
                    recommendedPipeline,
                    RecommendedPipeline.THINK_CRITIC_REPAIR);
            this.maxAttempts = maxAttempts;
            this.successThreshold = successThreshold;
            this.maxAnswerTokens = maxAnswerTokens;
            this.reason = reason;
            this.providerUsed = providerUsed;
        }

        @Override
        public String toString() {
            return "TaskClassification{" +
                    "taskType=" + taskType +
                    ", difficulty=" + difficulty +
                    ", needsDeepReasoning=" + needsDeepReasoning +
                    ", needsTools=" + needsTools +
                    ", needsWeb=" + needsWeb +
                    ", needsFileAccess=" + needsFileAccess +
                    ", needsUserClarification=" + needsUserClarification +
                    ", recommendedPipeline=" + recommendedPipeline +
                    ", maxAttempts=" + maxAttempts +
                    ", successThreshold=" + successThreshold +
                    ", maxAnswerTokens=" + maxAnswerTokens +
                    ", reason='" + reason + '\'' +
                    ", providerUsed='" + providerUsed + '\'' +
                    '}';
        }
    }
}
