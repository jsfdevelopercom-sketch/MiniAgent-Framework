package com.miniagent.config;

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
    private String defaultOpenaiModel = "gpt-4o-mini";
    private String defaultGeminiModel = "gemini-2.5-flash";
    private String defaultClaudeModel = "claude-3-haiku-20240307";
    
    private String topmostAllowedModel = "gpt-4o";

    // --- Claude Models ---
    private String claudeOpus46 = "claude-opus-4-6";
    private String claudeSonnet46 = "claude-sonnet-4-6";
    private String claudeHaiku45 = "claude-haiku-4-5-20251001";

    // --- Gemini Models ---
    private String gemini31Pro = "gemini-3.1-pro-preview";
    private String gemini3Flash = "gemini-3-flash-preview";
    private String gemini31FlashLite = "gemini-3.1-flash-lite-preview";
    private String gemini25Pro = "gemini-2.5-pro";
    private String gemini25Flash = "gemini-2.5-flash";
    private String gemini25FlashLite = "gemini-2.5-flash-lite";

    // --- Gemini Specialty Models ---
    private String geminiNanoBanana2 = "gemini-3.1-flash-image-preview";
    private String geminiNanoBananaPro = "gemini-3-pro-image-preview";
    private String geminiNanoBanana = "gemini-2.5-flash-image";
    private String gemini31Live = "gemini-3.1-flash-live-preview";
    private String gemini25Live = "gemini-2.5-flash-native-audio-preview-12-2025";
    private String geminiDeepResearch = "deep-research-pro-preview-12-2025";
    private String geminiVeo31 = "veo-3.1-generate-preview";
    private String geminiLyria3Pro = "lyria-3-pro-preview";

    // --- GPT Models ---
    private String gpt4o = "gpt-4o";
    private String gpt4oMini = "gpt-4o-mini";
    private String o1Preview = "o1-preview";
    private String o1Mini = "o1-mini";

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
     * Gets the topmost model that agents are allowed to escalate to during 
     * highly complex repair cycles.
     * 
     * @return the name of the top-tier model
     */
    public String getTopmostAllowedModel() {
        return topmostAllowedModel;
    }

    /**
     * Assigns the topmost allowed model. This gatekeeping variable ensures
     * that autonomous agents do not spend excessive credits without approval.
     * 
     * @param topmostAllowedModel the model name serving as the escalation ceiling
     */
    public void setTopmostAllowedModel(String topmostAllowedModel) {
        this.topmostAllowedModel = topmostAllowedModel;
    }
}
