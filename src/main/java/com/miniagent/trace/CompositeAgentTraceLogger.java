package com.miniagent.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Fan-out logger.
 *
 * Allows:
 * - JSONL durable trace
 * - in-memory UI trace
 * - future database trace
 */
public class CompositeAgentTraceLogger implements AgentTraceLogger {

    private final List<AgentTraceLogger> delegates = new ArrayList<>();

    public CompositeAgentTraceLogger(List<AgentTraceLogger> delegates) {
        if (delegates != null) {
            for (AgentTraceLogger logger : delegates) {
                if (logger != null) {
                    this.delegates.add(logger);
                }
            }
        }
    }

    @Override
    public void log(AgentTraceEvent event) {
        for (AgentTraceLogger delegate : delegates) {
            try {
                delegate.log(event);
            } catch (Exception e) {
                System.err.println("[TRACE LOGGER WARNING] Delegate failed: " + e.getMessage());
            }
        }
    }

    public boolean isEmpty() {
        return delegates.isEmpty();
    }
}