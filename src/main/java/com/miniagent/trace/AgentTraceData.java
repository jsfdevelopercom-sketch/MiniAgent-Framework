package com.miniagent.trace;

import com.miniagent.core.TaskClassifier;
import com.miniagent.core.AgentRunPlan;
import com.miniagent.core.AgentRunState;
import com.miniagent.core.ModelRoute;
import com.miniagent.core.StopPolicy;
import com.miniagent.model.EvaluationResult;
import com.miniagent.model.StructuredResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper methods for creating compact trace payloads.
 *
 * Keep large raw outputs out of trace unless truncated.
 */
public final class AgentTraceData {

    private AgentTraceData() {
    }

    public static Map<String, Object> classification(TaskClassifier.TaskClassification c) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (c == null) {
            data.put("classification", "null");
            return data;
        }

        data.put("taskType", c.taskType);
        data.put("difficulty", c.difficulty);
        data.put("needsDeepReasoning", c.needsDeepReasoning);
        data.put("needsTools", c.needsTools);
        data.put("needsWeb", c.needsWeb);
        data.put("needsFileAccess", c.needsFileAccess);
        data.put("needsUserClarification", c.needsUserClarification);
        data.put("recommendedPipeline", c.recommendedPipeline);
        data.put("maxAttempts", c.maxAttempts);
        data.put("successThreshold", c.successThreshold);
        data.put("maxAnswerTokens", c.maxAnswerTokens);
        data.put("reason", c.reason);
        data.put("providerUsed", c.providerUsed);

        return data;
    }

    public static Map<String, Object> modelRoute(ModelRoute r) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (r == null) {
            data.put("modelRoute", "null");
            return data;
        }

        data.put("generatorModel", r.getGeneratorModel());
        data.put("criticModel", r.getCriticModel());
        data.put("repairModel", r.getRepairModel());
        data.put("synthesizerModel", r.getSynthesizerModel());
        data.put("generatorTemperature", r.getGeneratorTemperature());
        data.put("criticTemperature", r.getCriticTemperature());
        data.put("repairTemperature", r.getRepairTemperature());
        data.put("synthesizerTemperature", r.getSynthesizerTemperature());

        return data;
    }

    public static Map<String, Object> runPlan(AgentRunPlan p) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (p == null) {
            data.put("runPlan", "null");
            return data;
        }

        data.put("maxAttempts", p.getMaxAttempts());
        data.put("successThreshold", p.getSuccessThreshold());
        data.put("maxAnswerTokens", p.getMaxAnswerTokens());
        data.put("maxWallClockSeconds", p.getMaxWallClockTime().toSeconds());
        data.put("allowFullHistory", p.isAllowFullHistory());
        data.put("enableRepairMemory", p.isEnableRepairMemory());
        data.put("enableBestAnswerTracking", p.isEnableBestAnswerTracking());
        data.put("enablePlanner", p.isEnablePlanner());
        data.put("enableTools", p.isEnableTools());

        return data;
    }

    public static Map<String, Object> runState(AgentRunState s) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (s == null) {
            data.put("runState", "null");
            return data;
        }

        data.put("runId", s.getRunId());
        data.put("userId", s.getUserId());
        data.put("attemptNumber", s.getAttemptNumber());
        data.put("bestScore", s.getBestScore());
        data.put("noImprovementCount", s.getNoImprovementCount());
        data.put("completed", s.isCompleted());
        data.put("successful", s.isSuccessful());
        data.put("stopReason", s.getStopReason());
        data.put("repairMemorySize", s.getRepairMemory().size());

        return data;
    }

    public static Map<String, Object> evaluation(EvaluationResult e) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (e == null) {
            data.put("evaluation", "null");
            return data;
        }

        data.put("pass", e.isPass());
        data.put("combinedScore", e.getCombinedScore());
        data.put("factualityScore", e.getFactualityScore());
        data.put("structureScore", e.getStructureScore());
        data.put("styleScore", e.getStyleScore());
        data.put("instructionAdherenceScore", e.getInstructionAdherenceScore());
        data.put("failureType", e.getFailureType());
        data.put("criticalIssues", e.hasCriticalIssues());
        data.put("issueCount", e.getIssues().size());
        data.put("issues", e.getIssues());
        data.put("repairInstructions", e.getRepairInstructions());
        data.put("rationale", e.getGeneralRationale());

        return data;
    }

    public static Map<String, Object> draft(StructuredResponse r, int maxChars) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (r == null) {
            data.put("draft", "null");
            return data;
        }

        String summary = r.getSummary() == null ? "" : r.getSummary();
        String raw = r.getRaw() == null ? "" : r.getRaw();

        data.put("summaryLength", summary.length());
        data.put("rawLength", raw.length());
        data.put("summaryPreview", truncate(summary, maxChars));
        data.put("rawPreview", truncate(raw, Math.min(maxChars, 1200)));

        return data;
    }

    public static Map<String, Object> stopDecision(StopPolicy.StopDecision decision) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (decision == null) {
            data.put("stopDecision", "null");
            return data;
        }

        data.put("action", decision.getAction());
        data.put("reason", decision.getReason());
        data.put("shouldContinue", decision.shouldContinue());

        return data;
    }

    public static Map<String, Object> datasetSummary(Map<String, Object> dataset) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (dataset == null || dataset.isEmpty()) {
            data.put("datasetSize", 0);
            return data;
        }

        data.put("datasetSize", dataset.size());
        data.put("datasetKeys", dataset.keySet().stream().limit(30).toList());

        return data;
    }

    public static Map<String, Object> historySummary(List<Map<String, String>> history) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (history == null || history.isEmpty()) {
            data.put("historySize", 0);
            return data;
        }

        data.put("historySize", history.size());

        int lastIndex = history.size() - 1;
        Map<String, String> last = history.get(lastIndex);

        if (last != null) {
            data.put("lastHistoryRole", last.getOrDefault("role", ""));
            String content = last.getOrDefault("content", "");
            data.put("lastHistoryContentPreview", truncate(content, 500));
        }

        return data;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);

        if (text.length() <= safeMax) {
            return text;
        }

        return text.substring(0, safeMax) + "...[TRUNCATED]";
    }
}