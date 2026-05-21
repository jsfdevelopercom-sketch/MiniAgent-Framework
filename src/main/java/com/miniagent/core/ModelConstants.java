package com.miniagent.core;

import java.util.Locale;
import java.util.Set;

/**
 * ModelConstants is the central registry of model names used by MiniAgent.
 *
 * Keep model strings here instead of scattering provider IDs across workers,
 * routers, evaluators, and config classes. A typo in a model name is otherwise
 * painful to debug because it appears as a provider HTTP failure much later in
 * the control flow.
 *
 * This class intentionally has no mutable state.
 */
public final class ModelConstants {

    /**
     * Private constructor because this is a constants/helper class.
     */
    private ModelConstants() {
        throw new AssertionError("ModelConstants is a static utility class.");
    }

    /*
     * OpenAI models.
     */
    public static final String BABBAGE_002 = "babbage-002";
    public static final String CHATGPT_4O_LATEST = "chatgpt-4o-latest";
    public static final String CHATGPT_IMAGE_LATEST = "chatgpt-image-latest";
    public static final String CODEX_MINI_LATEST = "codex-mini-latest";
    public static final String COMPUTER_USE_PREVIEW = "computer-use-preview";
    public static final String DALL_E_2 = "dall-e-2";
    public static final String DALL_E_3 = "dall-e-3";
    public static final String DAVINCI_002 = "davinci-002";
    public static final String GPT_3_5_TURBO = "gpt-3.5-turbo";
    public static final String GPT_4 = "gpt-4";
    public static final String GPT_4_TURBO = "gpt-4-turbo";
    public static final String GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview";
    public static final String GPT_4_1 = "gpt-4.1";
    public static final String GPT_4_1_MINI = "gpt-4.1-mini";
    public static final String GPT_4_1_NANO = "gpt-4.1-nano";
    public static final String GPT_4_5_PREVIEW = "gpt-4.5-preview";
    public static final String GPT_4O = "gpt-4o";
    public static final String GPT_4O_AUDIO_PREVIEW = "gpt-4o-audio-preview";
    public static final String GPT_4O_MINI = "gpt-4o-mini";
    public static final String GPT_4O_MINI_AUDIO_PREVIEW = "gpt-4o-mini-audio-preview";
    public static final String GPT_4O_MINI_REALTIME_PREVIEW = "gpt-4o-mini-realtime-preview";
    public static final String GPT_4O_MINI_SEARCH_PREVIEW = "gpt-4o-mini-search-preview";
    public static final String GPT_4O_MINI_TRANSCRIBE = "gpt-4o-mini-transcribe";
    public static final String GPT_4O_MINI_TTS = "gpt-4o-mini-tts";
    public static final String GPT_4O_REALTIME_PREVIEW = "gpt-4o-realtime-preview";
    public static final String GPT_4O_SEARCH_PREVIEW = "gpt-4o-search-preview";
    public static final String GPT_4O_TRANSCRIBE = "gpt-4o-transcribe";
    public static final String GPT_4O_TRANSCRIBE_DIARIZE = "gpt-4o-transcribe-diarize";
    public static final String GPT_5 = "gpt-5";
    public static final String GPT_5_CHAT_LATEST = "gpt-5-chat-latest";
    public static final String GPT_5_CODEX = "gpt-5-codex";
    public static final String GPT_5_MINI = "gpt-5-mini";
    public static final String GPT_5_NANO = "gpt-5-nano";
    public static final String GPT_5_PRO = "gpt-5-pro";
    public static final String GPT_5_1 = "gpt-5.1";
    public static final String GPT_5_1_CHAT_LATEST = "gpt-5.1-chat-latest";
    public static final String GPT_5_1_CODEX = "gpt-5.1-codex";
    public static final String GPT_5_1_CODEX_MAX = "gpt-5.1-codex-max";
    public static final String GPT_5_1_CODEX_MINI = "gpt-5.1-codex-mini";
    public static final String GPT_5_2 = "gpt-5.2";
    public static final String GPT_5_2_CHAT_LATEST = "gpt-5.2-chat-latest";
    public static final String GPT_5_2_CODEX = "gpt-5.2-codex";
    public static final String GPT_5_2_PRO = "gpt-5.2-pro";
    public static final String GPT_5_3_CHAT_LATEST = "gpt-5.3-chat-latest";
    public static final String GPT_5_3_CODEX = "gpt-5.3-codex";
    public static final String GPT_5_4 = "gpt-5.4";
    public static final String GPT_5_4_MINI = "gpt-5.4-mini";
    public static final String GPT_5_4_NANO = "gpt-5.4-nano";
    public static final String GPT_5_4_PRO = "gpt-5.4-pro";
    public static final String GPT_5_5 = "gpt-5.5";
    public static final String GPT_5_5_PRO = "gpt-5.5-pro";
    public static final String GPT_AUDIO = "gpt-audio";
    public static final String GPT_AUDIO_1_5 = "gpt-audio-1.5";
    public static final String GPT_AUDIO_MINI = "gpt-audio-mini";
    public static final String GPT_IMAGE_1 = "gpt-image-1";
    public static final String GPT_IMAGE_1_MINI = "gpt-image-1-mini";
    public static final String GPT_IMAGE_1_5 = "gpt-image-1.5";
    public static final String GPT_IMAGE_2 = "gpt-image-2";
    public static final String GPT_OSS_120B = "gpt-oss-120b";
    public static final String GPT_OSS_20B = "gpt-oss-20b";
    public static final String GPT_REALTIME = "gpt-realtime";
    public static final String GPT_REALTIME_1_5 = "gpt-realtime-1.5";
    public static final String GPT_REALTIME_MINI = "gpt-realtime-mini";
    public static final String O1 = "o1";
    public static final String O1_MINI = "o1-mini";
    public static final String O1_PREVIEW = "o1-preview";
    public static final String O1_PRO = "o1-pro";
    public static final String O3 = "o3";
    public static final String O3_DEEP_RESEARCH = "o3-deep-research";
    public static final String O3_MINI = "o3-mini";
    public static final String O3_PRO = "o3-pro";
    public static final String O4_MINI = "o4-mini";
    public static final String O4_MINI_DEEP_RESEARCH = "o4-mini-deep-research";
    public static final String OMNI_MODERATION_LATEST = "omni-moderation-latest";
    public static final String SORA_2 = "sora-2";
    public static final String SORA_2_PRO = "sora-2-pro";
    public static final String TEXT_EMBEDDING_3_LARGE = "text-embedding-3-large";
    public static final String TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small";
    public static final String TEXT_EMBEDDING_ADA_002 = "text-embedding-ada-002";
    public static final String TEXT_MODERATION_LATEST = "text-moderation-latest";
    public static final String TEXT_MODERATION_STABLE = "text-moderation-stable";
    public static final String TTS_1 = "tts-1";
    public static final String TTS_1_HD = "tts-1-hd";
    public static final String WHISPER_1 = "whisper-1";

