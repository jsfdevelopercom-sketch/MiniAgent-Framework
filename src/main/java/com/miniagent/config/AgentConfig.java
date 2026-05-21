package com.miniagent.config;

import com.miniagent.core.ModelConstants;

/**
 * AgentConfig stores provider credentials and default model preferences for one
 * MiniAgent instance.
 *
 * This class is intentionally simple: it is a mutable configuration object, not
 * a router and not an execution policy engine. Routing decisions belong to
 * ModelRouter, and runtime budgets belong to TaskClassifier/AgentRunPlan and
 * the
 * provider clients.
 *
 * Security note:
 * API keys should be injected server-side from environment variables. Frontend
 * code should not mutate shared production AgentConfig with user-provided keys.
 */
public class AgentConfig {

    private String openaiApiKey;
    private String geminiApiKey;
    private String claudeApiKey;

    private String defaultOpenaiModel = ModelConstants.GPT_4O_MINI;
    private String defaultGeminiModel = ModelConstants.GEMINI_2_5_FLASH;
    private String defaultClaudeModel = ModelConstants.CLAUDE_HAIKU_4_5;

    private String topmostAllowedOpenaiModel = ModelConstants.GPT_5_4;
    private String topmostAllowedGeminiModel = ModelConstants.GEMINI_3_1_PRO_PREVIEW;

    private String claudeOpus46 = ModelConstants.CLAUDE_OPUS_4_6;
    private String claudeSonnet46 = ModelConstants.CLAUDE_SONNET_4_6;
    private String claudeHaiku45 = ModelConstants.CLAUDE_HAIKU_4_5;

    private String gemini31Pro = ModelConstants.GEMINI_3_1_PRO_PREVIEW;
    private String gemini3Flash = ModelConstants.GEMINI_3_FLASH_PREVIEW;
    private String gemini31FlashLite = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private String gemini25Pro = ModelConstants.GEMINI_2_5_PRO;
    private String gemini25Flash = ModelConstants.GEMINI_2_5_FLASH;
    private String gemini25FlashLite = ModelConstants.GEMINI_2_5_FLASH_LITE;

    private String geminiNanoBanana2 = ModelConstants.GEMINI_3_1_FLASH_IMAGE_PREVIEW;
    private String geminiNanoBananaPro = ModelConstants.GEMINI_3_PRO_IMAGE_PREVIEW;
    private String geminiNanoBanana = ModelConstants.GEMINI_2_5_FLASH_IMAGE;
    private String gemini31Live = ModelConstants.GEMINI_3_1_FLASH_LIVE_PREVIEW;
    private String gemini25Live = ModelConstants.GEMINI_2_5_FLASH_NATIVE_AUDIO_PREVIEW;
    private String geminiDeepResearch = ModelConstants.GEMINI_DEEP_RESEARCH;
    private String geminiVeo31 = ModelConstants.GEMINI_VEO_3_1;
    private String geminiLyria3Pro = ModelConstants.GEMINI_LYRIA_3_PRO;

    private String gpt4o = ModelConstants.GPT_4O;
    private String gpt4oMini = ModelConstants.GPT_4O_MINI;
    private String o1Preview = ModelConstants.O1_PREVIEW;
    private String o1Mini = ModelConstants.O1_MINI;
    private String gpt54 = ModelConstants.GPT_5_4;

    /**
     * Default constructor.
     *
     * Keys can be injected later via setters. This is the most convenient path
     * for Spring/Railway code that creates AgentConfig and then fills it from
     * environment variables.
     */
    public AgentConfig() {
    }

    /**
     * Constructor for older integrations that only used OpenAI and Gemini.
     */
    public AgentConfig(String openaiApiKey, String geminiApiKey) {
        this.openaiApiKey = normalizeSecret(openaiApiKey);
        this.geminiApiKey = normalizeSecret(geminiApiKey);
    }

