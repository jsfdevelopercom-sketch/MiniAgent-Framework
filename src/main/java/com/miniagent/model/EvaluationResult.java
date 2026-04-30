package com.miniagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * EvaluationResult is the stable Java-side critic result consumed by
 * AgentRunState,
 * StopPolicy, and Repair logic.
 *
 * Score convention:
 * - All dimension scores are normalized to 0..100.
 * - pass=true means the evaluator believes the answer is acceptable.
 * - The agent should still combine pass=true with threshold checks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvaluationResult {

    private int factualityScore;
    private int structureScore;
    private int styleScore;
    private int instructionAdherenceScore;

    private boolean pass;

    private List<String> factualityFixes = new ArrayList<>();
    private List<String> structureFixes = new ArrayList<>();
    private List<String> styleFixes = new ArrayList<>();
    private List<String> missingInstructions = new ArrayList<>();

    private List<CriticIssue> issues = new ArrayList<>();
    private List<String> strengths = new ArrayList<>();
    private List<String> repairInstructions = new ArrayList<>();

    private String failureType = "NONE";
    private String generalRationale = "";
    private String rawOutput = "";

    public EvaluationResult() {
    }

    public int getFactualityScore() {
        return factualityScore;
    }

    public void setFactualityScore(int factualityScore) {
        this.factualityScore = clampScore(factualityScore);
    }

    public int getStructureScore() {
        return structureScore;
    }

    public void setStructureScore(int structureScore) {
        this.structureScore = clampScore(structureScore);
    }

    public int getStyleScore() {
        return styleScore;
    }

    public void setStyleScore(int styleScore) {
        this.styleScore = clampScore(styleScore);
    }

    public int getInstructionAdherenceScore() {
        return instructionAdherenceScore;
    }

    public void setInstructionAdherenceScore(int instructionAdherenceScore) {
        this.instructionAdherenceScore = clampScore(instructionAdherenceScore);
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public List<String> getFactualityFixes() {
        return safeUnmodifiable(factualityFixes);
    }

    public void setFactualityFixes(List<String> factualityFixes) {
        this.factualityFixes = sanitizeStringList(factualityFixes);
    }

    public List<String> getStructureFixes() {
        return safeUnmodifiable(structureFixes);
    }

    public void setStructureFixes(List<String> structureFixes) {
        this.structureFixes = sanitizeStringList(structureFixes);
    }

    public List<String> getStyleFixes() {
        return safeUnmodifiable(styleFixes);
    }

    public void setStyleFixes(List<String> styleFixes) {
        this.styleFixes = sanitizeStringList(styleFixes);
    }

    public List<String> getMissingInstructions() {
        return safeUnmodifiable(missingInstructions);
    }

    public void setMissingInstructions(List<String> missingInstructions) {
        this.missingInstructions = sanitizeStringList(missingInstructions);
    }

    public List<CriticIssue> getIssues() {
        if (issues == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(issues);
    }

    public void setIssues(List<CriticIssue> issues) {
        if (issues == null) {
            this.issues = new ArrayList<>();
            return;
        }

        List<CriticIssue> cleaned = new ArrayList<>();
        for (CriticIssue issue : issues) {
            if (issue != null && issue.hasContent()) {
                cleaned.add(issue);
            }
        }
        this.issues = cleaned;
    }

    public List<String> getStrengths() {
        return safeUnmodifiable(strengths);
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = sanitizeStringList(strengths);
    }

    public List<String> getRepairInstructions() {
        return safeUnmodifiable(repairInstructions);
    }

    public void setRepairInstructions(List<String> repairInstructions) {
        this.repairInstructions = sanitizeStringList(repairInstructions);
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            this.failureType = "UNKNOWN";
        } else {
            this.failureType = failureType.trim();
        }
    }

    public String getGeneralRationale() {
        return generalRationale;
    }

    public void setGeneralRationale(String generalRationale) {
        this.generalRationale = generalRationale == null ? "" : generalRationale.trim();
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput == null ? "" : rawOutput;
    }

    public int getCombinedScore() {
        return (factualityScore + structureScore + styleScore + instructionAdherenceScore) / 4;
    }

    public boolean hasCriticalIssues() {
        if (issues == null || issues.isEmpty()) {
            return false;
        }

        for (CriticIssue issue : issues) {
            if (issue != null && issue.isCritical()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasCriticalFailure() {
        return hasCriticalIssues();
    }

    public List<String> allFixesAsFlatList() {
        List<String> all = new ArrayList<>();
        all.addAll(getFactualityFixes());
        all.addAll(getStructureFixes());
        all.addAll(getStyleFixes());
        all.addAll(getMissingInstructions());
        all.addAll(getRepairInstructions());

        for (CriticIssue issue : getIssues()) {
            if (issue != null && issue.hasContent()) {
                StringBuilder line = new StringBuilder();
                line.append(issue.getSeverity()).append(": ");
                if (!issue.getIssue().isBlank()) {
                    line.append(issue.getIssue());
                }
                if (!issue.getFix().isBlank()) {
                    line.append(" Fix: ").append(issue.getFix());
                }
                all.add(line.toString());
            }
        }

        return sanitizeStringList(all);
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        if (score > 100) {
            return 100;
        }
        return score;
    }

    private static List<String> sanitizeStringList(List<String> input) {
        List<String> cleaned = new ArrayList<>();

        if (input == null) {
            return cleaned;
        }

        for (String item : input) {
            if (item == null) {
                continue;
            }

            String normalized = item.trim().replaceAll("\\s+", " ");
            if (normalized.isBlank()) {
                continue;
            }

            if ("none".equalsIgnoreCase(normalized) ||
                    "n/a".equalsIgnoreCase(normalized) ||
                    "null".equalsIgnoreCase(normalized)) {
                continue;
            }

            if (normalized.length() > 500) {
                normalized = normalized.substring(0, 500);
            }

            cleaned.add(normalized);
        }

        return cleaned;
    }

    private static List<String> safeUnmodifiable(List<String> input) {
        if (input == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(input);
    }

    @Override
    public String toString() {
        return "EvaluationResult{" +
                "factualityScore=" + factualityScore +
                ", structureScore=" + structureScore +
                ", styleScore=" + styleScore +
                ", instructionAdherenceScore=" + instructionAdherenceScore +
                ", pass=" + pass +
                ", failureType='" + failureType + '\'' +
                ", combinedScore=" + getCombinedScore() +
                ", issues=" + issues +
                ", generalRationale='" + generalRationale + '\'' +
                '}';
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CriticIssue {

        private String severity = "minor";
        private String issue = "";
        private String fix = "";

        public CriticIssue() {
        }

        public CriticIssue(String severity, String issue, String fix) {
            setSeverity(severity);
            setIssue(issue);
            setFix(fix);
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            if (severity == null || severity.isBlank()) {
                this.severity = "minor";
                return;
            }

            String normalized = severity.trim().toLowerCase();
            if (!normalized.equals("critical") &&
                    !normalized.equals("major") &&
                    !normalized.equals("minor")) {
                normalized = "minor";
            }

            this.severity = normalized;
        }

        public String getIssue() {
            return issue;
        }

        public void setIssue(String issue) {
            this.issue = issue == null ? "" : issue.trim();
        }

        public String getFix() {
            return fix;
        }

        public void setFix(String fix) {
            this.fix = fix == null ? "" : fix.trim();
        }

        public boolean isCritical() {
            return "critical".equalsIgnoreCase(severity);
        }

        public boolean hasContent() {
            return (issue != null && !issue.isBlank()) ||
                    (fix != null && !fix.isBlank());
        }

        @Override
        public String toString() {
            return "CriticIssue{" +
                    "severity='" + severity + '\'' +
                    ", issue='" + issue + '\'' +
                    ", fix='" + fix + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CriticIssue that)) {
                return false;
            }
            return Objects.equals(severity, that.severity) &&
                    Objects.equals(issue, that.issue) &&
                    Objects.equals(fix, that.fix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(severity, issue, fix);
        }
    }
}