    /*
     * Anthropic Claude models.
     */
    public static final String CLAUDE_HAIKU_4_5 = "claude-haiku-4-5-20251001";
    public static final String CLAUDE_OPUS_4_6 = "claude-opus-4-6";
    public static final String CLAUDE_SONNET_4_6 = "claude-sonnet-4-6";
    public static final String CLAUDE_3_5_SONNET = "claude-3-5-sonnet";
    public static final String CLAUDE_3_5_HAIKU = "claude-3-5-haiku";
    public static final String CLAUDE_3_OPUS = "claude-3-opus";

    /*
     * Google Gemini models.
     */
    public static final String GEMINI_3_1_PRO_PREVIEW = "gemini-3.1-pro-preview";
    public static final String GEMINI_3_FLASH_PREVIEW = "gemini-3-flash-preview";
    public static final String GEMINI_3_1_FLASH_LITE_PREVIEW = "gemini-3.1-flash-lite-preview";
    public static final String GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";
    public static final String GEMINI_2_5_FLASH_LITE = "gemini-2.5-flash-lite";
    public static final String GEMINI_3_1_FLASH_IMAGE_PREVIEW = "gemini-3.1-flash-image-preview";
    public static final String GEMINI_3_PRO_IMAGE_PREVIEW = "gemini-3-pro-image-preview";
    public static final String GEMINI_2_5_FLASH_IMAGE = "gemini-2.5-flash-image";
    public static final String GEMINI_3_1_FLASH_LIVE_PREVIEW = "gemini-3.1-flash-live-preview";
    public static final String GEMINI_2_5_FLASH_NATIVE_AUDIO_PREVIEW = "gemini-2.5-flash-native-audio-preview-12-2025";
    public static final String GEMINI_DEEP_RESEARCH = "deep-research-pro-preview-12-2025";
    public static final String GEMINI_VEO_3_1 = "veo-3.1-generate-preview";
    public static final String GEMINI_LYRIA_3_PRO = "lyria-3-pro-preview";
    public static final String GEMINI_1_5_FLASH = "gemini-1.5-flash";
    public static final String GEMINI_1_5_PRO = "gemini-1.5-pro";

