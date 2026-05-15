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
 * This class must stay small, cheap, and predictable.
 * It does not solve the user's task.
 * It does not decide retry/repair attempt counts.
 *
 * It DOES decide the answer token budget.
 *
 * That token-budget authority is deliberately bounded by deterministic Java
 * rules.
 * The model is allowed to say:
 *
 * "This looks like a large code-generation task. Give it 14000 answer tokens."
 *
 * But the model is not allowed to say:
 *
 * "Give this tiny one-line answer 50000 tokens."
 *
 * Therefore:
 * - the classifier model recommends max_answer_tokens
 * - Java validates and clamps that recommendation
 * - obvious large-code tasks are upgraded deterministically if the classifier
 * underestimates them
 *
 * Very important:
 * Attempts are runtime policy, not classifier policy.
 * If the system allows the classifier model to decide attempts, then one vague
 * JSON field can suddenly make
 * Agent-Nero spend several minutes doing repeated thinker/critic/repair loops.
 * That is exactly the wrong place to control commercial UX.
 *
 * The only reason maxAttempts still exists in TaskClassification is backward
 * compatibility with older code that may still read classification.maxAttempts.
 * It is deliberately set to 0 by this class.
 */
public class TaskClassifier {

    private static final String DEFAULT_OPENAI_CLASSIFIER_MODEL = ModelConstants.GPT_5_NANO;
    private static final String DEFAULT_GEMINI_CLASSIFIER_MODEL = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private static final String DEFAULT_CLAUDE_CLASSIFIER_MODEL = ModelConstants.CLAUDE_HAIKU_4_5;

    private static final int MAX_CLASSIFIER_INPUT_CHARS = 8000;
    private static final int MAX_REASON_CHARS = 240;

    /*
     * Global hard boundary for answer-token budget.
     *
     * These are not reasoning tokens.
     * These are not total API tokens.
     * This is the downstream answer budget that MiniAgent should try to preserve.
     *
     * If another class later clamps this value lower, this classifier's decision
     * will be lost.
     * In particular, check AgentRunPlan if you expect values above 12000 to
     * survive.
     */
    private static final int MIN_ANSWER_TOKENS = 500;
    private static final int MAX_ANSWER_TOKENS = 16000;

    private final OpenAiHttpClient openAiClient;
    private final GeminiHttpClient geminiClient;
    private final ClaudeHttpClient claudeClient;
    private final ObjectMapper mapper;

    private final String openAiClassifierModel;
    private final String geminiClassifierModel;
    private final String claudeClassifierModel;

    /**
     * Normal constructor used by production wiring.
     *
     * It chooses the default cheap classifier models from ModelConstants.
     * The classifier should not use expensive deep models because this call happens
     * before the real task starts.
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
     * Constructor used when the server wants to override classifier models.
     *
     * This is useful for testing, Railway configuration, or emergency fallback
     * changes without touching the main classifier code.
     *
     * Null or blank model names are replaced with safe defaults.
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
        this.mapper = mapper != null ? mapper : new ObjectMapper();

        this.openAiClassifierModel = cleanModel(openAiClassifierModel, DEFAULT_OPENAI_CLASSIFIER_MODEL);
        this.geminiClassifierModel = cleanModel(geminiClassifierModel, DEFAULT_GEMINI_CLASSIFIER_MODEL);
        this.claudeClassifierModel = cleanModel(claudeClassifierModel, DEFAULT_CLAUDE_CLASSIFIER_MODEL);
    }

    /**
     * Classifies a task using automatic provider selection.
     *
     * AUTO means:
     * 1. Try OpenAI first if configured.
     * 2. Then Gemini if OpenAI is unavailable or fails.
     * 3. Then Claude if both earlier providers fail.
     *
     * The provider fallback is intentionally local to classification.
     * A classifier failure should not kill the whole agent run when deterministic
     * fallback can still create a usable plan.
     */
    public TaskClassification classify(String userTask) {
        return classify(userTask, ClassifierProvider.AUTO);
    }

