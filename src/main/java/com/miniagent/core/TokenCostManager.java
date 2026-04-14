package com.miniagent.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TokenCostManager handles recording token usage across AI integrations and
 * calculates estimated cost against quotas (e.g., 10 RS).
 * <p>
 * This follows the BYOT (Bring Your Own Token) architecture limits.
 * We map a user's unique ID over to an aggregated structure tracking their consumed tokens.
 */
public class TokenCostManager {

    private final ConcurrentHashMap<String, AtomicInteger> userTokens = new ConcurrentHashMap<>();

    private static final double INR_PER_1000_TOKENS = 1.0; 

    public void addUsage(String userId, int pTokens, int cTokens) {
        String key = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        userTokens.computeIfAbsent(key, k -> new AtomicInteger(0)).addAndGet(pTokens + cTokens);
    }

    public int getTotalTokens(String userId) {
        String key = (userId == null || userId.isBlank()) ? "anonymous" : userId;
        return userTokens.getOrDefault(key, new AtomicInteger(0)).get();
    }

    public double getCostInInr(String userId) {
        return (getTotalTokens(userId) / 1000.0) * INR_PER_1000_TOKENS;
    }

    public boolean isQuotaExceeded(String userId) {
        return getCostInInr(userId) >= 10.0;
    }

    // Deprecated global hooks for legacy fallback
    public int getTotalTokens() { return getTotalTokens("anonymous"); }
    public double getCostInInr() { return getCostInInr("anonymous"); }
    public boolean isQuotaExceeded() { return isQuotaExceeded("anonymous"); }
}
