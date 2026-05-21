package com.miniagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.api.ModelOutputIncompleteException;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MiniAgentWorker is the actual answer generator inside MiniAgent.
 *
 * The high-level MiniAgent pipeline is roughly:
 *
 * 1. TaskClassifier
 * Decides what kind of task this is:
 * simple answer, code generation, debugging, research, architecture, etc.
 *
 * 2. AgentRunPlan
 * Turns that classification into a concrete execution contract:
 * max attempts, max answer tokens, whether large freeform output is needed,
 * whether synthesis should be skipped, etc.
 *
 * 3. ModelRouter
 * Chooses the models for generation, critic/evaluator, repair, and synthesis.
 *
 * 4. SafeThoughtExecutor
 * Orchestrates the stages and keeps the total runtime bounded.
 *
 * 5. MiniAgentWorker
 * This class. It creates the first draft and repairs drafts when needed.
 *
 * 6. MiniAgentEvaluator / OutputSynthesizer
 * Evaluates and optionally polishes the worker output.
 *
 * Important design rule:
 *
 * The Worker must NOT independently re-detect task difficulty or seriousness.
 * It should consume AgentRunPlan. If the plan says the task needs freeform
 * output,
 * Worker uses freeform text generation. If not, Worker uses the older
 * structured
 * MiniAgent JSON contract.
 *
 * Why two modes exist:
 *
 * Structured mode is useful for small controlled answers because the model
 * returns
 * a predictable JSON object that MiniAgent can parse.
 *
 * Freeform mode is required for large code, architecture, research, and
 * long-form
 * answers because forcing huge code into JSON is slow, fragile, and easy to
 * break.
 *
 * The old failure mode was:
 *
 * - The model was called through a "text" API method,
 * - but the prompt still told it to return MiniAgent JSON,
 * - and OpenAI was not given the plan-owned token/time budget.
 *
 * This class fixes that by keeping prompt shape, API mode, and runtime budget
 * aligned with AgentRunPlan.
 */
public class MiniAgentWorker {

    /*
     * Worker-stage OpenAI budgets.
     *
     * These values are deliberately stage-level defaults. The classifier/plan can
     * provide a more specific maxAnswerTokens value, but the timeout should remain
     * bounded for commercial UX. A user will not tolerate a single first-draft call
     * silently running for 3-5 minutes before anything else in the agent pipeline
     * even begins.
     */
    private static final int DEFAULT_FREEFORM_DRAFT_TOKENS = 9_000;
    private static final int MAX_FREEFORM_DRAFT_TOKENS = 14_000;
    private static final int DEFAULT_FREEFORM_REPAIR_TOKENS = 4500;
    private static final int DEFAULT_STRUCTURED_TOKENS = 1200;

    private static final Duration FREEFORM_DRAFT_TIMEOUT = Duration.ofSeconds(115);
    private static final Duration FREEFORM_REPAIR_TIMEOUT = Duration.ofSeconds(85);
    private static final Duration STRUCTURED_TIMEOUT = Duration.ofSeconds(45);

    private final OpenAiHttpClient openAiHttpClient;
    private final GeminiHttpClient geminiHttpClient;
    private final ClaudeHttpClient claudeHttpClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    /**
     * Wires the worker to all provider clients and prompt/parser utilities.
     *
     * The worker does not own API keys, model routing, retry policy, or task
     * classification. It only receives the already-selected model and produces one
     * draft/repair result through the matching provider client. Keeping this class
     * narrow makes deep generation failures much easier to localize.
     */
    public MiniAgentWorker(
            OpenAiHttpClient openAiHttpClient,
            GeminiHttpClient geminiHttpClient,
            ClaudeHttpClient claudeHttpClient,
            PromptFactory promptFactory,
            ObjectMapper mapper) {
        this.openAiHttpClient = openAiHttpClient;
        this.geminiHttpClient = geminiHttpClient;
        this.claudeHttpClient = claudeHttpClient;
        this.promptFactory = promptFactory;
        this.mapper = mapper;
    }