    /**
     * Classifies a task using the requested provider first.
     *
     * If the requested provider fails, this method still tries the remaining
     * providers.
     *
     * That behavior is intentional. Classification is a routing step, so it should
     * be resilient rather than fragile.
     */
    public TaskClassification classify(String userTask, ClassifierProvider preferredProvider) {
        String normalizedTask = validateAndNormalizeTask(userTask);
        ClassifierProvider provider = preferredProvider != null ? preferredProvider : ClassifierProvider.AUTO;

        List<ClassifierProvider> providerOrder = buildProviderOrder(provider);
        List<String> failures = new ArrayList<>();

        for (ClassifierProvider currentProvider : providerOrder) {
            try {
                String rawJson = invokeProvider(currentProvider, normalizedTask);
                return parseAndNormalize(rawJson, currentProvider, normalizedTask);
            } catch (Exception ex) {
                failures.add(currentProvider + ": " + ex.getMessage());
            }
        }

        return fallbackClassification(normalizedTask, failures);
    }

    /**
     * Calls the selected provider's structured-output endpoint.
     *
     * The classifier prompt already asks for JSON only.
     *
     * Provider-specific HTTP behavior, response_format handling, Responses API
     * handling, and temperature handling must remain inside the individual HTTP
     * client classes.
     *
     * This class should not know those transport details.
     */
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

    /**
     * Builds the system prompt for the classifier model.
     *
     * The important change:
     * TaskClassifier now owns max_answer_tokens.
     *
     * The classifier model is explicitly told the allowed ranges.
     * Then Java hard-validates the model's recommendation after parsing.
     *
     * What is deliberately still absent:
     * - no real max_attempts authority
     * - no retry policy authority
     * - no repair loop authority
     *
     * Token budget is a task-size estimate.
     * Attempt count is a runtime/commercial UX policy.
     * Keep those separate.
     */
    private String buildSystemPrompt() {
        return """
                You are TaskClassifier, a strict routing component inside a Java AI agent system.

                Your job:
                - Classify the user's task.
                - Recommend the cheapest safe MiniAgent pipeline.
                - Decide max_answer_tokens for the final answer.
                - Return only valid JSON.
                - Do not solve the task.
                - Do not explain the classification outside JSON.

                Important routing rules:
                - Prefer EASY unless the task clearly requires multiple steps.
                - Set needs_deep_reasoning=true only for complex code, architecture, debugging, medical/legal/financial reasoning, math, research, or multi-step planning.
                - Set needs_tools=true only if external action is needed: reading files, running code, web search, browser control, API calls, database access, calendar/email, or live state.
                - Set needs_web=true only if current/latest/niche information is required.
                - Set needs_file_access=true only if the user asks to inspect, upload, read, compare, or modify files/project code.
                - Set needs_user_clarification=true only if the task cannot be safely attempted from the provided input.
                - Set recommended_pipeline=DIRECT_ANSWER for simple tasks.
                - Set recommended_pipeline=THINK_CRITIC_REPAIR for medium quality-sensitive tasks.
                - Set recommended_pipeline=PLAN_THINK_CRITIC_REPAIR for hard multi-step non-tool tasks.
                - Set recommended_pipeline=TOOL_AGENT for tasks requiring tools or external observations.
                - Set recommended_pipeline=ASK_USER_CLARIFICATION if essential information is missing.
                - Set recommended_pipeline=REFUSE only for unsafe or disallowed tasks.
                - Always set max_attempts=0. This field exists only for backward compatibility. Do not use it to control retries.

                max_answer_tokens rules:
                - max_answer_tokens is the estimated final answer budget.
                - It must be an integer.
                - It must never be below 500.
                - It must never be above 16000.
                - For short EASY non-code tasks: use 500 to 1800.
                - For MEDIUM non-code tasks: use 1800 to 5000.
                - For HARD non-code tasks: use 4000 to 10000.
                - For EASY code tasks: use 2000 to 5000.
                - For MEDIUM code/debugging tasks: use 5000 to 10000.
                - For HARD complete code/debugging tasks: use 10000 to 16000.
                - For very large complete app/frontend/backend generation: use 14000 to 16000.
                - For research or architecture design: use 3000 to 14000 depending on difficulty.
                - For simple summaries or rewrites: use 800 to 3000.
                - Do not choose tiny token budgets for complete-code requests.
                - Do not choose huge token budgets for simple Q&A.

                Do not include retry counts, attempt counts, loop counts, or execution-time policy.
                Those are controlled by the runtime planner, not by the classifier.

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
                  "max_attempts": 0,
                  "success_threshold": 8,
                  "max_answer_tokens": 1200,
                  "reason": "Short reason under 30 words."
                }

                JSON only.
                """;
    }

