package com.miniagent.trace;

/**
 * Safe default logger when tracing is disabled.
 */
public class NoOpAgentTraceLogger implements AgentTraceLogger {

    @Override
    public void log(AgentTraceEvent event) {
        // Intentionally empty.
    }
}