    /**
     * Backward-compatible draft generation overload.
     *
     * Older callers that do not yet pass AgentRunPlan will still compile and run.
     * They intentionally fall back to structured mode because without a plan we do
     * not know if the task is safe for freeform output.
     *
     * New code should call the overload that accepts AgentRunPlan.
     */
    public StructuredResponse generateDraft(
            String model,
            String domainContext,
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history,
            Double temperature) {
        return generateDraft(
                model,
                domainContext,
                taskInstructions,
                dataset,
                liveInjections,
                history,
                temperature,
                null);
    }

    /**
     * Generates the first worker draft.
     *
     * This method is the main generation entry point used by SafeThoughtExecutor.
     *
     * The decision is intentionally simple:
     *
     * - If AgentRunPlan says freeform output is needed:
     * use direct text prompts and direct text API calls.
     *
     * - Otherwise:
     * use the older structured MiniAgent JSON prompt and structured API calls.
     *
     * This prevents the worker from accidentally asking a model to produce large
     * code inside JSON. It also lets OpenAI GPT-5.x receive the exact token/time
     * budget chosen by the plan.
     */
    public StructuredResponse generateDraft(
            String model,
            String domainContext,
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history,
            Double temperature,
            AgentRunPlan plan) {
        if (shouldUseFreeformWorkerOutput(plan)) {
            String sysPrompt = buildFreeformWorkerSystemPrompt(domainContext, model, plan);
            String userPrompt = buildFreeformWorkerUserPrompt(taskInstructions, dataset, liveInjections, plan);

            int maxOutputTokens = plan != null
                    ? plan.getMaxAnswerTokens()
                    : DEFAULT_FREEFORM_DRAFT_TOKENS;

            String text = executeFreeformDraftWithTokenRamp(
                    model,
                    sysPrompt,
                    userPrompt,
                    temperature,
                    maxOutputTokens,
                    FREEFORM_DRAFT_TIMEOUT);

            System.out.println(
                    "[MINIAGENT-WORKER] Freeform text draft generated. " +
                            "model=" + safeModel(model) +
                            ", maxOutputTokens=" + maxOutputTokens +
                            ", timeoutSeconds=" + FREEFORM_DRAFT_TIMEOUT.toSeconds() +
                            ", chars=" + safeLength(text));

            return responseFromText(text);
        }

        /*
         * Structured mode is still useful for small answers. The PromptFactory JSON
         * contract is kept here because downstream parsing expects a MiniAgent
         * StructuredResponse-like object.
         */
        String sysPrompt = promptFactory.buildWorkerSystemPrompt(domainContext, model);
        String userPrompt = promptFactory.buildWorkerUserPrompt(taskInstructions, dataset, liveInjections);

        String rawJson = executeStructuredCallForModel(
                model,
                sysPrompt,
                userPrompt,
                temperature,
                history,
                DEFAULT_STRUCTURED_TOKENS,
                STRUCTURED_TIMEOUT);

        System.out.println(
                "[MINIAGENT-WORKER] Structured JSON draft generated. " +
                        "model=" + safeModel(model) +
                        ", maxOutputTokens=" + DEFAULT_STRUCTURED_TOKENS +
                        ", timeoutSeconds=" + STRUCTURED_TIMEOUT.toSeconds() +
                        ", chars=" + safeLength(rawJson));

        return parseToStructuredResult(rawJson);
    }

    /**
     * Backward-compatible repair overload.
     *
     * Older callers still compile. They will use structured repair unless updated
     * to pass AgentRunPlan into the newer overload below.
     */
    public StructuredResponse repairDraft(
            String model,
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset) {
        return repairDraft(
                model,
                previousDraft,
                factualityFixes,
                structuralFixes,
                missingInstructions,
                dataset,
                null,
                null);
    }

