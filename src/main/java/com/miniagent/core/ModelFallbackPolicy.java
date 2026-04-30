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
                "gpt-5.4-2026-03-05",
                "gpt-5-nano-2025-08-07",
                "gemini-3.1-flash-lite-preview",
                "claude-haiku-4-5-20251001");
    }

    public ModelFallbackPolicy(
            String openAiReliable,
            String openAiCheap,
            String geminiCheap,
            String claudeCheap) {
        this.openAiReliable = clean(openAiReliable, "gpt-5.4-2026-03-05");
        this.openAiCheap = clean(openAiCheap, "gpt-5-nano-2025-08-07");
        this.geminiCheap = clean(geminiCheap, "gemini-3.1-flash-lite-preview");
        this.claudeCheap = clean(claudeCheap, "claude-haiku-4-5-20251001");
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