    /*
     * Model grouping used by routers and clients.
     */
    public static final Set<String> HIGH_THINKING_MODELS = Set.of(
            GPT_5_5_PRO,
            GPT_5_4_PRO,
            O1_PRO,
            O3_PRO,
            GPT_5_PRO);

    public static final Set<String> MEDIUM_THINKING_MODELS = Set.of(
            GPT_5_5,
            GPT_5_4,
            O1,
            O3,
            GPT_5,
            O1_PREVIEW);

    public static final Set<String> FAST_LOW_THINKING_MODELS = Set.of(
            GPT_5_4_MINI,
            GPT_5_4_NANO,
            GPT_5_MINI,
            GPT_5_NANO,
            O1_MINI,
            O3_MINI,
            O4_MINI,
            GPT_4O_MINI,
            GEMINI_2_5_FLASH,
            GEMINI_3_1_FLASH_LITE_PREVIEW,
            CLAUDE_HAIKU_4_5);

    /**
     * Returns true when the model should be treated as high-capability or costly.
     *
     * This method is intentionally heuristic. Stage-aware token/time budgets still
     * live in AgentRunPlan and provider clients, not here.
     */
    public static boolean isHighModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String lower = model.toLowerCase(Locale.ROOT).trim();

        return lower.contains("pro")
                || lower.contains("opus")
                || lower.contains("sonnet")
                || lower.contains("gpt-4o")
                || lower.contains("gpt-5")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.contains("deep-research")
                || lower.contains("lyria");
    }

    /**
     * Returns true when the model name belongs to OpenAI.
     */
    public static boolean isOpenAiModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String lower = model.toLowerCase(Locale.ROOT).trim();

        return lower.startsWith("gpt-")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.contains("deep-research");
    }

    /**
     * Returns true when the model name belongs to Gemini.
     */
    public static boolean isGeminiModel(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).trim().startsWith("gemini");
    }

    /**
     * Returns true when the model name belongs to Claude.
     */
    public static boolean isClaudeModel(String model) {
        return model != null && model.toLowerCase(Locale.ROOT).trim().startsWith("claude");
    }

    /**
     * Returns true when the model should be treated as a cheap/fast model.
     */
    public static boolean isFastLowThinkingModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        return FAST_LOW_THINKING_MODELS.contains(model.trim());
    }

    /**
     * Returns true for OpenAI reasoning-style models that often need Responses API
     * and may reject custom temperature.
     */
    public static boolean isOpenAiReasoningFamily(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String lower = model.toLowerCase(Locale.ROOT).trim();

        return lower.startsWith("gpt-5")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")
                || lower.contains("deep-research");
    }
}