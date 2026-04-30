package com.miniagent.core;

import com.miniagent.model.EvaluationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compresses evaluator feedback and model/thought failures into a short,
 * deduplicated repair memory.
 *
 * This prevents the "append whole history forever" failure mode.
 */
public class RepairMemoryCompressor {

    private final int maxRecords;
    private final int maxRenderedCharacters;

    private final LinkedHashMap<String, MemoryRecord> recordsBySignature = new LinkedHashMap<>();

    public RepairMemoryCompressor() {
        this(24, 3000);
    }

    public RepairMemoryCompressor(int maxRecords, int maxRenderedCharacters) {
        this.maxRecords = Math.max(8, maxRecords);
        this.maxRenderedCharacters = Math.max(1000, maxRenderedCharacters);
    }

    public synchronized void ingestEvaluation(EvaluationResult evaluation, int attemptNumber) {
        if (evaluation == null) {
            return;
        }

        ingestStrings(
                "critic-factuality",
                ThoughtFailureType.HALLUCINATION_RISK,
                evaluation.getFactualityFixes(),
                attemptNumber,
                7);

        ingestStrings(
                "critic-structure",
                ThoughtFailureType.STRUCTURAL_FAILURE,
                evaluation.getStructureFixes(),
                attemptNumber,
                6);

        ingestStrings(
                "critic-style",
                ThoughtFailureType.STRUCTURAL_FAILURE,
                evaluation.getStyleFixes(),
                attemptNumber,
                4);

        ingestStrings(
                "critic-instruction",
                ThoughtFailureType.INSTRUCTION_NON_ADHERENCE,
                evaluation.getMissingInstructions(),
                attemptNumber,
                8);

        ingestStrings(
                "critic-repair",
                ThoughtFailureType.CRITIC_REJECTED_OUTPUT,
                evaluation.getRepairInstructions(),
                attemptNumber,
                7);

        if (evaluation.getIssues() != null) {
            for (EvaluationResult.CriticIssue issue : evaluation.getIssues()) {
                if (issue == null || !issue.hasContent()) {
                    continue;
                }

                int severity = switch (issue.getSeverity().toLowerCase()) {
                    case "critical" -> 9;
                    case "major" -> 7;
                    default -> 4;
                };

                String msg = issue.getIssue();
                String fix = issue.getFix();

                ingestFailure(ThoughtFailureRecord.of(
                        ThoughtFailureType.CRITIC_REJECTED_OUTPUT,
                        "critic",
                        "critic",
                        attemptNumber,
                        msg,
                        fix,
                        severity,
                        true));
            }
        }

        prune();
    }

    public synchronized void ingestFailure(ThoughtFailureRecord failure) {
        if (failure == null) {
            return;
        }

        String signature = failure.getSignature();
        MemoryRecord existing = recordsBySignature.get(signature);

        if (existing == null) {
            recordsBySignature.put(signature, new MemoryRecord(failure));
        } else {
            existing.bump(failure);
        }

        prune();
    }

    public synchronized void ingestFailures(List<ThoughtFailureRecord> failures) {
        if (failures == null) {
            return;
        }

        for (ThoughtFailureRecord failure : failures) {
            ingestFailure(failure);
        }
    }

    public synchronized String renderForRepair() {
        return renderForRepair(10, maxRenderedCharacters);
    }

    public synchronized String renderForRepair(int maxItems, int maxCharacters) {
        List<MemoryRecord> ranked = rankedRecords();

        if (ranked.isEmpty()) {
            return "No prior repair memory.";
        }

        int safeMaxItems = Math.max(1, maxItems);
        int safeMaxChars = Math.max(500, maxCharacters);

        StringBuilder sb = new StringBuilder();
        sb.append("Repair memory. Do not repeat these mistakes:\n");

        int emitted = 0;
        for (MemoryRecord record : ranked) {
            if (emitted >= safeMaxItems) {
                break;
            }

            String line = "- " + record.toRepairLine() + "\n";

            if (sb.length() + line.length() > safeMaxChars) {
                sb.append("- Additional repair memory truncated to control prompt size.\n");
                break;
            }

            sb.append(line);
            emitted++;
        }

        return sb.toString().trim();
    }

    public synchronized List<ThoughtFailureRecord> asFailureRecords() {
        List<ThoughtFailureRecord> result = new ArrayList<>();
        for (MemoryRecord record : rankedRecords()) {
            result.add(record.failure);
        }
        return result;
    }

    public synchronized boolean hasRepeatedFailure(int minCount) {
        int threshold = Math.max(2, minCount);

        for (MemoryRecord record : recordsBySignature.values()) {
            if (record.count >= threshold) {
                return true;
            }
        }

        return false;
    }

    public synchronized ThoughtFailureRecord mostRepeatedFailure() {
        return recordsBySignature.values()
                .stream()
                .max(Comparator.comparingInt((MemoryRecord r) -> r.count)
                        .thenComparingInt(r -> r.failure.getSeverity()))
                .map(r -> r.failure)
                .orElse(null);
    }

    public synchronized void clear() {
        recordsBySignature.clear();
    }

    private void ingestStrings(
            String stage,
            ThoughtFailureType type,
            List<String> items,
            int attemptNumber,
            int severity) {
        if (items == null) {
            return;
        }

        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }

            ingestFailure(ThoughtFailureRecord.of(
                    type,
                    stage,
                    "critic",
                    attemptNumber,
                    item.trim(),
                    "Address this specifically in the next repair.",
                    severity,
                    true));
        }
    }

    private void prune() {
        if (recordsBySignature.size() <= maxRecords) {
            return;
        }

        List<Map.Entry<String, MemoryRecord>> ranked = new ArrayList<>(recordsBySignature.entrySet());
        ranked.sort(Comparator
                .comparingInt((Map.Entry<String, MemoryRecord> e) -> e.getValue().score())
                .reversed());

        recordsBySignature.clear();

        int count = 0;
        for (Map.Entry<String, MemoryRecord> entry : ranked) {
            if (count >= maxRecords) {
                break;
            }
            recordsBySignature.put(entry.getKey(), entry.getValue());
            count++;
        }
    }

    private List<MemoryRecord> rankedRecords() {
        List<MemoryRecord> ranked = new ArrayList<>(recordsBySignature.values());

        ranked.sort(Comparator
                .comparingInt(MemoryRecord::score)
                .reversed());

        return ranked;
    }

    private static class MemoryRecord {

        private ThoughtFailureRecord failure;
        private int count;

        private MemoryRecord(ThoughtFailureRecord failure) {
            this.failure = failure;
            this.count = 1;
        }

        private void bump(ThoughtFailureRecord newFailure) {
            this.count++;
            if (newFailure.getSeverity() > this.failure.getSeverity()) {
                this.failure = newFailure;
            }
        }

        private int score() {
            return failure.getSeverity() * 10 + count * 3 + failure.getAttemptNumber();
        }

        private String toRepairLine() {
            String repeatText = count > 1 ? " Repeated " + count + " times." : "";
            return failure.toRepairLine() + repeatText;
        }
    }
}