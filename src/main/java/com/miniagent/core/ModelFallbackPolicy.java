package com.miniagent.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * ModelFallbackPolicy owns candidate model ordering for each MiniAgent stage.
 *
 * This class does not decide how many candidates are actually attempted. That
 * runtime fan-out is controlled by SafeThoughtExecutor using AgentRunPlan. This
 * class simply returns an ordered list of sensible candidates for a stage.
 *
 * Important production rule:
 * Strong code generation must not be demoted to nano/flash-lite merely because
 * the first model failed. If a serious code generator fails,
 * SafeThoughtExecutor
 * should either stop within the budget or try another strong model. Cheap
 * models
 * belong in normal/simple paths and final formatting paths, not first-draft
 * production code generation.
 */
public class ModelFallbackPolicy {

    private final String openAiReliable;
    private final String openAiCheap;
    private final String geminiCheap;
    private final String claudeCheap;
    private final String geminiReliable;
    private final String claudeReliable;

    /**
     * Creates the default production policy.
     *
     * The defaults mirror ModelRouter:
     * - GPT-5.4 is the reliable OpenAI heavy generator/repair model.
     * - GPT-5 nano is cheap and used only where cheap fallback is appropriate.
     * - Gemini Pro and Claude Sonnet are strong cross-provider fallbacks.
     */
    public ModelFallbackPolicy() {
        this(
                ModelConstants.GPT_5_4,
                ModelConstants.GPT_5_NANO,
                ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW,
                ModelConstants.CLAUDE_HAIKU_4_5,
                ModelConstants.GEMINI_3_1_PRO_PREVIEW,
                ModelConstants.CLAUDE_SONNET_4_6);
    }

    /**
     * Backward-compatible constructor for old integration code.
     *
     * Older callers pass only four basic models. Strong Gemini/Claude defaults
     * are filled from ModelConstants so the newer protected paths still work.
     */
    public ModelFallbackPolicy(
            String openAiReliable,
            String openAiCheap,
            String geminiCheap,
            String claudeCheap) {
        this(
                openAiReliable,
                openAiCheap,
                geminiCheap,
                claudeCheap,
                ModelConstants.GEMINI_3_1_PRO_PREVIEW,
                ModelConstants.CLAUDE_SONNET_4_6);
    }

    /**
     * Full constructor for tests or future configuration-driven routing.
     *
     * All model names are cleaned once here so the stage methods can stay simple
     * and deterministic.
     */
    public ModelFallbackPolicy(
            String openAiReliable,
            String openAiCheap,
            String geminiCheap,
            String claudeCheap,
            String geminiReliable,
            String claudeReliable) {
        this.openAiReliable = clean(openAiReliable, ModelConstants.GPT_5_4);
        this.openAiCheap = clean(openAiCheap, ModelConstants.GPT_5_NANO);
        this.geminiCheap = clean(geminiCheap, ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW);
        this.claudeCheap = clean(claudeCheap, ModelConstants.CLAUDE_HAIKU_4_5);
        this.geminiReliable = clean(geminiReliable, ModelConstants.GEMINI_3_1_PRO_PREVIEW);
        this.claudeReliable = clean(claudeReliable, ModelConstants.CLAUDE_SONNET_4_6);
    }

