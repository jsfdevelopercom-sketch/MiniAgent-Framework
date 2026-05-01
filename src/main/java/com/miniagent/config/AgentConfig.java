package com.miniagent.config;

import com.miniagent.core.ModelConstants;

/**
 * AgentConfig is the primary configuration class for the MiniAgent architecture.
 * <p>
 * This class stores dynamic API keys, defaults for models, and allows on-the-fly 
 * reassignment of the "topmost model" that the system is permitted to use if 
 * escalation is required. 
 * <p>
 * By utilizing an instance of AgentConfig, multiple independent agents can be 
 * spawned simultaneously with different keys or models.
 */
public class AgentConfig {

    private String openaiApiKey;
    private String geminiApiKey;
    private String claudeApiKey;

    // The topmost default models if the system chooses to fall back or auto-assign.
    private String defaultOpenaiModel = ModelConstants.GPT_4O_MINI;
    private String defaultGeminiModel = ModelConstants.GEMINI_2_5_FLASH;
    private String defaultClaudeModel = ModelConstants.CLAUDE_HAIKU_4_5;
    
    private String topmostAllowedOpenaiModel = ModelConstants.GPT_5_4_PREVIEW;
    private String topmostAllowedGeminiModel = ModelConstants.GEMINI_3_1_PRO_PREVIEW;

    // --- Claude Models ---
    private String claudeOpus46 = ModelConstants.CLAUDE_OPUS_4_6;
    private String claudeSonnet46 = ModelConstants.CLAUDE_SONNET_4_6;
    private String claudeHaiku45 = ModelConstants.CLAUDE_HAIKU_4_5;

    // --- Gemini Models ---
    private String gemini31Pro = ModelConstants.GEMINI_3_1_PRO_PREVIEW;
    private String gemini3Flash = ModelConstants.GEMINI_3_FLASH_PREVIEW;
    private String gemini31FlashLite = ModelConstants.GEMINI_3_1_FLASH_LITE_PREVIEW;
    private String gemini25Pro = ModelConstants.GEMINI_2_5_PRO;
    private String gemini25Flash = ModelConstants.GEMINI_2_5_FLASH;
    private String gemini25FlashLite = ModelConstants.GEMINI_2_5_FLASH_LITE;

    // --- Gemini Specialty Models ---
    private String geminiNanoBanana2 = ModelConstants.GEMINI_3_1_FLASH_IMAGE_PREVIEW;
    private String geminiNanoBananaPro = ModelConstants.GEMINI_3_PRO_IMAGE_PREVIEW;
    private String geminiNanoBanana = ModelConstants.GEMINI_2_5_FLASH_IMAGE;
    private String gemini31Live = ModelConstants.GEMINI_3_1_FLASH_LIVE_PREVIEW;
    private String gemini25Live = ModelConstants.GEMINI_2_5_FLASH_NATIVE_AUDIO_PREVIEW;
    private String geminiDeepResearch = ModelConstants.GEMINI_DEEP_RESEARCH;
    private String geminiVeo31 = ModelConstants.GEMINI_VEO_3_1;
    private String geminiLyria3Pro = ModelConstants.GEMINI_LYRIA_3_PRO;

    // --- GPT Models ---
    private String gpt4o = ModelConstants.GPT_4O;
    private String gpt4oMini = ModelConstants.GPT_4O_MINI;
    private String o1Preview = ModelConstants.O1_PREVIEW;
    private String o1Mini = ModelConstants.O1_MINI;
    // --- GPT-5 Models ---
    private String gpt54 = ModelConstants.GPT_5_4_PREVIEW;

    public String getGpt54() {
        return gpt54;
    }

    public void setGpt54(String gpt54) {
        this.gpt54 = gpt54;
    }
    /**
     * Default constructor for AgentConfig.
     * API Keys should be injected post-instantiation or via overloaded constructor.
     */
    public AgentConfig() {
    }

