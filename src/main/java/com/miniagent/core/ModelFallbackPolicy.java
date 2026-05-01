package com.miniagent.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * ModelFallbackPolicy provides fallback model sequences per stage.
 *
 * The first model is always the requested/preferred model.
 * Later models should be cheaper/stabler fallback options.
 */
public class ModelFallbackPolicy {

    private final String openAiReliable;
    private final String openAiCheap;
    private final String geminiCheap;
    private final String claudeCheap;

    public ModelFallbackPolicy() {
        this(
                ModelConstants.GPT_5_4_PREVIEW,
                ModelConstants.GPT_5_NANO,
                ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW,
                ModelConstants.CLAUDE_HAIKU_4_5);
    }

    public ModelFallbackPolicy(
            String openAiReliable,
            String openAiCheap,
            String geminiCheap,
            String claudeCheap) {
        this.openAiReliable = clean(openAiReliable, ModelConstants.GPT_5_4_PREVIEW);
        this.openAiCheap = clean(openAiCheap, ModelConstants.GPT_5_NANO);
        this.geminiCheap = clean(geminiCheap, ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW);
        this.claudeCheap = clean(claudeCheap, ModelConstants.CLAUDE_HAIKU_4_5);
    }

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

    public List<String> repairFallbacks(String preferredModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        addPreferred(models, preferredModel);

        models.add(openAiReliable);
        models.add(openAiCheap);
        models.add(geminiCheap);

        return new ArrayList<>(models);
    }

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

    private void addPreferred(LinkedHashSet<String> models, String preferred) {
        if (preferred != null && !preferred.isBlank()) {
            models.add(preferred.trim());
        }
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}