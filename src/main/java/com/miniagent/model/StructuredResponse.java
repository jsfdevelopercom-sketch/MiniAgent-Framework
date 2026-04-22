package com.miniagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * StructuredResponse represents a strictly formatted Object returned by the Worker.
 * <p>
 * It assumes a chain-of-thought mechanism ("thought_process") which forces the LLM 
 * to reason before emitting the final desired payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StructuredResponse {

    private String thought_process;
    private String summary;
    private String spoken_summary;
    private String convo;
    private String thoughtSignature;
    
    private String raw;

    /**
     * Gets the logical chain of thought reasoning supplied by the agent before generating the summary.
     */
    public String getThought_process() {
        return thought_process;
    }

    public String getThoughtSignature() {
        return thoughtSignature;
    }

    public void setThoughtSignature(String thoughtSignature) {
        this.thoughtSignature = thoughtSignature;
    }

    public void setThought_process(String thought_process) {
        this.thought_process = thought_process;
    }

    /**
     * Gets the pure, unpolluted domain output (the "summary" in a medical context).
     */
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * Gets the condensed, first-person voiceover script designed specifically for audio TTS dictation.
     */
    public String getSpoken_summary() {
        return spoken_summary;
    }

    public void setSpoken_summary(String spoken_summary) {
        this.spoken_summary = spoken_summary;
    }

    /**
     * Gets the conversational artifacts, metadata, or questions the agent wishes 
     * to communicate back to the human operator.
     */
    public String getConvo() {
        return convo;
    }

    public void setConvo(String convo) {
        this.convo = convo;
    }

    /**
     * Gets the raw LLM JSON string before parsing, useful for debugging parse failures.
     */
    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }
}