    /**
     * Repairs a draft.
     *
     * The repair stage must use the same output philosophy as the first draft.
     * If the original task was large/freeform/code, repair must also stay in
     * freeform mode. Otherwise the system can generate a good code draft and then
     * ruin it by forcing repair into JSON.
     */
    public StructuredResponse repairDraft(
            String model,
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset,
            Double temperature,
            AgentRunPlan plan) {
        if (shouldUseFreeformWorkerOutput(plan)) {
            String sysPrompt = buildFreeformRepairSystemPrompt(plan);
            String userPrompt = buildFreeformRepairUserPrompt(
                    previousDraft,
                    factualityFixes,
                    structuralFixes,
                    missingInstructions,
                    dataset,
                    plan);

            String text = executeTextCallForModel(
                    model,
                    sysPrompt,
                    userPrompt,
                    temperature,
                    DEFAULT_FREEFORM_REPAIR_TOKENS,
                    FREEFORM_REPAIR_TIMEOUT);

            System.out.println(
                    "[MINIAGENT-WORKER] Freeform text repair generated. " +
                            "model=" + safeModel(model) +
                            ", maxOutputTokens=" + DEFAULT_FREEFORM_REPAIR_TOKENS +
                            ", timeoutSeconds=" + FREEFORM_REPAIR_TIMEOUT.toSeconds() +
                            ", chars=" + safeLength(text));

            return responseFromText(text);
        }

        String sysPrompt = promptFactory.buildRepairSystemPrompt();
        String userPrompt = promptFactory.buildRepairUserPrompt(
                previousDraft,
                factualityFixes,
                structuralFixes,
                missingInstructions,
                dataset);

        String rawJson = executeStructuredCallForModel(
                model,
                sysPrompt,
                userPrompt,
                temperature,
                null,
                DEFAULT_STRUCTURED_TOKENS,
                STRUCTURED_TIMEOUT);

        System.out.println(
                "[MINIAGENT-WORKER] Structured JSON repair generated. " +
                        "model=" + safeModel(model) +
                        ", maxOutputTokens=" + DEFAULT_STRUCTURED_TOKENS +
                        ", timeoutSeconds=" + STRUCTURED_TIMEOUT.toSeconds() +
                        ", chars=" + safeLength(rawJson));

        return parseToStructuredResult(rawJson);
    }

    /**
     * Uses AgentRunPlan as the source of truth.
     *
     * Do not add fresh keyword checks here.
     * Do not check arbitrary thresholds like max tokens >= 6000 here.
     *
     * If a future task should use freeform output, fix TaskClassifier/AgentRunPlan,
     * not this worker.
     */
    private boolean shouldUseFreeformWorkerOutput(AgentRunPlan plan) {
        return plan != null && plan.shouldUseFreeformWorkerOutput();
    }

    /**
     * Executes a text call against the correct provider.
     *
     * All provider branches receive the same stage-aware budget.
     *
     * This is important for coherence: if OpenAI obeys the plan but Gemini/Claude
     * fallback paths ignore the timeout/token budget, one failed GPT call can still
     * turn into several long provider calls. The worker therefore forwards the same
     * maxOutputTokens and timeout to every provider client.
     */
    private String executeTextCallForModel(
            String model,
            String sysPrompt,
            String userPrompt,
            Double temperature,
            int maxOutputTokens,
            Duration timeout) {
        String safeModel = safeModel(model);
        String lowerModel = safeModel.toLowerCase(Locale.ROOT);

        if (lowerModel.startsWith("gemini")) {
            return geminiHttpClient.executeTextCall(safeModel, sysPrompt, userPrompt, temperature, maxOutputTokens, timeout);
        }

        if (lowerModel.startsWith("claude")) {
            return claudeHttpClient.executeTextCall(safeModel, sysPrompt, userPrompt, temperature, maxOutputTokens, timeout);
        }

        return openAiHttpClient.executeTextCall(
                safeModel,
                sysPrompt,
                userPrompt,
                temperature,
                maxOutputTokens,
                timeout);
    }