    /**
     * Normal first-draft generation fallbacks.
     *
     * Used for simple/medium tasks. Cheap models are allowed here because these
     * tasks are not expected to produce production-grade code or large research.
     */
    public List<String> generationFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);

        String lower = lower(preferredModel);

        if (lower.startsWith("gemini")) {
            add(models, geminiCheap);
            add(models, openAiCheap);
            add(models, claudeCheap);
        } else if (lower.startsWith("claude")) {
            add(models, claudeCheap);
            add(models, openAiCheap);
            add(models, geminiCheap);
        } else {
            add(models, openAiCheap);
            add(models, geminiCheap);
            add(models, claudeCheap);
        }

        add(models, openAiReliable);

        return new ArrayList<>(models);
    }

    /**
     * Strong first-draft generation fallbacks.
     *
     * Used for hard code, architecture, research, and other protected tasks. The
     * list deliberately contains strong models only. SafeThoughtExecutor may trim
     * this to one candidate for commercial latency.
     */
    public List<String> strongGenerationFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);
        add(models, openAiReliable);
        add(models, claudeReliable);
        add(models, geminiReliable);

        return new ArrayList<>(models);
    }

    /**
     * Normal repair fallbacks.
     *
     * Repair for simple prose can be cheap. If the task is hard/code/freeform,
     * SafeThoughtExecutor should call strongRepairFallbacks instead.
     */
    public List<String> repairFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);
        add(models, openAiCheap);
        add(models, geminiCheap);
        add(models, claudeCheap);
        add(models, openAiReliable);

        return new ArrayList<>(models);
    }

    /**
     * Strong repair fallbacks.
     *
     * Code repair must preserve implementation detail. Avoid nano-class models
     * because they tend to compress long code or convert it into explanations.
     */
    public List<String> strongRepairFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);
        add(models, openAiReliable);
        add(models, claudeReliable);
        add(models, geminiReliable);

        return new ArrayList<>(models);
    }

    /**
     * Normal critic fallbacks.
     *
     * Critic output is structured and small, so cheaper models can be used for
     * ordinary tasks. The critic must still be reliable enough to produce JSON.
     */
    public List<String> criticFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);

        String lower = lower(preferredModel);

        if (lower.startsWith("claude")) {
            add(models, claudeCheap);
            add(models, openAiCheap);
            add(models, geminiCheap);
        } else if (lower.startsWith("gemini")) {
            add(models, geminiCheap);
            add(models, openAiCheap);
            add(models, claudeCheap);
        } else {
            add(models, openAiCheap);
            add(models, geminiCheap);
            add(models, claudeCheap);
        }

        return new ArrayList<>(models);
    }

    /**
     * Strong critic fallbacks.
     *
     * Used when a bad critic can materially hurt the final answer. Claude Sonnet
     * is intentionally early because it is often a strong evaluator, while GPT-5.4
     * remains available if cross-provider critique fails.
     */
    public List<String> strongCriticFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();

        addPreferred(models, preferredModel);
        add(models, claudeReliable);
        add(models, openAiReliable);
        add(models, geminiReliable);

        return new ArrayList<>(models);
    }

    /**
     * Returns the reliable OpenAI model configured for heavy paths.
     */
    public String getOpenAiReliable() {
        return openAiReliable;
    }

    /**
     * Returns the cheap OpenAI model configured for normal/simple paths.
     */
    public String getOpenAiCheap() {
        return openAiCheap;
    }

    /**
     * Returns the cheap Gemini model configured for normal/simple paths.
     */
    public String getGeminiCheap() {
        return geminiCheap;
    }

    /**
     * Returns the cheap Claude model configured for normal/simple paths.
     */
    public String getClaudeCheap() {
        return claudeCheap;
    }

    /**
     * Returns the reliable Gemini model configured for protected paths.
     */
    public String getGeminiReliable() {
        return geminiReliable;
    }

    /**
     * Returns the reliable Claude model configured for protected paths.
     */
    public String getClaudeReliable() {
        return claudeReliable;
    }

    /**
     * Adds a preferred model as the first candidate when present.
     */
    private void addPreferred(LinkedHashSet<String> models, String preferred) {
        add(models, preferred);
    }

    /**
     * Adds a model to the ordered set, skipping blank values.
     *
     * LinkedHashSet is used so duplicates collapse while first-seen order remains
     * stable for debugging.
     */
    private static void add(LinkedHashSet<String> models, String model) {
        if (models == null || model == null || model.isBlank()) {
            return;
        }

        models.add(model.trim());
    }

    /**
     * Returns a trimmed configured model or a safe fallback.
     */
    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    /**
     * Lowercases a nullable model string for provider-prefix checks.
     */
    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}