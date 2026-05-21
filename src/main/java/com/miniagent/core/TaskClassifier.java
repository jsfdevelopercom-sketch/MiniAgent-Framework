package com.miniagent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * It does not solve the user task. Its job is to produce a small routing object
 * that the rest of the system can trust:
 *
 * task type
 * difficulty
 * tool/web/file needs
 * recommended pipeline
 * max attempts
 * success threshold
 * one-shot answer-token budget
 *
 * This class is intentionally both model-assisted and Java-guarded. The model
 * is
 * useful for semantic classification, but the Java layer must still sanitize
 * the
 * result so a malformed JSON object cannot turn one user request into a long
 * and
 * expensive loop.
 *
 * Important control-flow rule:
 *
 * TaskClassifier is now the source of truth for maxAttempts and
 * maxAnswerTokens.
 * AgentRunPlan may clamp these values, but it must not inflate them or invent
 * a different policy.
 *
 * Backward compatibility rule:
 *
 * The JSON structure is preserved. The object still contains max_attempts and
 * max_answer_tokens, so older callers and logs do not break.
 */
public class TaskClassifier {

    private static final String DEFAULT_OPENAI_CLASSIFIER_MODEL = ModelConstants.GPT_5_NANO;
    private static final String DEFAULT_GEMINI_CLASSIFIER_MODEL = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private static final String DEFAULT_CLAUDE_CLASSIFIER_MODEL = ModelConstants.CLAUDE_HAIKU_4_5;

    private static final int MAX_CLASSIFIER_INPUT_CHARS = 8000;
    private static final int MAX_REASON_CHARS = 240;

    private static final int MIN_ANSWER_TOKENS = 500;
    private static final int MAX_ONE_SHOT_ANSWER_TOKENS = 12_000;

    private static final int MIN_ATTEMPTS = 1;
    private static final int MAX_ATTEMPTS = 2;

    private final OpenAiHttpClient openAiClient;
    private final GeminiHttpClient geminiClient;
    private final ClaudeHttpClient claudeClient;
    private final ObjectMapper mapper;
    private final TokenCountJudge tokenCountJudge;

    private final String openAiClassifierModel;
    private final String geminiClassifierModel;
    private final String claudeClassifierModel;

    /**
     * Production constructor.
     *
     * Classifier models should be cheap and fast because classification happens
     * before real work starts. If classification itself becomes expensive, every
     * request feels slow even before generation begins.
     */
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

    /**
     * Configurable constructor for tests and server overrides.
     *
     * A production server may choose different classifier models through config
     * without changing classification logic. Blank model names fall back to safe
     * defaults so a missing env var does not break the whole agent.
     */
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
        this.mapper = mapper == null ? new ObjectMapper() : mapper;

        this.openAiClassifierModel = cleanModel(openAiClassifierModel, DEFAULT_OPENAI_CLASSIFIER_MODEL);
        this.geminiClassifierModel = cleanModel(geminiClassifierModel, DEFAULT_GEMINI_CLASSIFIER_MODEL);
        this.claudeClassifierModel = cleanModel(claudeClassifierModel, DEFAULT_CLAUDE_CLASSIFIER_MODEL);