    /**
     * Generates freeform text with a controlled token-ramp retry.
     *
     * This method is intentionally used only for freeform worker output. It handles
     * the common large-code case where the provider returns a real partial file but
     * marks the response incomplete because max_output_tokens was reached.
     *
     * The retry is not a new agent attempt. It is the same generation stage retried
     * with a larger output budget. That keeps StopPolicy and maxAttempts stable.
     */
    private String executeFreeformDraftWithTokenRamp(
            String model,
            String sysPrompt,
            String userPrompt,
            Double temperature,
            int startingMaxOutputTokens,
            Duration baseTimeout
    ) {
        int first = clampTokenBudget(startingMaxOutputTokens);
        int second = Math.max(first + 3000, 12000);
        int third = MAX_FREEFORM_DRAFT_TOKENS;

        int[] budgets = uniqueBudgets(first, second, third);

        ModelOutputIncompleteException lastIncomplete = null;

        for (int budget : budgets) {
            try {
                Duration timeout = timeoutForFreeformBudget(budget, baseTimeout);

                System.out.println(
                        "[MINIAGENT-WORKER] Freeform generation attempt. " +
                                "model=" + safeModel(model) +
                                ", maxOutputTokens=" + budget +
                                ", timeoutSeconds=" + timeout.toSeconds()
                );

                return executeTextCallForModel(
                        model,
                        sysPrompt,
                        userPrompt,
                        temperature,
                        budget,
                        timeout
                );
            } catch (ModelOutputIncompleteException incomplete) {
                lastIncomplete = incomplete;

                if (!incomplete.isMaxOutputTokenExhaustion()) {
                    throw incomplete;
                }

                System.out.println(
                        "[MINIAGENT-WORKER] Provider returned incomplete output due to token cap. " +
                                "Retrying with larger budget if available. " +
                                "model=" + safeModel(model) +
                                ", previousBudget=" + budget +
                                ", partialChars=" + incomplete.getPartialText().length() +
                                ", outputTokens=" + incomplete.getOutputTokens() +
                                ", reasoningTokens=" + incomplete.getReasoningTokens()
                );
            }
        }

        if (lastIncomplete != null) {
            throw lastIncomplete;
        }

        throw new IllegalStateException("Freeform generation failed before producing a response.");
    }

    private int clampTokenBudget(int value) {
        if (value <= 0) {
            return DEFAULT_FREEFORM_DRAFT_TOKENS;
        }

        return Math.max(1000, Math.min(MAX_FREEFORM_DRAFT_TOKENS, value));
    }

    private int[] uniqueBudgets(int... values) {
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();

        for (int value : values) {
            unique.add(clampTokenBudget(value));
        }

        int[] result = new int[unique.size()];
        int index = 0;

        for (Integer value : unique) {
            result[index++] = value;
        }

        return result;
    }

    private Duration timeoutForFreeformBudget(int budget, Duration baseTimeout) {
        if (budget <= 9000) {
            return baseTimeout == null ? Duration.ofSeconds(145) : baseTimeout;
        }

        if (budget <= 12000) {
            return Duration.ofSeconds(180);
        }

        return Duration.ofSeconds(220);
    }

    /**
     * Executes a structured call against the correct provider.
     *
     * Structured calls are intentionally small and time-bounded. They should be
     * used for classifier/evaluator-style JSON, not for large code output.
     */
    private String executeStructuredCallForModel(
            String model,
            String sysPrompt,
            String userPrompt,
            Double temperature,
            List<Map<String, String>> history,
            int maxOutputTokens,
            Duration timeout) {
        String safeModel = safeModel(model);
        String lowerModel = safeModel.toLowerCase(Locale.ROOT);

        if (lowerModel.startsWith("gemini")) {
            return geminiHttpClient.executeStructuredCall(safeModel, sysPrompt, userPrompt, temperature, history, maxOutputTokens, timeout);
        }

        if (lowerModel.startsWith("claude")) {
            return claudeHttpClient.executeStructuredCall(safeModel, sysPrompt, userPrompt, temperature, history, maxOutputTokens, timeout);
        }

        return openAiHttpClient.executeStructuredCall(
                safeModel,
                sysPrompt,
                userPrompt,
                temperature,
                history,
                maxOutputTokens,
                timeout);
    }

