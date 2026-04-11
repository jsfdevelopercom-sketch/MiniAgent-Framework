package com.miniagent.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TokenCostManager handles recording token usage across AI integrations and
 * calculates estimated cost against quotas (e.g., 1 RS).
 * <p>
 * This follows the BYOT (Bring Your Own Token) architecture limits.
 */
public class TokenCostManager {

    private final AtomicInteger totalTokens = new AtomicInteger(0);
    private final AtomicInteger promptTokens = new AtomicInteger(0);
    private final AtomicInteger completionTokens = new AtomicInteger(0);

    // Rough approximation based on current top-tier GPT and Gemini API blend
    // Example: $0.01 per 1k prompt, $0.03 per 1k context. 
    // Roughly 1 INR = $0.012 -> ~1000 tokens blended average.
    private static final double INR_PER_1000_TOKENS = 1.0; 

    public void addUsage(int pTokens, int cTokens) {
        promptTokens.addAndGet(pTokens);
        completionTokens.addAndGet(cTokens);
        totalTokens.addAndGet(pTokens + cTokens);
    }

    public int getTotalTokens() {
        return totalTokens.get();
    }

    public double getCostInInr() {
        return (getTotalTokens() / 1000.0) * INR_PER_1000_TOKENS;
    }

    /**
     * @return true if the free quota threshold has been exceeded.
     */
    public boolean isQuotaExceeded() {
        return getCostInInr() >= 1000.0; // Exceeds 1000 Rs
    }
}
