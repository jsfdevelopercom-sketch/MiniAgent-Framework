package com.miniagent.core;

import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable runtime state for one MiniAgent execution.
 *
 * This is the object the loop updates after every attempt.
 */
public class AgentRunState {

    private final String runId;
    private final String userId;
    private final String originalTask;
    private final Instant startedAt;

    private int attemptNumber;
    private StructuredResponse currentDraft;
    private StructuredResponse bestDraft;
    private EvaluationResult latestEvaluation;
    private int bestScore;
    private int noImprovementCount;
    private boolean completed;
    private boolean successful;
    private String stopReason;

    private final List<String> repairMemory = new ArrayList<>();

    public AgentRunState(String runId, String userId, String originalTask) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId cannot be blank.");
        }
        if (originalTask == null || originalTask.isBlank()) {
            throw new IllegalArgumentException("originalTask cannot be blank.");
        }

        this.runId = runId;
        this.userId = userId == null || userId.isBlank() ? "anonymous" : userId;
        this.originalTask = originalTask.trim();
        this.startedAt = Instant.now();

        this.attemptNumber = 0;
        this.bestScore = 0;
        this.noImprovementCount = 0;
        this.completed = false;
        this.successful = false;
        this.stopReason = "";
    }

    public void recordDraftAndEvaluation(StructuredResponse draft, EvaluationResult evaluation) {
        this.attemptNumber++;
        this.currentDraft = draft;
        this.latestEvaluation = evaluation;

        int score = combinedScore(evaluation);

        if (bestDraft == null || score > bestScore) {
            bestDraft = draft;
            bestScore = score;
            noImprovementCount = 0;
        } else {
            noImprovementCount++;
        }

        addEvaluationToRepairMemory(evaluation);
    }

    public int combinedScore(EvaluationResult evaluation) {
        if (evaluation == null) {
            return 0;
        }

        int factuality = safeScore(evaluation.getFactualityScore());
        int structure = safeScore(evaluation.getStructureScore());
        int style = safeScore(evaluation.getStyleScore());
        int instruction = safeScore(evaluation.getInstructionAdherenceScore());

        return (factuality + structure + style + instruction) / 4;
    }

    public String compactRepairMemoryText(int maxItems) {
        if (repairMemory.isEmpty()) {
            return "No repair memory yet.";
        }

        int safeMax = Math.max(1, maxItems);
        int fromIndex = Math.max(0, repairMemory.size() - safeMax);

        StringBuilder sb = new StringBuilder();
        List<String> recent = repairMemory.subList(fromIndex, repairMemory.size());
        for (int i = 0; i < recent.size(); i++) {
            sb.append(i + 1).append(". ").append(recent.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    public void markSuccess(String reason) {
        this.completed = true;
        this.successful = true;
        this.stopReason = reason == null ? "success" : reason;
    }

    public void markStopped(String reason) {
        this.completed = true;
        this.successful = false;
        this.stopReason = reason == null ? "stopped" : reason;
    }

    private void addEvaluationToRepairMemory(EvaluationResult evaluation) {
        if (evaluation == null) {
            return;
        }

        addAllPrefixed("Factuality", evaluation.getFactualityFixes());
        addAllPrefixed("Structure", evaluation.getStructureFixes());
        addAllPrefixed("Style", evaluation.getStyleFixes());
        addAllPrefixed("Instruction", evaluation.getMissingInstructions());

        while (repairMemory.size() > 12) {
            repairMemory.remove(0);
        }
    }

    private void addAllPrefixed(String prefix, List<String> items) {
        if (items == null) {
            return;
        }

        for (String item : items) {
            if (item != null && !item.isBlank()) {
                repairMemory.add(prefix + ": " + item.trim());
            }
        }
    }

    private int safeScore(int score) {
        if (score < 0) {
            return 0;
        }
        if (score > 100) {
            return 100;
        }
        return score;
    }

    public String getRunId() {
        return runId;
    }

    public String getUserId() {
        return userId;
    }

    public String getOriginalTask() {
        return originalTask;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public StructuredResponse getCurrentDraft() {
        return currentDraft;
    }

    public StructuredResponse getBestDraft() {
        return bestDraft;
    }

    public EvaluationResult getLatestEvaluation() {
        return latestEvaluation;
    }

    public int getBestScore() {
        return bestScore;
    }

    public int getNoImprovementCount() {
        return noImprovementCount;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getStopReason() {
        return stopReason;
    }

    public List<String> getRepairMemory() {
        return Collections.unmodifiableList(repairMemory);
    }
}