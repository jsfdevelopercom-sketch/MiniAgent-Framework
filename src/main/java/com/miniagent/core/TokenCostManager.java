package com.miniagent.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TokenCostManager tracks token consumption across different AI models and calculates 
 * the estimated usage cost (in INR) to prevent runaway expenses and enforce quotas.
 * 
 * Deep Insight:
 * This class serves as the financial safeguard for the Agent-Nero ecosystem.
 * By tracking costs per run, user, and model, we ensure that infinite loops or 
 * large context window expansions do not drain API budgets silently.
 * It uses precise provider-specific pricing to give accurate expenditure reports.
 */
public class TokenCostManager {

    private final ConcurrentHashMap<String, UsageTracker> userTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UsageTracker> runTrackers = new ConcurrentHashMap<>();

    private static final double USD_TO_INR = 83.5;

    private boolean ignoreCost = true;

    public void setIgnoreCost(boolean ignoreCost) {
        this.ignoreCost = ignoreCost;
    }

    public static class UsageSnapshot {
        private final int inputTokens;
        private final int outputTokens;
        private final double totalCostInr;
        private final int callCount;
        private final int estimatedCallCount;

        public UsageSnapshot(int inputTokens, int outputTokens, double totalCostInr, int callCount, int estimatedCallCount) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalCostInr = totalCostInr;
            this.callCount = callCount;
            this.estimatedCallCount = estimatedCallCount;
        }

        public int getTotalTokens() { return inputTokens + outputTokens; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public double getTotalCostInr() { return totalCostInr; }
        public int getCallCount() { return callCount; }
        public int getEstimatedCallCount() { return estimatedCallCount; }
    }

    private static class UsageTracker {
        final AtomicInteger inputTokens = new AtomicInteger(0);
        final AtomicInteger outputTokens = new AtomicInteger(0);
        final AtomicInteger callCount = new AtomicInteger(0);
        final AtomicInteger estimatedCallCount = new AtomicInteger(0);
        double totalCostInr = 0.0;

        synchronized void addUsage(int input, int output, double cost, boolean isEstimated) {
            inputTokens.addAndGet(input);
            outputTokens.addAndGet(output);
            totalCostInr += cost;
            callCount.incrementAndGet();
            if (isEstimated) {
                estimatedCallCount.incrementAndGet();
            }
        }

        UsageSnapshot snapshot() {
            return new UsageSnapshot(
                inputTokens.get(), 
                outputTokens.get(), 
                totalCostInr, 
                callCount.get(), 
                estimatedCallCount.get()
            );
        }
    }

    /**
     * Estimates the number of tokens in a given text based on standard subword tokenization heuristics.
     * Deep Insight: A rough conversion of ~4 characters per token is industry standard for English.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    /**
     * Calculates the cost of an API call in INR based on the model used.
     * Deep Insight: Pricing varies heavily by provider; failing to map this correctly results in flawed quotas.
     */
    private double calculateCostInr(String model, int inputTokens, int outputTokens) {
        if (model == null) return 0.0;
        String lowerModel = model.toLowerCase();
        double inputCostUsdPerM = 0;
        double outputCostUsdPerM = 0;

        if (lowerModel.contains(ModelConstants.GPT_5_NANO) || lowerModel.contains(ModelConstants.GPT_4O_MINI)) {
            inputCostUsdPerM = 0.150; outputCostUsdPerM = 0.600;
        } else if (lowerModel.contains(ModelConstants.GPT_5_4) || lowerModel.contains(ModelConstants.GPT_4O)) {
            inputCostUsdPerM = 5.00; outputCostUsdPerM = 15.00;
        } else if (lowerModel.contains(ModelConstants.GPT_4_TURBO)) {
            inputCostUsdPerM = 10.00; outputCostUsdPerM = 30.00;

        } else if (lowerModel.contains(ModelConstants.GEMINI_1_5_FLASH)) {
            inputCostUsdPerM = 0.075; outputCostUsdPerM = 0.30;
        } else if (lowerModel.contains(ModelConstants.GEMINI_1_5_PRO)) {
            inputCostUsdPerM = 3.50; outputCostUsdPerM = 10.50;
        } else if (lowerModel.contains(ModelConstants.CLAUDE_3_5_SONNET)) {
            inputCostUsdPerM = 3.00; outputCostUsdPerM = 15.00;
        } else if (lowerModel.contains(ModelConstants.CLAUDE_3_5_HAIKU)) {
            inputCostUsdPerM = 0.25; outputCostUsdPerM = 1.25;
        } else if (lowerModel.contains(ModelConstants.CLAUDE_3_OPUS)) {
            inputCostUsdPerM = 15.00; outputCostUsdPerM = 75.00;
        } else {
            // Default generic fallback
            inputCostUsdPerM = 1.00; outputCostUsdPerM = 2.00;
        }

        double costUsd = ((inputTokens / 1_000_000.0) * inputCostUsdPerM) + 
                         ((outputTokens / 1_000_000.0) * outputCostUsdPerM);
        return costUsd * USD_TO_INR;
    }

    /**
     * Records token usage for a specific user and run, factoring in provider-specific pricing.
     */
    public void addModelUsage(String userId, String runId, String stage, String model, int inputTokens, int outputTokens, boolean isEstimated) {
        String safeUserId = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        String safeRunId = (runId == null || runId.isBlank()) ? "unknown-run" : runId;
        
        double costInr = calculateCostInr(model, inputTokens, outputTokens);

        userTrackers.computeIfAbsent(safeUserId, k -> new UsageTracker())
                    .addUsage(inputTokens, outputTokens, costInr, isEstimated);
                    
        runTrackers.computeIfAbsent(safeRunId, k -> new UsageTracker())
                   .addUsage(inputTokens, outputTokens, costInr, isEstimated);
    }

    /**
     * Retrieves the comprehensive snapshot of tokens consumed during a specific run.
     */
    public UsageSnapshot getRunSnapshot(String runId) {
        if (runId == null || !runTrackers.containsKey(runId)) {
            return new UsageSnapshot(0, 0, 0.0, 0, 0);
        }
        return runTrackers.get(runId).snapshot();
    }

    public int getTotalTokens(String userId) {
        String key = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        UsageTracker tracker = userTrackers.get(key);
        return tracker == null ? 0 : tracker.snapshot().getTotalTokens();
    }

    public double getCostInInr(String userId) {
        String key = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        UsageTracker tracker = userTrackers.get(key);
        return tracker == null ? 0.0 : tracker.snapshot().getTotalCostInr();
    }

    public boolean isQuotaExceeded(String userId) {
        double cost = getCostInInr(userId);
        if (cost >= 10.0) {
            if (ignoreCost) {
                System.err.println("[WARNING] Token cost quota exceeded (Cost: " + cost + " INR). ignoreCost is true, so continuing execution.");
                return false;
            }
            return true;
        }
        return false;
    }

    // Deprecated global hooks for legacy fallback
    public int getTotalTokens() { return getTotalTokens("anonymous"); }
    public double getCostInInr() { return getCostInInr("anonymous"); }
    public boolean isQuotaExceeded() { return isQuotaExceeded("anonymous"); }
}