    /**
     * Constructor allowing initialization with both keys.
     * 
     * @param openaiApiKey the OpenAI API key
     * @param geminiApiKey the Google Gemini API key
     */
    public AgentConfig(String openaiApiKey, String geminiApiKey) {
        this.openaiApiKey = openaiApiKey;
        this.geminiApiKey = geminiApiKey;
    }

    /**
     * Retrieves the current OpenAI API key.
     * 
     * @return the string representing the OpenAI API key
     */
    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    /**
     * Sets or updates the OpenAI API Key on the fly.
     * 
     * @param openaiApiKey the new API key
     */
    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    /**
     * Retrieves the current Gemini API key.
     * 
     * @return the string representing the Gemini API key
     */
    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    /**
     * Sets or updates the Gemini API Key on the fly.
     * 
     * @param geminiApiKey the new API key
     */
    public void setGeminiApiKey(String geminiApiKey) {
        this.geminiApiKey = geminiApiKey;
    }

    /**
     * Retrieves the current Claude API key.
     * 
     * @return the string representing the Claude API key
     */
    public String getClaudeApiKey() {
        return claudeApiKey;
    }

    /**
     * Sets or updates the Claude API Key on the fly.
     * 
     * @param claudeApiKey the new API key
     */
    public void setClaudeApiKey(String claudeApiKey) {
        this.claudeApiKey = claudeApiKey;
    }

    /**
     * Gets the default OpenAI model to use for worker generation.
     * 
     * @return the model name, e.g., 'gpt-4o-mini'
     */
    public String getDefaultOpenaiModel() {
        return defaultOpenaiModel;
    }

    /**
     * Sets the default OpenAI model. This can be used to dynamically change 
     * the performance vs cost tradeoff.
     * 
     * @param defaultOpenaiModel the model name to use by default
     */
    public void setDefaultOpenaiModel(String defaultOpenaiModel) {
        this.defaultOpenaiModel = defaultOpenaiModel;
    }

    /**
     * Gets the default Gemini model to use for extraction or evaluation.
     * 
     * @return the model name
     */
    public String getDefaultGeminiModel() {
        return defaultGeminiModel;
    }

    /**
     * Sets the default Gemini model.
     * 
     * @param defaultGeminiModel the model name
     */
    public void setDefaultGeminiModel(String defaultGeminiModel) {
        this.defaultGeminiModel = defaultGeminiModel;
    }

    /**
     * Gets the default Claude model.
     * 
     * @return the model name
     */
    public String getDefaultClaudeModel() {
        return defaultClaudeModel;
    }

    /**
     * Sets the default Claude model.
     * 
     * @param defaultClaudeModel the model name
     */
    public void setDefaultClaudeModel(String defaultClaudeModel) {
        this.defaultClaudeModel = defaultClaudeModel;
    }

    /**
     * Gets the topmost OpenAI model that agents are allowed to escalate to during 
     * highly complex repair cycles.
     * 
     * @return the name of the top-tier OpenAI model
     */
    public String getTopmostAllowedOpenaiModel() {
        return topmostAllowedOpenaiModel;
    }

    /**
     * Assigns the topmost allowed OpenAI model. This gatekeeping variable ensures
     * that autonomous agents do not spend excessive credits without approval.
     * 
     * @param topmostAllowedOpenaiModel the model name serving as the escalation ceiling
     */
    public void setTopmostAllowedOpenaiModel(String topmostAllowedOpenaiModel) {
        this.topmostAllowedOpenaiModel = topmostAllowedOpenaiModel;
    }

    /**
     * Gets the topmost Gemini model that agents are allowed to escalate to during 
     * highly complex repair cycles.
     * 
     * @return the name of the top-tier Gemini model
     */
    public String getTopmostAllowedGeminiModel() {
        return topmostAllowedGeminiModel;
    }

    /**
     * Assigns the topmost allowed Gemini model.
     * 
     * @param topmostAllowedGeminiModel the model name serving as the escalation ceiling
     */
    public void setTopmostAllowedGeminiModel(String topmostAllowedGeminiModel) {
        this.topmostAllowedGeminiModel = topmostAllowedGeminiModel;
    }
}