        this.tokenCountJudge = new TokenCountJudge(this.openAiClient, this.mapper);
    }

    /**
     * Classifies using the default provider order.
     *
     * The AUTO order currently tries OpenAI first, then Gemini, then Claude. If
     * all providers fail, deterministic fallback classification is used so the
     * run can still proceed in a bounded way.
     */
    public TaskClassification classify(String userTask) {
        return classify(userTask, ClassifierProvider.AUTO);
    }

    /**
     * Classifies a task using a requested provider order.
     *
     * The returned TaskClassification is already normalized. Downstream classes
     * should not read raw model JSON or reinterpret attempts/token budget.
     */
    public TaskClassification classify(String userTask, ClassifierProvider preferredProvider) {
        String normalizedTask = validateAndNormalizeTask(userTask);
        ClassifierProvider provider = preferredProvider == null ? ClassifierProvider.AUTO : preferredProvider;

        List<ClassifierProvider> providerOrder = buildProviderOrder(provider);
        List<String> failures = new ArrayList<>();

        for (ClassifierProvider currentProvider : providerOrder) {
            try {
                String rawJson = invokeProvider(currentProvider, normalizedTask);
                return parseAndNormalize(rawJson, currentProvider, normalizedTask);
            } catch (Exception ex) {
                failures.add(currentProvider + ": " + safeError(ex));
            }
        }

        return fallbackClassification(normalizedTask, failures);
    }

    /**
     * Sends the classifier prompt to one provider.
     *
     * The provider clients own HTTP details. This method only builds the small
     * classification prompt and expects a JSON object back.
     */
    private String invokeProvider(ClassifierProvider provider, String userTask) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(userTask);

        return switch (provider) {
            case OPENAI -> {
                ensureOpenAiAvailable();
                yield openAiClient.executeStructuredCall(openAiClassifierModel, systemPrompt, userPrompt, 0.0, null);
            }
            case GEMINI -> {
                ensureGeminiAvailable();
                yield geminiClient.executeStructuredCall(geminiClassifierModel, systemPrompt, userPrompt, 0.0, null);
            }
            case CLAUDE -> {
                ensureClaudeAvailable();
                yield claudeClient.executeStructuredCall(claudeClassifierModel, systemPrompt, userPrompt, 0.0, null);
            }
            case AUTO -> throw new IllegalArgumentException("AUTO should be resolved before provider invocation.");
        };
    }

    /**
     * Builds the classifier system prompt.
     *
     * The JSON shape is intentionally stable. We guide the model to return sane
     * values, but parseAndNormalize() remains the final guardrail.
     */
    private String buildSystemPrompt() {
        return """
                You are TaskClassifier, a strict routing component inside a Java AI agent system.

                Your job:
                - Classify the user's task.
                - Recommend the cheapest safe MiniAgent pipeline.
                - Recommend max_attempts.
                - Recommend max_answer_tokens.
                - Return only valid JSON.
                - Do not solve the user's task.
                - Do not explain anything outside JSON.

                Keep the JSON schema exactly compatible with older MiniAgent code.
                Do not add fields.
                Do not remove fields.

                Routing rules:
                - Prefer EASY unless the task clearly requires multiple steps.
                - Use CODE_GENERATION for new code, full files, apps, scripts, frontend/backend work, Android/Spring/Java code, etc.
                - Use CODE_DEBUGGING for stack traces, compile errors, runtime errors, broken code, or bug fixing.
                - Use ARCHITECTURE_DESIGN for system design, class design, orchestration, project structure, and backend/frontend architecture.
                - Use MEDICAL for clinical reasoning or patient-related writing.
                - Use RESEARCH when the user asks to investigate, compare sources, or use latest/current information.
                - Set needs_deep_reasoning=true for complex code, architecture, debugging, medical/legal/financial reasoning, math, research, or multi-step planning.
                - Set needs_tools=true only when external action is needed: file access, running code, web, APIs, local project inspection, etc.
                - Set needs_web=true only when current/latest/niche information is required.
                - Set needs_file_access=true only when files/project code must be read or modified.
                - Set needs_user_clarification=true only when the task cannot be safely attempted from the provided input.

                Pipeline rules:
                - DIRECT_ANSWER for simple tasks.
                - THINK_CRITIC_REPAIR for medium quality-sensitive tasks.
                - PLAN_THINK_CRITIC_REPAIR for hard multi-step non-tool tasks.
                - TOOL_AGENT for tasks requiring external observations.
                - ASK_USER_CLARIFICATION only when essential information is missing.
                - REFUSE only for unsafe or disallowed tasks.

                max_attempts rules:
                - Integer only.
                - Use 1 for EASY tasks.
                - Use 1 for MEDIUM tasks.
                - Use 1 for HARD large one-shot code generation.
                - Use 2 only for HARD non-code tasks where one repair pass is clearly useful.
                - Never return 0.
                - Never return more than 2.

                max_answer_tokens rules:
                - Integer only.
                - This is the one-shot final-answer budget, not the context window.
                - Minimum 500.
                - Maximum 12000.
                - EASY short non-code: 500 to 1500.
                - MEDIUM non-code: 1500 to 3500.
                - HARD non-code: 3500 to 6000.
                - EASY code: 2000 to 3500.
                - MEDIUM code/debugging: 3500 to 5500.
                - For HARD complete code/debugging tasks: use 8500 to 12000.
                - For very large complete app/frontend/backend generation: use 9000 to 12000.
                - Never return above 12000.
                - Runtime may retry incomplete generations up to 14000.
                - Do not use tiny budgets for complete-code requests.
                - Do not use huge budgets for simple Q&A.

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

    /** Builds the small user prompt that contains only the task to classify. */
    private String buildUserPrompt(String userTask) {
        return """
                Classify this user task.

                USER_TASK:
                %s
                """.formatted(userTask);
    }

    /**
     * Parses provider JSON and returns the normalized final classification.
     *
     * This method is where model judgement becomes trustworthy runtime policy.
     * It corrects obvious under-classification, clamps token/attempt ranges, and
     * aligns pipeline/tool flags with the sanitized task type.
     */
    private TaskClassification parseAndNormalize(
            String rawJson,
            ClassifierProvider providerUsed,
            String normalizedTask) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Classifier returned blank JSON.");
        }

        TaskClassification classification = mapper.readValue(extractJsonObject(rawJson), TaskClassification.class);

        TaskType taskType = classification.taskType == null ? TaskType.UNKNOWN : classification.taskType;
        TaskDifficulty difficulty = classification.difficulty == null ? TaskDifficulty.MEDIUM
                : classification.difficulty;
        RecommendedPipeline pipeline = classification.recommendedPipeline == null
                ? RecommendedPipeline.THINK_CRITIC_REPAIR
                : classification.recommendedPipeline;

        boolean needsDeepReasoning = classification.needsDeepReasoning;
        boolean needsTools = classification.needsTools;
        boolean needsWeb = classification.needsWeb;
        boolean needsFileAccess = classification.needsFileAccess;
        boolean needsUserClarification = classification.needsUserClarification;

        boolean heavyTask = looksLikeHeavyTask(normalizedTask);
        boolean veryHeavyTask = looksLikeVeryHeavyTask(normalizedTask);

        if (heavyTask || veryHeavyTask) {
            // General heavy tasks still require HARD difficulty and deep reasoning,
            // but we don't force taskType to CODE_GENERATION if it isn't code.
            if (taskType == TaskType.UNKNOWN || taskType == TaskType.GENERAL_QA) {
                 if (containsAny(normalizedTask.toLowerCase(Locale.ROOT), "code", "html", "javascript", "java", "python", "app")) {
                     taskType = TaskType.CODE_GENERATION;
                 } else {
                     taskType = TaskType.WRITING;
                 }
            }
            difficulty = TaskDifficulty.HARD;
            needsDeepReasoning = true;
        }

        if (needsWeb || needsFileAccess) {
            needsTools = true;
        }

        if (needsUserClarification) {
            pipeline = RecommendedPipeline.ASK_USER_CLARIFICATION;
        } else if (pipeline == RecommendedPipeline.REFUSE) {
            pipeline = RecommendedPipeline.REFUSE;
        } else if (needsTools) {
            pipeline = RecommendedPipeline.TOOL_AGENT;
        } else if (heavyTask || veryHeavyTask) {
            pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
        } else if (needsDeepReasoning && difficulty == TaskDifficulty.HARD) {
            pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
        } else if (needsDeepReasoning) {
            pipeline = RecommendedPipeline.THINK_CRITIC_REPAIR;
        }

        int successThreshold = normalizeSuccessThreshold(classification.successThreshold, difficulty, taskType);
        int maxAnswerTokens = normalizeAnswerTokenBudget(classification.maxAnswerTokens, taskType, difficulty,
                normalizedTask);
        int maxAttempts = normalizeMaxAttempts(classification.maxAttempts, taskType, difficulty, pipeline,
                normalizedTask);

        String reason = classification.reason;
        if (reason == null || reason.isBlank()) {
            reason = "Classified by " + providerUsed + ".";
        }

        TaskClassification normalized = new TaskClassification(
                taskType,
                difficulty,
                needsDeepReasoning,
                needsTools,
                needsWeb,
                needsFileAccess,
                needsUserClarification,
                pipeline,
                maxAttempts,
                successThreshold,
                maxAnswerTokens,
                trimReason(reason),
                providerUsed.name());

        TokenCountJudge.TokenBudgetDecision tokenDecision =
                tokenCountJudge.judge(normalizedTask, normalized);

        normalized.maxAnswerTokens = tokenDecision.getStartingMaxOutputTokens();
        normalized.reason = normalized.reason + " TokenBudget=" + tokenDecision.getStartingMaxOutputTokens()
                + " via " + tokenDecision.getSource();

        return normalized;
    }

    /** Sanitizes attempt count so runtime does not become an uncontrolled loop. */
    private static int normalizeMaxAttempts(
            int modelSuggestedAttempts,
            TaskType taskType,
            TaskDifficulty difficulty,
            RecommendedPipeline pipeline,
            String userTask) {
        if (pipeline == RecommendedPipeline.DIRECT_ANSWER
                || pipeline == RecommendedPipeline.ASK_USER_CLARIFICATION
                || pipeline == RecommendedPipeline.REFUSE) {
            return 1;
        }

        if (looksLikeHeavyTask(userTask) || looksLikeVeryHeavyTask(userTask)) {
            return 1;
        }

        int fallback = defaultAttemptsFor(taskType, difficulty, pipeline);
        int candidate = modelSuggestedAttempts > 0 ? modelSuggestedAttempts : fallback;
        return clamp(candidate, MIN_ATTEMPTS, MAX_ATTEMPTS);
    }

    /** Provides conservative attempt defaults when the model omits the value. */
    private static int defaultAttemptsFor(TaskType taskType, TaskDifficulty difficulty, RecommendedPipeline pipeline) {
        if (pipeline == RecommendedPipeline.DIRECT_ANSWER
                || pipeline == RecommendedPipeline.ASK_USER_CLARIFICATION
                || pipeline == RecommendedPipeline.REFUSE) {
            return 1;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING
                || taskType == TaskType.ARCHITECTURE_DESIGN) {
            return 1;
        }

        return difficulty == TaskDifficulty.HARD ? 2 : 1;
    }

    /** Sanitizes evaluator pass threshold into a realistic 6..10 range. */
    private static int normalizeSuccessThreshold(int modelSuggestedThreshold, TaskDifficulty difficulty,
            TaskType taskType) {
        int fallback = switch (difficulty) {
            case EASY -> 7;
            case MEDIUM -> 8;
            case HARD -> 8;
        };

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            fallback = difficulty == TaskDifficulty.HARD ? 8 : fallback;
        }

        return clamp(modelSuggestedThreshold <= 0 ? fallback : modelSuggestedThreshold, 6, 10);
    }

    /**
     * Sanitizes max_answer_tokens.
     *
     * This value is the one-shot final answer budget. It is intentionally capped
     * at 7000 until chunk/project generation exists.
     */
    private static int normalizeAnswerTokenBudget(
            int modelSuggestedTokens,
            TaskType taskType,
            TaskDifficulty difficulty,
            String userTask) {
        int fallback = defaultMaxAnswerTokensFor(taskType, difficulty, userTask);
        int candidate = modelSuggestedTokens > 0 ? modelSuggestedTokens : fallback;

        int minAllowed = minimumAllowedAnswerTokensFor(taskType, difficulty, userTask);
        int maxAllowed = maximumAllowedAnswerTokensFor(taskType, difficulty, userTask);

        minAllowed = clamp(minAllowed, MIN_ANSWER_TOKENS, MAX_ONE_SHOT_ANSWER_TOKENS);
        maxAllowed = clamp(maxAllowed, MIN_ANSWER_TOKENS, MAX_ONE_SHOT_ANSWER_TOKENS);

        if (minAllowed > maxAllowed) {
            minAllowed = MIN_ANSWER_TOKENS;
            maxAllowed = MAX_ONE_SHOT_ANSWER_TOKENS;
        }

        return clamp(candidate, minAllowed, maxAllowed);
    }

    /** Determines the minimum useful one-shot budget for this task class. */
    private static int minimumAllowedAnswerTokensFor(TaskType taskType, TaskDifficulty difficulty, String userTask) {
        if (looksLikeVeryHeavyTask(userTask)) {
            return 6500;
        }

        if (looksLikeHeavyTask(userTask)) {
            return 6000;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 2000;
                case MEDIUM -> 3500;
                case HARD -> 5500;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 1500;
                case MEDIUM -> 3000;
                case HARD -> 4500;
            };
        }

        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return switch (difficulty) {
                case EASY -> 800;
                case MEDIUM -> 1500;
                case HARD -> 3000;
            };
        }

        if (taskType == TaskType.MEDICAL) {
            return switch (difficulty) {
                case EASY -> 1000;
                case MEDIUM -> 2000;
                case HARD -> 3500;
            };
        }

        return switch (difficulty) {
            case EASY -> 500;
            case MEDIUM -> 1500;
            case HARD -> 3500;
        };
    }

    /** Determines the maximum allowed one-shot budget for this task class. */
    private static int maximumAllowedAnswerTokensFor(TaskType taskType, TaskDifficulty difficulty, String userTask) {
        if (looksLikeVeryHeavyTask(userTask) || looksLikeHeavyTask(userTask)) {
            return 12000;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 3500;
                case MEDIUM -> 5500;
                case HARD -> 7000;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 3500;
                case MEDIUM -> 5500;
                case HARD -> 6500;
            };
        }

        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return switch (difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4000;
                case HARD -> 5500;
            };
        }

        if (taskType == TaskType.MEDICAL) {
            return switch (difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4500;
                case HARD -> 6000;
            };
        }

        return switch (difficulty) {
            case EASY -> 1500;
            case MEDIUM -> 3500;
            case HARD -> 6000;
        };
    }

    /**
     * Provides fallback token budgets when the classifier model omits the field.
     */
    private static int defaultMaxAnswerTokensFor(TaskType taskType, TaskDifficulty difficulty, String userTask) {
        if (looksLikeVeryHeavyTask(userTask)) {
            return 7000;
        }

        if (looksLikeHeavyTask(userTask)) {
            return 6500;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4500;
                case HARD -> 6500;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4000;
                case HARD -> 5500;
            };
        }

        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return switch (difficulty) {
                case EASY -> 1200;
                case MEDIUM -> 2500;
                case HARD -> 4000;
            };
        }

        if (taskType == TaskType.MEDICAL) {
            return switch (difficulty) {
                case EASY -> 1500;
                case MEDIUM -> 3000;
                case HARD -> 4500;
            };
        }

        return switch (difficulty) {
            case EASY -> 1000;
            case MEDIUM -> 2500;
            case HARD -> 4500;
        };
    }

    /** Creates a deterministic fallback if all model classifiers fail. */
    private TaskClassification fallbackClassification(String userTask, List<String> failures) {
        TaskType taskType = roughTaskType(userTask);
        TaskDifficulty difficulty = roughDifficulty(userTask);

        boolean heavyTask = looksLikeHeavyTask(userTask) || looksLikeVeryHeavyTask(userTask);
        if (heavyTask) {
            taskType = TaskType.CODE_GENERATION;
            difficulty = TaskDifficulty.HARD;
        }

        boolean needsDeepReasoning = difficulty != TaskDifficulty.EASY;

        RecommendedPipeline pipeline;
        if (heavyTask) {
            pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
        } else if (difficulty == TaskDifficulty.HARD) {
            pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
        } else if (difficulty == TaskDifficulty.MEDIUM) {
            pipeline = RecommendedPipeline.THINK_CRITIC_REPAIR;
        } else {
            pipeline = RecommendedPipeline.DIRECT_ANSWER;
        }

        int maxAnswerTokens = normalizeAnswerTokenBudget(0, taskType, difficulty, userTask);
        int maxAttempts = normalizeMaxAttempts(0, taskType, difficulty, pipeline, userTask);
        int successThreshold = normalizeSuccessThreshold(0, difficulty, taskType);

        String reason = "Model classification failed; used deterministic fallback.";
        if (failures != null && !failures.isEmpty()) {
            reason = reason + " First error: " + failures.get(0);
        }

        return new TaskClassification(
                taskType,
                difficulty,
                needsDeepReasoning,
                false,
                false,
                false,
                false,
                pipeline,
                maxAttempts,
                successThreshold,
                maxAnswerTokens,
                trimReason(reason),
                "FALLBACK");
    }

    /** Builds provider order for AUTO or explicit classifier provider. */
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

    /** Verifies OpenAI is usable before attempting the classifier call. */
    private void ensureOpenAiAvailable() {
        if (openAiClient == null) {
            throw new IllegalStateException("OpenAI client is null.");
        }

        if (openAiClient.getConfig() == null
                || openAiClient.getConfig().getOpenaiApiKey() == null
                || openAiClient.getConfig().getOpenaiApiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured.");
        }
    }

    /** Verifies Gemini is usable before attempting the classifier call. */
    private void ensureGeminiAvailable() {
        if (geminiClient == null) {
            throw new IllegalStateException("Gemini client is null.");
        }

        if (geminiClient.getConfig() == null
                || geminiClient.getConfig().getGeminiApiKey() == null
                || geminiClient.getConfig().getGeminiApiKey().isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
    }

    /** Verifies Claude is usable before attempting the classifier call. */
    private void ensureClaudeAvailable() {
        if (claudeClient == null) {
            throw new IllegalStateException("Claude client is null.");
        }

        if (claudeClient.getConfig() == null
                || claudeClient.getConfig().getClaudeApiKey() == null
                || claudeClient.getConfig().getClaudeApiKey().isBlank()) {
            throw new IllegalStateException("Claude API key is not configured.");
        }
    }

    /** Validates and limits classifier input so prompts stay small and cheap. */
    private String validateAndNormalizeTask(String userTask) {
        if (userTask == null || userTask.isBlank()) {
            throw new IllegalArgumentException("Cannot classify an empty task.");
        }

        String cleaned = userTask.trim();
        if (cleaned.length() > MAX_CLASSIFIER_INPUT_CHARS) {
            cleaned = cleaned.substring(0, MAX_CLASSIFIER_INPUT_CHARS);
        }

        return cleaned;
    }

    /**
     * Detects large tasks that must not be under-budgeted.
     * This includes both large codebase generations and massive text tasks (essays, books, comprehensive reports).
     */
    private static boolean looksLikeHeavyTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean heavyContext = containsAny(q,
                "code", "html", "index.html", "javascript", "typescript", "java", "python",
                "spring", "android", "kotlin", "app", "editor", "frontend", "backend",
                "class", "method", "api", "script", "css",
                "essay", "book", "report", "comprehensive", "case summary", "document",
                "thesis", "whitepaper");

        boolean large = containsAny(q,
                "complete", "full", "working", "production", "production-grade", "production grade",
                "elaborate", "advanced", "complex", "extremely detailed", "no placeholder",
                "no placeholders", "must not include placeholders", "all features", "entire");

        return heavyContext && large;
    }

    /**
     * Detects truly oversized prompts (complete apps, full books) that are still capped for one-shot
     * mode.
     */
    private static boolean looksLikeVeryHeavyTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean massiveScope = containsAny(q,
                "ide", "entire codebase", "full app", "complete application", "large document",
                "massive", "entire book", "full book");

        boolean completeFeatureSet = containsAny(q,
                "all features", "complete with", "complete code", "full working",
                "production-grade", "production grade", "complex complete", "in-depth");

        boolean heavyOutput = containsAny(q, "index.html", "html", "javascript", "frontend", "app", "essay", "report", "book");

        return massiveScope && completeFeatureSet && heavyOutput;
    }

    /**
     * Rough deterministic task type detector used only when model classification
     * fails.
     */
    private static TaskType roughTaskType(String task) {
        String lower = task == null ? "" : task.toLowerCase(Locale.ROOT);

        if (containsAny(lower,
                "java", "python", "code", "class", "method", "compile", "bug", "exception",
                "spring boot", "html", "javascript", "typescript", "kotlin", "android", "frontend", "backend")) {
            if (containsAny(lower, "error", "bug", "fix", "compile", "exception", "stack trace", "failed")) {
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

    /**
     * Rough deterministic difficulty detector used only when model classification
     * fails.
     */
    private static TaskDifficulty roughDifficulty(String task) {
        String lower = task == null ? "" : task.toLowerCase(Locale.ROOT);
        int length = task == null ? 0 : task.length();

        if (looksLikeHeavyTask(task) || looksLikeVeryHeavyTask(task)) {
            return TaskDifficulty.HARD;
        }

        if (length > 2500) {
            return TaskDifficulty.HARD;
        }

        if (containsAny(lower,
                "architecture", "recursive", "agent", "debug", "compile", "production",
                "security", "medical", "legal", "financial", "complete code", "full working",
                "all features", "essay", "book", "report", "comprehensive")) {
            return TaskDifficulty.HARD;
        }

        if (length > 700 || containsAny(lower, "code", "java", "spring", "api", "refactor", "detailed")) {
            return TaskDifficulty.MEDIUM;
        }

        return TaskDifficulty.EASY;
    }

    /** Extracts a JSON object if the model wrapped JSON in markdown or prose. */
    private static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }

        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;
    }

    /** Returns a configured model or a fallback if the value is blank. */
    private static String cleanModel(String model, String fallback) {
        return model == null || model.isBlank() ? fallback : model.trim();
    }

    /** Clamps integer values into an inclusive range. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Keeps log/debug reasons compact. */
    private static String trimReason(String reason) {
        if (reason == null) {
            return "";
        }

        String cleaned = reason.trim().replaceAll("\\s+", " ");
        return cleaned.length() <= MAX_REASON_CHARS ? cleaned : cleaned.substring(0, MAX_REASON_CHARS);
    }

    /** Simple contains-any helper for deterministic fallback heuristics. */
    private static boolean containsAny(String text, String... terms) {
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
     * Converts an exception into a compact failure string for fallback reason logs.
     */
    private static String safeError(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
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

    /**
     * Jackson DTO used as the public classification result.
     *
     * Fields stay public for compatibility with older code that accesses them
     * directly. The constructor applies null defaults but does not perform heavy
     * policy normalization; parseAndNormalize() is responsible for that.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskClassification {

        @JsonProperty("task_type")
        public TaskType taskType;

        @JsonProperty("difficulty")
        public TaskDifficulty difficulty;

        @JsonProperty("needs_deep_reasoning")
        public boolean needsDeepReasoning;

        @JsonProperty("needs_tools")
        public boolean needsTools;

        @JsonProperty("needs_web")
        public boolean needsWeb;

        @JsonProperty("needs_file_access")
        public boolean needsFileAccess;

        @JsonProperty("needs_user_clarification")
        public boolean needsUserClarification;

        @JsonProperty("recommended_pipeline")
        public RecommendedPipeline recommendedPipeline;

        @JsonProperty("max_attempts")
        public int maxAttempts;

        @JsonProperty("success_threshold")
        public int successThreshold;

        @JsonProperty("max_answer_tokens")
        public int maxAnswerTokens;

        @JsonProperty("reason")
        public String reason;

        /** Local diagnostic field; not required in provider JSON. */
        public String providerUsed;

        /** Required by Jackson. Values are normalized after deserialization. */
        public TaskClassification() {
        }

        /**
         * Main constructor used by classifier normalization and deterministic fallback.
         */
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
            this.recommendedPipeline = Objects.requireNonNullElse(recommendedPipeline,
                    RecommendedPipeline.THINK_CRITIC_REPAIR);
            this.maxAttempts = maxAttempts;
            this.successThreshold = successThreshold;
            this.maxAnswerTokens = maxAnswerTokens;
            this.reason = reason;
            this.providerUsed = providerUsed;
        }

        /** Provides useful logs when tracing route decisions. */
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