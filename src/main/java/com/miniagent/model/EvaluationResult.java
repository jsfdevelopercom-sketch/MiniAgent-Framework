package com.miniagent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * EvaluationResult represents the output of a multi-dimensional analysis by the Evaluator.
 * <p>
 * Instead of a single one-dimensional score, it breaks down the generation into
 * distinct metrics: Factuality, Structure, Style, and Instruction Adherence.
 * <p>
 * This rich data allows the Prompter to generate pinpoint-accurate repair instructions.
 */
public class EvaluationResult {

    private int factualityScore;
    private int structureScore;
    private int styleScore;
    private int instructionAdherenceScore;
    
    private boolean isPass;
    private String rawOutput;

    private List<String> factualityFixes = new ArrayList<>();
    private List<String> structureFixes = new ArrayList<>();
    private List<String> styleFixes = new ArrayList<>();
    private List<String> missingInstructions = new ArrayList<>();

    private String generalRationale;

    /**
     * Determines the overarching total score by aggregating the dimensions.
     * 
     * @return the average score across all four dimensions.
     */
    public int getCombinedScore() {
        return (factualityScore + structureScore + styleScore + instructionAdherenceScore) / 4;
    }

    /**
     * Determines if any specific critical dimension has failed (e.g., Factuality below 95).
     * 
     * @return true if a critical dimension enforces a repair loop.
     */
    public boolean hasCriticalFailure() {
        return factualityScore < 95 || instructionAdherenceScore < 90 || structureScore < 90;
    }

    // Getters and setters with descriptive documentation

    /**
     * Gets the factuality score (0-100). Validates against hallucination.
     */
    public int getFactualityScore() { return factualityScore; }
    public void setFactualityScore(int factualityScore) { this.factualityScore = factualityScore; }

    /**
     * Gets the structure/schema score.
     */
    public int getStructureScore() { return structureScore; }
    public void setStructureScore(int structureScore) { this.structureScore = structureScore; }

    /**
     * Gets the style score based on user context.
     */
    public int getStyleScore() { return styleScore; }
    public void setStyleScore(int styleScore) { this.styleScore = styleScore; }

    /**
     * Gets the instruction adherence score (ensuring mid-way prompts are not ignored).
     */
    public int getInstructionAdherenceScore() { return instructionAdherenceScore; }
    public void setInstructionAdherenceScore(int instructionAdherenceScore) { this.instructionAdherenceScore = instructionAdherenceScore; }

    /**
     * Returns true if the evaluation cleanly passes all thresholds.
     */
    public boolean isPass() { return isPass; }
    public void setPass(boolean pass) { isPass = pass; }

    /**
     * The raw unparsed text response from the LLM evaluator.
     */
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }

    /**
     * Specific textual fixes needed to correct factuality errors.
     */
    public List<String> getFactualityFixes() { return factualityFixes; }
    public void setFactualityFixes(List<String> factualityFixes) { this.factualityFixes = factualityFixes; }

    /**
     * Specific textual fixes needed to correct formatting or schema.
     */
    public List<String> getStructureFixes() { return structureFixes; }
    public void setStructureFixes(List<String> structureFixes) { this.structureFixes = structureFixes; }

    /**
     * Specific textual fixes to adopt the correct style.
     */
    public List<String> getStyleFixes() { return styleFixes; }
    public void setStyleFixes(List<String> styleFixes) { this.styleFixes = styleFixes; }

    /**
     * Lists instructions that were injected mid-way but inexplicably ignored by the worker.
     */
    public List<String> getMissingInstructions() { return missingInstructions; }
    public void setMissingInstructions(List<String> missingInstructions) { this.missingInstructions = missingInstructions; }

    /**
     * A human-readable reasoning block explaining why the overall score was assigned.
     */
    public String getGeneralRationale() { return generalRationale; }
    public void setGeneralRationale(String generalRationale) { this.generalRationale = generalRationale; }
}