    /**
     * Builds the user prompt sent to the classifier model.
     *
     * The word JSON is intentionally present in the full message set through the
     * system prompt.
     *
     * That matters for OpenAI JSON-mode style structured calls, which can reject
     * requests when no message contains the word JSON.
     */
    private String buildUserPrompt(String userTask) {
        return """
                Classify this user task.

                USER_TASK:
                %s
                """.formatted(userTask);
    }

    /**
     * Parses the model's JSON and then applies deterministic cleanup.
     *
     * The model is allowed to suggest:
     * - task type
     * - difficulty
     * - pipeline
     * - tool needs
     * - success threshold
     * - max_answer_tokens
     *
     * The model is not allowed to control attempts.
     *
     * Token budget flow:
     * 1. Read classification.maxAnswerTokens from model JSON.
     * 2. Upgrade obvious large-code tasks before final token normalization.
     * 3. Clamp the token budget inside task-appropriate Java ranges.
     * 4. Return the final validated value.
     *
     * Normalization here protects the rest of MiniAgent from weak classifier
     * output:
     * - missing enum values get safe defaults
     * - web/file access automatically implies tools
     * - tool-required tasks are forced to TOOL_AGENT unless they are
     * clarification/refusal cases
     * - large complete code requests get upgraded to hard code generation
     */
    private TaskClassification parseAndNormalize(
            String rawJson,
            ClassifierProvider providerUsed,
            String normalizedTask) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Classifier returned blank JSON.");
        }

        TaskClassification classification = mapper.readValue(rawJson, TaskClassification.class);

        TaskType taskType = classification.taskType != null
                ? classification.taskType
                : TaskType.UNKNOWN;

        TaskDifficulty difficulty = classification.difficulty != null
                ? classification.difficulty
                : TaskDifficulty.MEDIUM;

        RecommendedPipeline pipeline = classification.recommendedPipeline != null
                ? classification.recommendedPipeline
                : RecommendedPipeline.THINK_CRITIC_REPAIR;

        boolean needsDeepReasoning = classification.needsDeepReasoning;
        boolean needsTools = classification.needsTools;
        boolean needsWeb = classification.needsWeb;
        boolean needsFileAccess = classification.needsFileAccess;
        boolean needsUserClarification = classification.needsUserClarification;

        int successThreshold = clamp(classification.successThreshold, 6, 10, 8);

        boolean largeCodeTask = looksLikeLargeCodeTask(normalizedTask);

        if (largeCodeTask) {
            taskType = TaskType.CODE_GENERATION;
            difficulty = TaskDifficulty.HARD;
            needsDeepReasoning = true;
        }

        int maxAnswerTokens = normalizeAnswerTokenBudget(
                classification.maxAnswerTokens,
                taskType,
                difficulty,
                normalizedTask);

        if (needsWeb || needsFileAccess) {
            needsTools = true;
        }

        if (needsUserClarification) {
            pipeline = RecommendedPipeline.ASK_USER_CLARIFICATION;
        } else if (needsTools && pipeline != RecommendedPipeline.REFUSE) {
            pipeline = RecommendedPipeline.TOOL_AGENT;
        }

        if (largeCodeTask) {
            /*
             * Important:
             * Do not override TOOL_AGENT here if the task also needs file/project access.
             * A large code task can still require tools when the user asks the agent to
             * inspect or edit files.
             */
            if (!needsTools
                    && !needsUserClarification
                    && pipeline != RecommendedPipeline.REFUSE) {
                pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
            }
        }

        String reason = classification.reason;
        if (reason == null || reason.isBlank()) {
            reason = "Classified by " + providerUsed + ".";
        }

        return new TaskClassification(
                taskType,
                difficulty,
                needsDeepReasoning,
                needsTools,
                needsWeb,
                needsFileAccess,
                needsUserClarification,
                pipeline,
                0,
                successThreshold,
                maxAnswerTokens,
                trimReason(reason),
                providerUsed.name());
    }

    /**
     * Normalizes the classifier model's answer-token recommendation.
     *
     * This is the key method for your requested behavior.
     *
     * The classifier model has authority to choose the budget,
     * but only inside the range that Java considers valid for the classified task.
     *
     * Example:
     * - model says EASY GENERAL_QA with 16000 tokens
     * - Java reduces it to the EASY GENERAL_QA ceiling
     *
     * Example:
     * - model says HARD complete IDE/editor code with 4000 tokens
     * - Java raises it to the large-code floor
     *
     * This makes the classifier authoritative but not dangerous.
     */
    private static int normalizeAnswerTokenBudget(
            int modelSuggestedTokens,
            TaskType taskType,
            TaskDifficulty difficulty,
            String userTask) {
        int fallback = defaultMaxAnswerTokensFor(taskType, difficulty);
        int candidate = modelSuggestedTokens > 0 ? modelSuggestedTokens : fallback;

        int minAllowed = minimumAllowedAnswerTokensFor(taskType, difficulty, userTask);
        int maxAllowed = maximumAllowedAnswerTokensFor(taskType, difficulty, userTask);

        if (minAllowed < MIN_ANSWER_TOKENS) {
            minAllowed = MIN_ANSWER_TOKENS;
        }

        if (maxAllowed > MAX_ANSWER_TOKENS) {
            maxAllowed = MAX_ANSWER_TOKENS;
        }

        if (minAllowed > maxAllowed) {
            minAllowed = MIN_ANSWER_TOKENS;
            maxAllowed = MAX_ANSWER_TOKENS;
        }

        return Math.max(minAllowed, Math.min(maxAllowed, candidate));
    }

    /**
     * Defines the lower bound for answer tokens after classification.
     *
     * This prevents absurdly tiny output budgets for tasks where the user clearly
     * asked for complete code, a full app, a serious debugging answer, or a large
     * architecture plan.
     */
    private static int minimumAllowedAnswerTokensFor(
            TaskType taskType,
            TaskDifficulty difficulty,
            String userTask) {
        if (looksLikeVeryLargeCodeTask(userTask)) {
            return 14000;
        }

        if (looksLikeLargeCodeTask(userTask)) {
            return 12000;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 2000;
                case MEDIUM -> 5000;
                case HARD -> 10000;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 1500;
                case MEDIUM -> 3000;
                case HARD -> 6000;
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
            case MEDIUM -> 1800;
            case HARD -> 4000;
        };
    }

    /**
     * Defines the upper bound for answer tokens after classification.
     *
     * This prevents the classifier from wasting massive output budgets on tiny
     * questions while still allowing very large code tasks to reach 16000.
     */
    private static int maximumAllowedAnswerTokensFor(
            TaskType taskType,
            TaskDifficulty difficulty,
            String userTask) {
        if (looksLikeVeryLargeCodeTask(userTask)) {
            return 16000;
        }

        if (looksLikeLargeCodeTask(userTask)) {
            return 16000;
        }

        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 5000;
                case MEDIUM -> 10000;
                case HARD -> 16000;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 4000;
                case MEDIUM -> 8000;
                case HARD -> 14000;
            };
        }

        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return switch (difficulty) {
                case EASY -> 3000;
                case MEDIUM -> 5000;
                case HARD -> 8000;
            };
        }

        if (taskType == TaskType.MEDICAL) {
            return switch (difficulty) {
                case EASY -> 3000;
                case MEDIUM -> 6000;
                case HARD -> 9000;
            };
        }

        return switch (difficulty) {
            case EASY -> 1800;
            case MEDIUM -> 5000;
            case HARD -> 10000;
        };
    }

    /**
     * Detects large code-generation requests that the classifier model may
     * under-score.
     *
     * This is a practical guardrail for prompts like:
     * "complete code", "full working app", "no placeholders", "VS Code-like
     * editor".
     *
     * It upgrades classification quality, token budget, and deep-reasoning need.
     * It does not set attempts.
     */
    private static boolean looksLikeLargeCodeTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean code = q.contains("code") ||
                q.contains("html") ||
                q.contains("javascript") ||
                q.contains("typescript") ||
                q.contains("java") ||
                q.contains("python") ||
                q.contains("spring") ||
                q.contains("android") ||
                q.contains("kotlin") ||
                q.contains("app") ||
                q.contains("editor") ||
                q.contains("frontend") ||
                q.contains("backend");

        boolean large = q.contains("complete") ||
                q.contains("full") ||
                q.contains("working") ||
                q.contains("production") ||
                q.contains("elaborate") ||
                q.contains("advanced") ||
                q.contains("complex") ||
                q.contains("extremely detailed") ||
                q.contains("no placeholder") ||
                q.contains("no placeholders") ||
                q.contains("must not include placeholders") ||
                q.contains("like vscode") ||
                q.contains("vs code") ||
                q.contains("visual studio code") ||
                q.contains("microsoft visual studio") ||
                q.contains("all features");

        return code && large;
    }

    /**
     * Detects the kind of request that should be given the largest answer budget.
     *
     * This catches the exact failure class you hit:
     * a user asks for a complete VS-Code-like / Visual-Studio-like editor or app,
     * and the classifier returns a budget that is too small to ever produce a
     * usable first draft.
     */
    private static boolean looksLikeVeryLargeCodeTask(String task) {
        String q = task == null ? "" : task.toLowerCase(Locale.ROOT);

        boolean editorOrIde = q.contains("vs code") ||
                q.contains("vscode") ||
                q.contains("visual studio code") ||
                q.contains("visual studio") ||
                q.contains("microsoft visual studio") ||
                q.contains("texteditor") ||
                q.contains("text editor") ||
                q.contains("ide");

        boolean completeFeatureSet = q.contains("all features") ||
                q.contains("complete with") ||
                q.contains("complete code") ||
                q.contains("full working") ||
                q.contains("production-grade") ||
                q.contains("production grade") ||
                q.contains("complex complete");

        boolean frontendApp = q.contains("index.html") ||
                q.contains("html") ||
                q.contains("javascript") ||
                q.contains("frontend") ||
                q.contains("app");

        return editorOrIde && completeFeatureSet && frontendApp;
    }

    /**
     * Builds a deterministic fallback classification when every provider fails.
     *
     * This avoids a silly failure mode where the whole agent crashes before trying
     * the real task simply because a cheap classifier had a temporary outage or
     * returned malformed JSON.
     *
     * Fallback is intentionally conservative:
     * - no external tools
     * - no web
     * - no file access
     * - no attempts
     *
     * Token budget is still chosen through the same normalization path used for
     * model classifications.
     */
    private TaskClassification fallbackClassification(String userTask, List<String> failures) {
        TaskType taskType = roughTaskType(userTask);
        TaskDifficulty difficulty = roughDifficulty(userTask);

        RecommendedPipeline pipeline = difficulty == TaskDifficulty.HARD
                ? RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR
                : RecommendedPipeline.THINK_CRITIC_REPAIR;

        boolean needsDeepReasoning = difficulty != TaskDifficulty.EASY;

        if (looksLikeLargeCodeTask(userTask)) {
            taskType = TaskType.CODE_GENERATION;
            difficulty = TaskDifficulty.HARD;
            pipeline = RecommendedPipeline.PLAN_THINK_CRITIC_REPAIR;
            needsDeepReasoning = true;
        }

        int maxAnswerTokens = normalizeAnswerTokenBudget(
                0,
                taskType,
                difficulty,
                userTask);

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
                0,
                8,
                maxAnswerTokens,
                trimReason(reason),
                "FALLBACK");
    }

    /**
     * Creates provider fallback order.
     *
     * If AUTO is selected, the normal order is OpenAI -> Gemini -> Claude.
     * If a specific provider is selected, that provider is tried first and the
     * others remain as backup.
     */
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

    /**
     * Verifies that the OpenAI classifier client can actually be used.
     *
     * This check is done before calling the provider so the failure message is
     * clear.
     */
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

    /**
     * Verifies that the Gemini classifier client can actually be used.
     *
     * This keeps provider fallback readable in logs instead of failing later with a
     * vague null pointer.
     */
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

    /**
     * Verifies that the Claude classifier client can actually be used.
     *
     * This method mirrors the OpenAI/Gemini checks so provider availability is
     * handled consistently.
     */
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

    /**
     * Validates the incoming task and trims it to a classifier-safe size.
     *
     * The classifier does not need the entire conversation or a huge pasted file.
     * It only needs enough text to determine routing.
     *
     * Trimming here reduces cost and avoids wasting classifier tokens.
     */
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
     * Normalizes model names supplied by config or tests.
     *
     * Blank model names should not crash the classifier.
     * They are replaced by the known default model.
     */
    private static String cleanModel(String model, String fallback) {
        if (model == null || model.isBlank()) {
            return fallback;
        }
        return model.trim();
    }

    /**
     * Clamps integer values coming from the model.
     *
     * This is used for quality settings like successThreshold.
     * Answer tokens use normalizeAnswerTokenBudget(), because token budget needs
     * task-aware ranges rather than one flat min/max clamp.
     *
     * It is not used for maxAttempts because TaskClassifier does not own attempt
     * policy.
     */
    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Chooses a practical default output budget when the classifier model omits
     * max_answer_tokens or returns a non-positive value.
     *
     * This is not the same as provider max tokens.
     * This is the app-level final-answer budget.
     */
    private static int defaultMaxAnswerTokensFor(TaskType taskType, TaskDifficulty difficulty) {
        if (taskType == TaskType.CODE_GENERATION || taskType == TaskType.CODE_DEBUGGING) {
            return switch (difficulty) {
                case EASY -> 3000;
                case MEDIUM -> 7000;
                case HARD -> 12000;
            };
        }

        if (taskType == TaskType.ARCHITECTURE_DESIGN || taskType == TaskType.RESEARCH) {
            return switch (difficulty) {
                case EASY -> 2500;
                case MEDIUM -> 4500;
                case HARD -> 8000;
            };
        }

        if (taskType == TaskType.WRITING || taskType == TaskType.SUMMARIZATION) {
            return switch (difficulty) {
                case EASY -> 1500;
                case MEDIUM -> 3000;
                case HARD -> 5000;
            };
        }

        if (taskType == TaskType.MEDICAL) {
            return switch (difficulty) {
                case EASY -> 1500;
                case MEDIUM -> 3000;
                case HARD -> 5000;
            };
        }

        return switch (difficulty) {
            case EASY -> 1200;
            case MEDIUM -> 2500;
            case HARD -> 5000;
        };
    }

    /**
     * Cleans the reason field so logs stay readable.
     *
     * Classifier reasons are diagnostic breadcrumbs, not essays.
     */
    private static String trimReason(String reason) {
        if (reason == null) {
            return "";
        }

        String cleaned = reason.trim().replaceAll("\\s+", " ");

        if (cleaned.length() <= MAX_REASON_CHARS) {
            return cleaned;
        }

        return cleaned.substring(0, MAX_REASON_CHARS);
    }

    /**
     * Heuristic task-type detection used only when provider classification fails.
     *
     * This is deliberately simple.
     * The real model classifier usually does better, but this fallback is good
     * enough to avoid routing everything as generic Q&A during provider outages.
     */
    private static TaskType roughTaskType(String task) {
        String lower = task == null ? "" : task.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "java", "python", "code", "class", "method", "compile", "bug", "exception",
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
     * Heuristic difficulty detection used only when provider classification fails.
     *
     * This is intentionally rough.
     * It should classify obvious hard tasks as hard, but it should not try to
     * replace the actual classifier model.
     */
    private static TaskDifficulty roughDifficulty(String task) {
        String lower = task == null ? "" : task.toLowerCase(Locale.ROOT);
        int length = task == null ? 0 : task.length();

        if (looksLikeLargeCodeTask(task)) {
            return TaskDifficulty.HARD;
        }

        if (length > 2500) {
            return TaskDifficulty.HARD;
        }

        if (containsAny(lower, "architecture", "recursive", "agent", "debug", "compile", "production",
                "security", "medical", "legal", "financial", "complete code", "full working",
                "all features", "visual studio", "vs code", "vscode")) {
            return TaskDifficulty.HARD;
        }

        if (length > 700 || containsAny(lower, "code", "java", "spring", "api", "refactor", "detailed")) {
            return TaskDifficulty.MEDIUM;
        }

        return TaskDifficulty.EASY;
    }

    /**
     * Tiny helper for readable heuristic checks.
     *
     * This avoids noisy repeated null/blank checks in roughTaskType and
     * roughDifficulty.
     */
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

    /**
     * DTO returned by the classifier.
     *
     * Fields remain public because this class is used as a simple Jackson DTO and
     * older code likely reads the values directly.
     *
     * maxAttempts is intentionally retained but neutralized:
     * - Old callers can still compile.
     * - The classifier JSON still carries max_attempts only for compatibility.
     * - This class always stores it as 0.
     * - AgentRunPlan/runtime policy must decide the actual attempts.
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

        /**
         * Backward-compatible field only.
         *
         * Do not let TaskClassifier set attempts.
         * Do not use this for runtime loop count.
         */
        @JsonProperty("max_attempts")
        public int maxAttempts;

        @JsonProperty("success_threshold")
        public int successThreshold;

        /**
         * Classifier-owned answer budget.
         *
         * This value is read from model JSON, then normalized by Java before the
         * final TaskClassification is returned.
         */
        @JsonProperty("max_answer_tokens")
        public int maxAnswerTokens;

        @JsonProperty("reason")
        public String reason;

        public String providerUsed;

        /**
         * Required by Jackson.
         *
         * Keep this empty.
         * Normalization happens after deserialization in parseAndNormalize().
         */
        public TaskClassification() {
        }

        /**
         * Main constructor used by this class.
         *
         * The maxAttempts parameter is kept only so older internal calls do not break
         * immediately.
         *
         * The assigned value is always 0 because attempts are not owned by
         * TaskClassifier.
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
            this.recommendedPipeline = Objects.requireNonNullElse(
                    recommendedPipeline,
                    RecommendedPipeline.THINK_CRITIC_REPAIR);

            /*
             * Intentionally ignore the incoming value.
             * If a model, old test, or old caller passes 3/4/5 here, it must not affect
             * runtime behavior.
             */
            this.maxAttempts = 0;

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