    /**
     * Constructor for current three-provider MiniAgent integrations.
     */
    public AgentConfig(String openaiApiKey, String geminiApiKey, String claudeApiKey) {
        this.openaiApiKey = normalizeSecret(openaiApiKey);
        this.geminiApiKey = normalizeSecret(geminiApiKey);
        this.claudeApiKey = normalizeSecret(claudeApiKey);
    }

    /**
     * Returns the configured OpenAI API key.
     */
    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    /**
     * Sets the OpenAI API key.
     *
     * Blank values are normalized to null so provider clients can produce a clear
     * "API key missing" error instead of sending an empty Authorization header.
     */
    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = normalizeSecret(openaiApiKey);
    }

    /**
     * Returns the configured Gemini API key.
     */
    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    /**
     * Sets the Gemini API key.
     */
    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = normalizeSecret(geminiApiKey);
    }

    /**
     * Returns the configured Claude API key.
     */
    public String getClaudeApiKey() {
        return claudeApiKey;
    }

    /**
     * Sets the Claude API key.
     */
    public void setClaudeApiKey(String claudeApiKey) {
        this.claudeApiKey = normalizeSecret(claudeApiKey);
    }

    /**
     * Returns the default OpenAI model used when a caller does not pass a model.
     */
    public String getDefaultOpenaiModel() {
        return defaultOpenaiModel;
    }

    /**
     * Sets the default OpenAI model.
     */
    public void setDefaultOpenaiModel(String defaultOpenaiModel) {
        this.defaultOpenaiModel = normalizeModel(defaultOpenaiModel, this.defaultOpenaiModel);
    }

    /**
     * Returns the default Gemini model.
     */
    public String getDefaultGeminiModel() {
        return defaultGeminiModel;
    }

    /**
     * Sets the default Gemini model.
     */
    public void setDefaultGeminiModel(String defaultGeminiModel) {
        this.defaultGeminiModel = normalizeModel(defaultGeminiModel, this.defaultGeminiModel);
    }

    /**
     * Returns the default Claude model.
     */
    public String getDefaultClaudeModel() {
        return defaultClaudeModel;
    }

    /**
     * Sets the default Claude model.
     */
    public void setDefaultClaudeModel(String defaultClaudeModel) {
        this.defaultClaudeModel = normalizeModel(defaultClaudeModel, this.defaultClaudeModel);
    }

    /**
     * Returns the maximum OpenAI model allowed for escalation.
     */
    public String getTopmostAllowedOpenaiModel() {
        return topmostAllowedOpenaiModel;
    }

    /**
     * Sets the maximum OpenAI model allowed for escalation.
     */
    public void setTopmostAllowedOpenaiModel(String topmostAllowedOpenaiModel) {
        this.topmostAllowedOpenaiModel = normalizeModel(topmostAllowedOpenaiModel, this.topmostAllowedOpenaiModel);
    }

    /**
     * Returns the maximum Gemini model allowed for escalation.
     */
    public String getTopmostAllowedGeminiModel() {
        return topmostAllowedGeminiModel;
    }

    /**
     * Sets the maximum Gemini model allowed for escalation.
     */
    public void setTopmostAllowedGeminiModel(String topmostAllowedGeminiModel) {
        this.topmostAllowedGeminiModel = normalizeModel(topmostAllowedGeminiModel, this.topmostAllowedGeminiModel);
    }

    /**
     * Returns GPT-5.4 alias used by older project code.
     */
    public String getGpt54() {
        return gpt54;
    }

    /**
     * Sets GPT-5.4 alias used by older project code.
     */
    public void setGpt54(String gpt54) {
        this.gpt54 = normalizeModel(gpt54, this.gpt54);
    }

    /**
     * Returns Claude Opus 4.6 alias.
     */
    public String getClaudeOpus46() {
        return claudeOpus46;
    }

    /**
     * Sets Claude Opus 4.6 alias.
     */
    public void setClaudeOpus46(String claudeOpus46) {
        this.claudeOpus46 = normalizeModel(claudeOpus46, this.claudeOpus46);
    }

    /**
     * Returns Claude Sonnet 4.6 alias.
     */
    public String getClaudeSonnet46() {
        return claudeSonnet46;
    }

    /**
     * Sets Claude Sonnet 4.6 alias.
     */
    public void setClaudeSonnet46(String claudeSonnet46) {
        this.claudeSonnet46 = normalizeModel(claudeSonnet46, this.claudeSonnet46);
    }

    /**
     * Returns Claude Haiku 4.5 alias.
     */
    public String getClaudeHaiku45() {
        return claudeHaiku45;
    }

    /**
     * Sets Claude Haiku 4.5 alias.
     */
    public void setClaudeHaiku45(String claudeHaiku45) {
        this.claudeHaiku45 = normalizeModel(claudeHaiku45, this.claudeHaiku45);
    }

    /**
     * Returns Gemini 3.1 Pro alias.
     */
    public String getGemini31Pro() {
        return gemini31Pro;
    }

    /**
     * Sets Gemini 3.1 Pro alias.
     */
    public void setGemini31Pro(String gemini31Pro) {
        this.gemini31Pro = normalizeModel(gemini31Pro, this.gemini31Pro);
    }

    /**
     * Returns Gemini 3 Flash alias.
     */
    public String getGemini3Flash() {
        return gemini3Flash;
    }

    /**
     * Sets Gemini 3 Flash alias.
     */
    public void setGemini3Flash(String gemini3Flash) {
        this.gemini3Flash = normalizeModel(gemini3Flash, this.gemini3Flash);
    }

    /**
     * Returns Gemini 3.1 Flash Lite alias.
     */
    public String getGemini31FlashLite() {
        return gemini31FlashLite;
    }

    /**
     * Sets Gemini 3.1 Flash Lite alias.
     */
    public void setGemini31FlashLite(String gemini31FlashLite) {
        this.gemini31FlashLite = normalizeModel(gemini31FlashLite, this.gemini31FlashLite);
    }

    /**
     * Returns Gemini 2.5 Pro alias.
     */
    public String getGemini25Pro() {
        return gemini25Pro;
    }

    /**
     * Sets Gemini 2.5 Pro alias.
     */
    public void setGemini25Pro(String gemini25Pro) {
        this.gemini25Pro = normalizeModel(gemini25Pro, this.gemini25Pro);
    }

    /**
     * Returns Gemini 2.5 Flash alias.
     */
    public String getGemini25Flash() {
        return gemini25Flash;
    }

    /**
     * Sets Gemini 2.5 Flash alias.
     */
    public void setGemini25Flash(String gemini25Flash) {
        this.gemini25Flash = normalizeModel(gemini25Flash, this.gemini25Flash);
    }

    /**
     * Returns Gemini 2.5 Flash Lite alias.
     */
    public String getGemini25FlashLite() {
        return gemini25FlashLite;
    }

    /**
     * Sets Gemini 2.5 Flash Lite alias.
     */
    public void setGemini25FlashLite(String gemini25FlashLite) {
        this.gemini25FlashLite = normalizeModel(gemini25FlashLite, this.gemini25FlashLite);
    }

    /**
     * Returns Gemini image model alias.
     */
    public String getGeminiNanoBanana2() {
        return geminiNanoBanana2;
    }

    /**
     * Sets Gemini image model alias.
     */
    public void setGeminiNanoBanana2(String geminiNanoBanana2) {
        this.geminiNanoBanana2 = normalizeModel(geminiNanoBanana2, this.geminiNanoBanana2);
    }

    /**
     * Returns Gemini Pro image model alias.
     */
    public String getGeminiNanoBananaPro() {
        return geminiNanoBananaPro;
    }

    /**
     * Sets Gemini Pro image model alias.
     */
    public void setGeminiNanoBananaPro(String geminiNanoBananaPro) {
        this.geminiNanoBananaPro = normalizeModel(geminiNanoBananaPro, this.geminiNanoBananaPro);
    }

    /**
     * Returns older Gemini image model alias.
     */
    public String getGeminiNanoBanana() {
        return geminiNanoBanana;
    }

    /**
     * Sets older Gemini image model alias.
     */
    public void setGeminiNanoBanana(String geminiNanoBanana) {
        this.geminiNanoBanana = normalizeModel(geminiNanoBanana, this.geminiNanoBanana);
    }

    /**
     * Returns Gemini live model alias.
     */
    public String getGemini31Live() {
        return gemini31Live;
    }

    /**
     * Sets Gemini live model alias.
     */
    public void setGemini31Live(String gemini31Live) {
        this.gemini31Live = normalizeModel(gemini31Live, this.gemini31Live);
    }

    /**
     * Returns Gemini 2.5 live model alias.
     */
    public String getGemini25Live() {
        return gemini25Live;
    }

    /**
     * Sets Gemini 2.5 live model alias.
     */
    public void setGemini25Live(String gemini25Live) {
        this.gemini25Live = normalizeModel(gemini25Live, this.gemini25Live);
    }

    /**
     * Returns Gemini deep research alias.
     */
    public String getGeminiDeepResearch() {
        return geminiDeepResearch;
    }

    /**
     * Sets Gemini deep research alias.
     */
    public void setGeminiDeepResearch(String geminiDeepResearch) {
        this.geminiDeepResearch = normalizeModel(geminiDeepResearch, this.geminiDeepResearch);
    }

    /**
     * Returns Gemini Veo alias.
     */
    public String getGeminiVeo31() {
        return geminiVeo31;
    }

    /**
     * Sets Gemini Veo alias.
     */
    public void setGeminiVeo31(String geminiVeo31) {
        this.geminiVeo31 = normalizeModel(geminiVeo31, this.geminiVeo31);
    }

    /**
     * Returns Gemini Lyria alias.
     */
    public String getGeminiLyria3Pro() {
        return geminiLyria3Pro;
    }

    /**
     * Sets Gemini Lyria alias.
     */
    public void setGeminiLyria3Pro(String geminiLyria3Pro) {
        this.geminiLyria3Pro = normalizeModel(geminiLyria3Pro, this.geminiLyria3Pro);
    }

    /**
     * Returns GPT-4o alias.
     */
    public String getGpt4o() {
        return gpt4o;
    }

    /**
     * Sets GPT-4o alias.
     */
    public void setGpt4o(String gpt4o) {
        this.gpt4o = normalizeModel(gpt4o, this.gpt4o);
    }

    /**
     * Returns GPT-4o-mini alias.
     */
    public String getGpt4oMini() {
        return gpt4oMini;
    }

    /**
     * Sets GPT-4o-mini alias.
     */
    public void setGpt4oMini(String gpt4oMini) {
        this.gpt4oMini = normalizeModel(gpt4oMini, this.gpt4oMini);
    }

    /**
     * Returns o1-preview alias.
     */
    public String getO1Preview() {
        return o1Preview;
    }

    /**
     * Sets o1-preview alias.
     */
    public void setO1Preview(String o1Preview) {
        this.o1Preview = normalizeModel(o1Preview, this.o1Preview);
    }

    /**
     * Returns o1-mini alias.
     */
    public String getO1Mini() {
        return o1Mini;
    }

    /**
     * Sets o1-mini alias.
     */
    public void setO1Mini(String o1Mini) {
        this.o1Mini = normalizeModel(o1Mini, this.o1Mini);
    }

    /**
     * Trims API secrets and converts blanks to null.
     */
    private String normalizeSecret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    /**
     * Trims model names while preserving the existing value if the new input is
     * blank. This avoids accidentally erasing a configured model with an empty
     * environment variable.
     */
    private String normalizeModel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }
}