    /**
     * Builds the system/developer side of a freeform worker prompt.
     *
     * This intentionally does NOT say "return JSON".
     *
     * The worker is allowed to return the final answer directly. For a code task,
     * that means real code, not a JSON wrapper containing escaped code.
     */
    private String buildFreeformWorkerSystemPrompt(
            String domainContext,
            String model,
            AgentRunPlan plan) {
        StringBuilder builder = new StringBuilder();

        builder.append("You are MiniAgentWorker, the direct answer generator inside Agent-Nero.\n");
        builder.append("You are currently in FREEFORM TEXT MODE, not structured JSON mode.\n\n");

        if (model != null && !model.isBlank()) {
            builder.append("Selected model: ").append(model.trim()).append("\n\n");
        }

        if (domainContext != null && !domainContext.isBlank()) {
            builder.append("Domain context:\n");
            builder.append(domainContext.trim()).append("\n\n");
        }

        builder.append("Output rules:\n");
        builder.append("- Return the actual final user-facing answer directly.\n");
        builder.append("- Do not wrap the answer in JSON.\n");
        builder.append("- Do not include fields named thought_process, summary, convo, or spoken_summary.\n");
        builder.append("- Do not explain internal agent stages.\n");
        builder.append("- For code requests, return complete usable code directly.\n");
        builder.append("- For single-file requests, keep the output as one complete file if possible.\n");
        builder.append("- Avoid placeholders unless the user explicitly asked for a template.\n");
        builder.append("- Prefer correctness, completeness, and runnable structure over decorative explanation.\n");
        builder.append(
                "- Obey the user's requested output format when it does not conflict with safety or correctness.\n");

        if (plan != null) {
            builder.append("\nExecution budget:\n");
            builder.append("- Difficulty: ").append(plan.getClassification().difficulty).append("\n");
            builder.append("- Task type: ").append(plan.getClassification().taskType).append("\n");
            builder.append("- Target max answer tokens: ").append(plan.getMaxAnswerTokens()).append("\n");
            builder.append("- Complete the best useful answer within this one worker call.\n");
        }

        return builder.toString();
    }

    /**
     * Builds the user side of the freeform worker prompt.
     *
     * Dataset and live injections are added as plain text context. They are not
     * converted into a JSON schema because this method is specifically for
     * freeform output.
     */
    private String buildFreeformWorkerUserPrompt(
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections,
            AgentRunPlan plan) {
        StringBuilder builder = new StringBuilder();

        builder.append("User task:\n");
        builder.append(safeText(taskInstructions)).append("\n\n");

        appendDataset(builder, dataset);
        appendLiveInjections(builder, liveInjections);

        if (plan != null && plan.isCodeTask()) {
            builder.append("Code-specific instruction:\n");
            builder.append("Return code that is directly usable by the user. ");
            builder.append("Do not compress code into prose. ");
            builder.append("Do not replace real implementation with comments saying where code should go. ");
            builder.append("Do not omit required functions/classes just to keep the answer short.\n");
        }

        return builder.toString();
    }

