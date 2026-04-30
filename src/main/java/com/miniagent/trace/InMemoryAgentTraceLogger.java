package com.miniagent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps recent trace events in memory.
 *
 * Useful for:
 * - live UI inspection
 * - debug endpoint
 * - tests
 *
 * Not durable. Use JsonlAgentTraceLogger for disk persistence.
 */
public class InMemoryAgentTraceLogger implements AgentTraceLogger {

    private final int maxEventsPerRun;
    private final ConcurrentHashMap<String, List<AgentTraceEvent>> eventsByRunId = new ConcurrentHashMap<>();

    public InMemoryAgentTraceLogger() {
        this(200);
    }

    public InMemoryAgentTraceLogger(int maxEventsPerRun) {
        this.maxEventsPerRun = Math.max(20, maxEventsPerRun);
    }

    @Override
    public void log(AgentTraceEvent event) {
        if (event == null) {
            return;
        }

        String runId = event.getRunId() == null || event.getRunId().isBlank()
                ? "unknown-run"
                : event.getRunId();

        List<AgentTraceEvent> events = eventsByRunId.computeIfAbsent(
                runId,
                ignored -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (events) {
            events.add(event);

            while (events.size() > maxEventsPerRun) {
                events.remove(0);
            }
        }
    }

    public List<AgentTraceEvent> getEvents(String runId) {
        if (runId == null || runId.isBlank()) {
            return Collections.emptyList();
        }

        List<AgentTraceEvent> events = eventsByRunId.get(runId);
        if (events == null) {
            return Collections.emptyList();
        }

        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public void clearRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        eventsByRunId.remove(runId);
    }

    public void clearAll() {
        eventsByRunId.clear();
    }
}