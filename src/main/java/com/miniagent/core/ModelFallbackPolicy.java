package com.miniagent.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * ModelFallbackPolicy provides fallback model sequences per stage.
 *
 * The first model is always the requested/preferred model.
 * Later models are ordered by how safe they are for that stage.
 *
 * Important distinction:
 * - Normal fallbacks may include cheap models because many tasks only need
 * stable completion.
 * - Strong code fallbacks must not fall to nano-class models because code
 * repair/generation can otherwise shrink a production request into a toy demo.
 */
public class ModelFallbackPolicy {

    private final String openAiReliable;
    private final String openAiCheap;
    private final String geminiCheap;
    private final String claudeCheap;
    private final String geminiReliable;
    private final String claudeReliable;

    /**
     * Default production fallback policy.
     *
     * GPT-5.4 is treated as the reliable OpenAI workhorse.
     * GPT-5 nano remains available for cheap non-code fallback paths only.
     * Gemini Pro and Claude Sonnet are kept as strong cross-provider fallbacks.
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
     * Backward-compatible constructor.
     *
     * Existing callers that provide only cheap/reliable basics can continue
     * compiling. Strong provider defaults are filled from ModelConstants.
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
     * Full constructor.
     *
     * This is useful for tests or future configuration-driven routing where
     * reliable/cheap models may be injected from AgentConfig.
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
     * Normal generation fallbacks.
     *
     * This path is intentionally allowed to include cheap models. It is used for
     * ordinary tasks where a smaller model can safely recover a provider failure.
     * Serious code generation should use strongGenerationFallbacks(...) instead.
     */
    public List<String> generationFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        String lower = lower(preferredModel);

        if (lower.startsWith("gemini")) {
            models.add(openAiReliable);
            models.add(openAiCheap);
            models.add(geminiCheap);
        } else if (lower.startsWith("claude")) {
            models.add(openAiReliable);
            models.add(openAiCheap);
            models.add(claudeCheap);
        } else {
            models.add(openAiReliable);
            models.add(openAiCheap);
            models.add(geminiCheap);
        }

        return new ArrayList<>(models);
    }

    /**
     * Strong generation fallbacks for high-effort code and architecture tasks.
     *
     * This list deliberately excludes GPT-5 nano and cheap flash-lite. If the
     * preferred strong model fails, we should move to another strong model, not
     * to a cheap summarizer-level model that may produce stubs.
     */
    public List<String> strongGenerationFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        models.add(openAiReliable);
        models.add(geminiReliable);
        models.add(claudeReliable);

        return new ArrayList<>(models);
    }

    /**
     * Normal repair fallbacks.
     *
     * This path remains cheap-capable for non-code tasks. For serious code
     * repair, use strongRepairFallbacks(...).
     */
    public List<String> repairFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        models.add(openAiReliable);
        models.add(openAiCheap);
        models.add(geminiCheap);

        return new ArrayList<>(models);
    }

    /**
     * Strong repair fallbacks for code.
     *
     * Code repair is not summarization. A nano-class model can easily collapse a
     * production implementation into a small demo or damage syntax. Keep repair
     * on strong models only.
     */
    public List<String> strongRepairFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        models.add(openAiReliable);
        models.add(geminiReliable);
        models.add(claudeReliable);

        return new ArrayList<>(models);
    }

    /**
     * Critic fallbacks.
     *
     * Critic work may be cheaper than generation, but it still needs enough
     * reliability to return structured evaluation. Claude Sonnet remains the
     * preferred critic in routing; if unavailable, use cheap structured models.
     */
    public List<String> criticFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        String lower = lower(preferredModel);

        if (lower.startsWith("claude")) {
            models.add(openAiCheap);
            models.add(geminiCheap);
        } else if (lower.startsWith("gemini")) {
            models.add(openAiCheap);
            models.add(claudeCheap);
        } else {
            models.add(openAiCheap);
            models.add(geminiCheap);
            models.add(claudeCheap);
        }

        return new ArrayList<>(models);
    }

    /**
     * Strong critic fallbacks.
     *
     * Use this for hard code/architecture/research tasks when a failed critic
     * should not cause fake 50/50 evaluation or low-confidence loops.
     */
    public List<String> strongCriticFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        models.add(claudeReliable);
        models.add(openAiReliable);
        models.add(geminiReliable);

        return new ArrayList<>(models);
    }

    public String getOpenAiReliable() {
        return openAiReliable;
    }

    public String getOpenAiCheap() {
        return openAiCheap;
    }

    public String getGeminiCheap() {
        return geminiCheap;
    }

    public String getClaudeCheap() {
        return claudeCheap;
    }

    public String getGeminiReliable() {
        return geminiReliable;
    }

    public String getClaudeReliable() {
        return claudeReliable;
    }

    /**
     * Adds the preferred model as first candidate when it is not blank.
     */
    private void addPreferred(LinkedHashSet<String> models, String preferred) {
        if (models == null || preferred == null || preferred.isBlank()) {
            return;
        }

        models.add(preferred.trim());
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
     * Lowercases a nullable model string.
     */
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}