    /**
     * Builds a freeform repair system prompt.
     *
     * Repair is not a second full rewrite unless needed. It should preserve useful
     * parts from the previous draft and fix the critic/validator issues.
     */
    private String buildFreeformRepairSystemPrompt(AgentRunPlan plan) {
        StringBuilder builder = new StringBuilder();

        builder.append("You are MiniAgentWorker repairing a previous freeform answer.\n");
        builder.append("You are in FREEFORM TEXT REPAIR MODE, not structured JSON mode.\n\n");
        builder.append("Output rules:\n");
        builder.append("- Return the corrected final answer directly.\n");
        builder.append("- Do not wrap the answer in JSON.\n");
        builder.append("- For code, return the corrected code/output directly.\n");
        builder.append("- Preserve useful working parts from the previous draft.\n");
        builder.append("- Fix only the listed issues and any obvious compile-breaking defects.\n");

        if (plan != null) {
            builder.append("\nTask type: ").append(plan.getClassification().taskType).append("\n");
            builder.append("Difficulty: ").append(plan.getClassification().difficulty).append("\n");
            builder.append("Target repair max answer tokens: ").append(DEFAULT_FREEFORM_REPAIR_TOKENS).append("\n");
        }

        return builder.toString();
    }

    /**
     * Builds the repair user prompt by giving the previous draft and exact repair
     * notes to the model.
     */
    private String buildFreeformRepairUserPrompt(
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset,
            AgentRunPlan plan) {
        StringBuilder builder = new StringBuilder();

        builder.append("Previous draft:\n");
        builder.append(safeText(previousDraft)).append("\n\n");

        appendIssueList(builder, "Factuality fixes", factualityFixes);
        appendIssueList(builder, "Structural/code fixes", structuralFixes);
        appendIssueList(builder, "Missing user instructions to restore", missingInstructions);
        appendDataset(builder, dataset);

        if (plan != null && plan.isCodeTask()) {
            builder.append("Repair instruction:\n");
            builder.append("Return the corrected complete code/answer directly. ");
            builder.append("Do not summarize the code instead of printing it.\n");
        }

        return builder.toString();
    }

    /**
     * Appends dataset/context to a prompt in a readable way.
     *
     * Dataset values can come from memory, uploaded files, local state, or future
     * tool outputs. The worker should see them as context, not as a schema.
     */
    private void appendDataset(StringBuilder builder, Map<String, Object> dataset) {
        if (dataset == null || dataset.isEmpty()) {
            return;
        }

        builder.append("Available dataset/context:\n");

        for (Map.Entry<String, Object> entry : dataset.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            builder.append("- ")
                    .append(entry.getKey())
                    .append(": ")
                    .append(safeText(entry.getValue()))
                    .append("\n");
        }

        builder.append("\n");
    }

    /**
     * Appends live instructions injected by the caller.
     *
     * These are usually runtime constraints that should override generic style,
     * for example "return only code" or "do not use markdown".
     */
    private void appendLiveInjections(StringBuilder builder, List<String> liveInjections) {
        if (liveInjections == null || liveInjections.isEmpty()) {
            return;
        }

        builder.append("Live instructions that must be obeyed:\n");

        for (String injection : liveInjections) {
            if (injection != null && !injection.isBlank()) {
                builder.append("- ").append(injection.trim()).append("\n");
            }
        }

        builder.append("\n");
    }

    /**
     * Adds a critic/validator issue list to the repair prompt.
     */
    private void appendIssueList(StringBuilder builder, String title, List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }

        builder.append(title).append(":\n");

        for (String issue : issues) {
            if (issue != null && !issue.isBlank()) {
                builder.append("- ").append(issue.trim()).append("\n");
            }
        }

        builder.append("\n");
    }

    /**
     * Converts a direct freeform answer into StructuredResponse.
     *
     * The rest of MiniAgent already expects StructuredResponse, so we wrap locally
     * instead of asking the model to wrap huge code in JSON. This is the key trick:
     * keep the internal Java object stable, but stop forcing the model to emit
     * that object for large outputs.
     */
    private StructuredResponse responseFromText(String text) {
        String safeText = safeText(text);

        StructuredResponse response = new StructuredResponse();

        response.setThought_process("Freeform worker output wrapped locally into StructuredResponse.");
        response.setSummary(safeText.isBlank()
                ? "The model returned an empty freeform response."
                : safeText);
        response.setConvo("");
        response.setRaw(safeText);

        return response;
    }

    /**
     * Parses MiniAgent structured JSON into StructuredResponse.
     *
     * This parser is intentionally forgiving because LLMs sometimes add code
     * fences or extra text around JSON despite instructions. For small structured
     * tasks, best-effort recovery is better than failing the whole agent run.
     */
    private StructuredResponse parseToStructuredResult(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            StructuredResponse failSafe = new StructuredResponse();
            failSafe.setRaw("");
            failSafe.setThought_process("Structured response was blank.");
            failSafe.setSummary("The model returned an empty structured response.");
            return failSafe;
        }

        String cleanJson = rawJson.trim();

        int startIdx = cleanJson.indexOf('{');
        int endIdx = cleanJson.lastIndexOf('}');

        if (startIdx != -1 && endIdx != -1 && startIdx <= endIdx) {
            cleanJson = cleanJson.substring(startIdx, endIdx + 1);
        }

        try {
            StructuredResponse response = mapper.readValue(cleanJson, StructuredResponse.class);

            if (response.getSummary() == null || response.getSummary().isBlank()) {
                response.setSummary(extractBestEffortText(cleanJson));
            }

            response.setRaw(rawJson);
            return response;
        } catch (Exception e) {
            StructuredResponse failSafe = new StructuredResponse();
            failSafe.setRaw(rawJson);
            failSafe.setThought_process("Structural strictness failed, fallback parser active.");
            failSafe.setSummary(extractBestEffortText(cleanJson));
            return failSafe;
        }
    }

    /**
     * Attempts to turn malformed or non-standard JSON into readable answer text.
     *
     * This is deliberately a fallback. Normal structured responses should parse
     * directly through ObjectMapper.
     */
    private String extractBestEffortText(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "";
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(rawJson);
            StringBuilder fallback = new StringBuilder();

            java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = root.fields();

            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();

                if (field == null || field.getKey() == null) {
                    continue;
                }

                /*
                 * thought_process and convo are internal-ish fields. If parsing fails
                 * and we are flattening the JSON, prefer user-facing fields.
                 */
                if ("thought_process".equals(field.getKey()) || "convo".equals(field.getKey())) {
                    continue;
                }

                com.fasterxml.jackson.databind.JsonNode value = field.getValue();

                if (value == null || value.isNull()) {
                    continue;
                }

                if (value.isObject()) {
                    fallback.append("### ")
                            .append(field.getKey().replace("_", " ").toUpperCase(Locale.ROOT))
                            .append("\n");

                    value.fields().forEachRemaining(entry -> fallback
                            .append("- **")
                            .append(entry.getKey())
                            .append("**: ")
                            .append(entry.getValue().asText())
                            .append("\n"));
                } else if (value.isArray()) {
                    fallback.append("### ")
                            .append(field.getKey().replace("_", " ").toUpperCase(Locale.ROOT))
                            .append("\n");

                    value.forEach(element -> fallback
                            .append("- ")
                            .append(element.asText())
                            .append("\n"));
                } else {
                    fallback.append("### ")
                            .append(field.getKey().replace("_", " ").toUpperCase(Locale.ROOT))
                            .append("\n");
                    fallback.append(value.asText()).append("\n\n");
                }
            }

            String flattened = fallback.toString().trim();
            return flattened.isEmpty() ? rawJson : flattened;
        } catch (Exception ignored) {
            return rawJson;
        }
    }

    /**
     * Normalizes model names before provider routing.
     */
    private String safeModel(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }

        return model.trim();
    }

    /**
     * Converts nullable values to printable text without throwing.
     */
    private String safeText(Object value) {
        if (value == null) {
            return "";
        }

        return String.valueOf(value);
    }

    /**
     * Small logging helper.
     */
    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}