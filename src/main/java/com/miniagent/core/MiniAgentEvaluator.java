package com.miniagent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.api.ClaudeHttpClient;
import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.EvaluationResult;
import com.miniagent.prompt.PromptFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * MiniAgentEvaluator is the strict Critic node.
 *
 * Architecture role:
 * - It does not repair the answer.
 * - It does not produce the final answer.
 * - It evaluates the draft against the original task constraints, rigid rules,
 * dataset, and live injections.
 *
 * Important:
 * - This class uses structured JSON evaluation instead of fragile text-block
 * parsing.
 * - It also runs deterministic checks so the agent is not dependent only on LLM
 * judgement.
 */
public class MiniAgentEvaluator {

    private final OpenAiHttpClient openAiHttpClient;
    private final GeminiHttpClient geminiHttpClient;
    private final ClaudeHttpClient claudeHttpClient;
    private final PromptFactory promptFactory;
    private final ObjectMapper mapper;

    /**
     * Backward-compatible constructor.
     *
     * If you use Claude critic models, prefer the constructor that includes
     * ClaudeHttpClient.
     */
    public MiniAgentEvaluator(
            OpenAiHttpClient openAiHttpClient,
            GeminiHttpClient geminiHttpClient,
            PromptFactory promptFactory) {
        this(openAiHttpClient, geminiHttpClient, null, promptFactory, new ObjectMapper());
    }

    public MiniAgentEvaluator(
            OpenAiHttpClient openAiHttpClient,
            GeminiHttpClient geminiHttpClient,
            ClaudeHttpClient claudeHttpClient,
            PromptFactory promptFactory,
            ObjectMapper mapper) {
        this.openAiHttpClient = openAiHttpClient;
        this.geminiHttpClient = geminiHttpClient;
        this.claudeHttpClient = claudeHttpClient;
        this.promptFactory = promptFactory;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * Evaluates a draft.
     *
     * This signature is intentionally preserved so your existing Agent/deepThink
     * code still compiles.
     *
     * @param model          critic model ID
     * @param useGemini      legacy parameter; model prefix now has priority
     * @param draft          generated answer/draft to evaluate
     * @param rigidRules     strict rules that must be satisfied
     * @param dataset        ground-truth context supplied to worker
     * @param liveInjections additional user instructions
     * @param history        legacy conversational history; intentionally
     *                       summarized/ignored by default
     */
    public EvaluationResult evaluateDraft(
            String model,
            boolean useGemini,
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history) {
        String safeDraft = draft == null ? "" : draft.trim();
        List<String> safeRules = sanitizeList(rigidRules);
        Map<String, Object> safeDataset = dataset == null ? Collections.emptyMap() : dataset;
        List<String> safeLiveInjections = sanitizeList(liveInjections);

        EvaluationResult deterministicPrecheck = deterministicPrecheck(safeDraft, safeRules, safeDataset,
                safeLiveInjections);
        if (deterministicPrecheck != null && deterministicPrecheck.getCombinedScore() <= 20) {
            return deterministicPrecheck;
        }

        String safeModel = normalizeModel(model, useGemini);
        String systemPrompt = buildEvaluatorSystemPrompt();
        String userPrompt = buildEvaluatorUserPrompt(
                safeDraft,
                safeRules,
                safeDataset,
                safeLiveInjections,
                compactHistory(history));

        String rawJson;
        try {
            rawJson = executeStructuredCriticCall(safeModel, systemPrompt, userPrompt);
        } catch (Exception modelFailure) {
            EvaluationResult fallback = fallbackFromModelFailure(
                    safeDraft,
                    safeRules,
                    modelFailure);
            mergeDeterministicFindings(fallback, safeDraft, safeRules, safeDataset, safeLiveInjections);
            return fallback;
        }

        EvaluationResult result = parseStructuredCriticJson(rawJson);
        result.setRawOutput(rawJson);

        mergeDeterministicFindings(result, safeDraft, safeRules, safeDataset, safeLiveInjections);
        normalizePassFlag(result);

        return result;
    }

    private String executeStructuredCriticCall(String model, String systemPrompt, String userPrompt) {
        String lower = model == null ? "" : model.toLowerCase(Locale.ROOT);

        if (lower.startsWith("gemini")) {
            if (geminiHttpClient == null) {
                throw new IllegalStateException("Gemini critic requested but GeminiHttpClient is null.");
            }
            return geminiHttpClient.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
        }

        if (lower.startsWith("claude")) {
            if (claudeHttpClient == null) {
                throw new IllegalStateException("Claude critic requested but ClaudeHttpClient is null.");
            }
            return claudeHttpClient.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
        }

        if (openAiHttpClient == null) {
            throw new IllegalStateException("OpenAI critic requested but OpenAiHttpClient is null.");
        }

        return openAiHttpClient.executeStructuredCall(model, systemPrompt, userPrompt, 0.0, null);
    }

    private String buildEvaluatorSystemPrompt() {
        return """
                You are MiniAgentCritic, a strict evaluator inside an AI agent loop.

                Your job:
                - Judge whether the candidate draft satisfies the user's task and rigid rules.
                - Return ONLY valid JSON.
                - Do NOT rewrite the answer.
                - Do NOT be polite.
                - Do NOT reward style if the answer fails explicit requirements.
                - Do NOT pass outputs with placeholders, missing required parts, invented facts, or undefined code references.

                Scoring:
                - Use 0 to 100 for all dimension scores.
                - factuality_score: correctness and no unsupported claims.
                - structure_score: organization, completeness, required format.
                - style_score: clarity, tone, usability.
                - instruction_adherence_score: obedience to the original task and rigid rules.
                - pass=true only if the output can be accepted with minimal or no repair.
                - If any critical issue exists, pass=false.

                Required JSON schema:
                {
                  "pass": false,
                  "factuality_score": 0,
                  "structure_score": 0,
                  "style_score": 0,
                  "instruction_adherence_score": 0,
                  "failure_type": "NONE | FACTUAL_ERROR | STRUCTURE_ERROR | STYLE_ERROR | MISSING_REQUIREMENTS | INCOMPLETE_OUTPUT | UNSAFE_OUTPUT | EMPTY_OUTPUT | CODE_NOT_COMPILE_READY | UNKNOWN",
                  "issues": [
                    {
                      "severity": "critical | major | minor",
                      "issue": "specific issue",
                      "fix": "specific fix instruction"
                    }
                  ],
                  "factuality_fixes": [],
                  "structure_fixes": [],
                  "style_fixes": [],
                  "missing_instructions": [],
                  "repair_instructions": [],
                  "strengths": [],
                  "rationale": "Short rationale under 80 words."
                }

                JSON only.
                """;
    }

    private String buildEvaluatorUserPrompt(
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections,
            String compactHistory) {
        StringBuilder sb = new StringBuilder();

        sb.append("Evaluate the candidate draft below.\n\n");

        sb.append("RIGID_RULES:\n");
        if (rigidRules.isEmpty()) {
            sb.append("- The output must directly satisfy the user's task.\n");
            sb.append("- The output must be correct, complete, and usable.\n");
        } else {
            for (String rule : rigidRules) {
                sb.append("- ").append(rule).append("\n");
            }
        }

        sb.append("\nLIVE_INJECTIONS:\n");
        if (liveInjections.isEmpty()) {
            sb.append("- None\n");
        } else {
            for (String injection : liveInjections) {
                sb.append("- ").append(injection).append("\n");
            }
        }

        sb.append("\nGROUND_TRUTH_DATASET_SUMMARY:\n");
        sb.append(safeDatasetSummary(dataset)).append("\n");

        sb.append("\nCOMPACT_HISTORY:\n");
        sb.append(compactHistory == null || compactHistory.isBlank() ? "No history provided." : compactHistory)
                .append("\n");

        sb.append("\nCANDIDATE_DRAFT:\n");
        sb.append(draft == null || draft.isBlank() ? "[EMPTY_DRAFT]" : draft).append("\n");

        return sb.toString();
    }

    private EvaluationResult parseStructuredCriticJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return emptyOrMalformedResult("Critic returned blank JSON.");
        }

        String cleanJson = extractJsonObject(rawJson);

        try {
            CriticJson parsed = mapper.readValue(cleanJson, CriticJson.class);
            return toEvaluationResult(parsed, rawJson);
        } catch (Exception firstFailure) {
            EvaluationResult fallback = emptyOrMalformedResult(
                    "Critic JSON could not be parsed: " + firstFailure.getMessage());
            fallback.setRawOutput(rawJson);
            return fallback;
        }
    }

    private EvaluationResult toEvaluationResult(CriticJson parsed, String rawOutput) {
        EvaluationResult result = new EvaluationResult();

        if (parsed == null) {
            result.setPass(false);
            result.setFailureType("UNKNOWN");
            result.setGeneralRationale("Parsed critic response was null.");
            result.setRawOutput(rawOutput);
            return result;
        }

        result.setPass(parsed.pass);
        result.setFactualityScore(normalizeScore(parsed.factualityScore));
        result.setStructureScore(normalizeScore(parsed.structureScore));
        result.setStyleScore(normalizeScore(parsed.styleScore));
        result.setInstructionAdherenceScore(normalizeScore(parsed.instructionAdherenceScore));
        result.setFailureType(defaultString(parsed.failureType, "UNKNOWN"));
        result.setGeneralRationale(defaultString(parsed.rationale, ""));

        result.setFactualityFixes(parsed.factualityFixes);
        result.setStructureFixes(parsed.structureFixes);
        result.setStyleFixes(parsed.styleFixes);
        result.setMissingInstructions(parsed.missingInstructions);
        result.setRepairInstructions(parsed.repairInstructions);
        result.setStrengths(parsed.strengths);

        List<EvaluationResult.CriticIssue> issues = new ArrayList<>();
        if (parsed.issues != null) {
            for (CriticIssueJson issueJson : parsed.issues) {
                if (issueJson == null) {
                    continue;
                }
                EvaluationResult.CriticIssue issue = new EvaluationResult.CriticIssue(
                        issueJson.severity,
                        issueJson.issue,
                        issueJson.fix);
                if (issue.hasContent()) {
                    issues.add(issue);
                }
            }
        }
        result.setIssues(issues);

        result.setRawOutput(rawOutput);
        return result;
    }

    private EvaluationResult deterministicPrecheck(
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections) {
        if (draft == null || draft.isBlank()) {
            EvaluationResult result = new EvaluationResult();
            result.setPass(false);
            result.setFailureType("EMPTY_OUTPUT");
            result.setFactualityScore(0);
            result.setStructureScore(0);
            result.setStyleScore(0);
            result.setInstructionAdherenceScore(0);
            result.setGeneralRationale("Draft is empty.");
            result.setIssues(List.of(new EvaluationResult.CriticIssue(
                    "critical",
                    "The candidate draft is empty.",
                    "Regenerate the answer from scratch.")));
            result.setStructureFixes(List.of("Regenerate a non-empty output."));
            result.setRepairInstructions(List.of("Create a complete answer instead of returning an empty draft."));
            result.setRawOutput("DETERMINISTIC_EMPTY_OUTPUT");
            return result;
        }

        return null;
    }

    private void mergeDeterministicFindings(
            EvaluationResult result,
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections) {
        if (result == null) {
            return;
        }

        List<EvaluationResult.CriticIssue> issues = new ArrayList<>(result.getIssues());
        List<String> structureFixes = new ArrayList<>(result.getStructureFixes());
        List<String> missingInstructions = new ArrayList<>(result.getMissingInstructions());
        List<String> repairInstructions = new ArrayList<>(result.getRepairInstructions());

        String lowerDraft = draft == null ? "" : draft.toLowerCase(Locale.ROOT);

        if (draft == null || draft.isBlank()) {
            addIssue(issues, "critical", "Output is blank.", "Regenerate the answer from scratch.");
            structureFixes.add("Output must not be blank.");
            forceFail(result, "EMPTY_OUTPUT", 0, 0, 0, 0);
        }

        if (containsPlaceholder(lowerDraft)) {
            addIssue(
                    issues,
                    "critical",
                    "Output contains TODO/placeholder/incomplete implementation markers.",
                    "Replace placeholders with complete concrete content.");
            structureFixes.add("Remove TODOs, placeholders, and incomplete markers.");
            repairInstructions.add("Do not leave any function, section, or class as a placeholder.");
            penalize(result, 20, 25, 10, 25);
            result.setFailureType("INCOMPLETE_OUTPUT");
            result.setPass(false);
        }

        if (looksLikeCodeTask(rigidRules, draft) && containsCodeRisk(lowerDraft)) {
            addIssue(
                    issues,
                    "major",
                    "Code output appears to include compile-risk markers or undefined/incomplete fragments.",
                    "Review imports, helper methods, class names, and method bodies for completeness.");
            structureFixes.add("Ensure code is compile-ready and all referenced helpers exist.");
            repairInstructions.add("Preflight the code for ghost methods, missing imports, and incomplete blocks.");
            penalize(result, 10, 20, 5, 20);
            if ("NONE".equalsIgnoreCase(result.getFailureType())) {
                result.setFailureType("CODE_NOT_COMPILE_READY");
            }
            result.setPass(false);
        }

        for (String injection : liveInjections) {
            if (injection == null || injection.isBlank()) {
                continue;
            }

            String normalizedInjection = injection.trim();
            if (isLikelyMustIncludeInstruction(normalizedInjection) &&
                    !lowerDraft.contains(simplifyForContainment(normalizedInjection))) {
                addIssue(
                        issues,
                        "major",
                        "The draft may not reflect a live user instruction: " + normalizedInjection,
                        "Revise the answer to explicitly satisfy this live instruction.");
                missingInstructions.add("Possibly missed live instruction: " + normalizedInjection);
                penalize(result, 5, 5, 0, 15);
                result.setPass(false);
            }
        }

        if (result.hasCriticalIssues()) {
            result.setPass(false);
        }

        result.setIssues(issues);
        result.setStructureFixes(structureFixes);
        result.setMissingInstructions(missingInstructions);
        result.setRepairInstructions(repairInstructions);
    }

    private EvaluationResult fallbackFromModelFailure(
            String draft,
            List<String> rigidRules,
            Exception modelFailure) {
        EvaluationResult result = new EvaluationResult();
        result.setPass(false);
        result.setFailureType("UNKNOWN");

        boolean usableDraft = draft != null && !draft.isBlank();

        result.setFactualityScore(usableDraft ? 50 : 0);
        result.setStructureScore(usableDraft ? 50 : 0);
        result.setStyleScore(usableDraft ? 50 : 0);
        result.setInstructionAdherenceScore(usableDraft ? 50 : 0);

        String message = modelFailure == null ? "Unknown critic failure." : modelFailure.getMessage();
        result.setGeneralRationale("Critic model failed; deterministic fallback used. " + message);
        result.setRawOutput("CRITIC_MODEL_FAILURE: " + message);

        result.setIssues(List.of(new EvaluationResult.CriticIssue(
                "major",
                "The critic model call failed, so evaluation confidence is low.",
                "Retry with a fallback critic model or use deterministic checks.")));

        result.setRepairInstructions(List.of(
                "Review the answer conservatively because the critic model failed.",
                "If this happens often, route critic evaluation to a cheaper and more reliable structured-output model."));

        return result;
    }

    private EvaluationResult emptyOrMalformedResult(String reason) {
        EvaluationResult result = new EvaluationResult();
        result.setPass(false);
        result.setFailureType("UNKNOWN");
        result.setFactualityScore(20);
        result.setStructureScore(20);
        result.setStyleScore(20);
        result.setInstructionAdherenceScore(20);
        result.setGeneralRationale(reason);
        result.setIssues(List.of(new EvaluationResult.CriticIssue(
                "major",
                "Critic response was blank or malformed.",
                "Retry critic call with strict JSON mode or fallback provider.")));
        result.setRepairInstructions(List.of("Do not trust this evaluation fully; retry critic or use fallback."));
        result.setRawOutput(reason);
        return result;
    }

    private void normalizePassFlag(EvaluationResult result) {
        if (result == null) {
            return;
        }

        int combined = result.getCombinedScore();

        if (result.hasCriticalIssues()) {
            result.setPass(false);
            return;
        }

        if (combined < 75) {
            result.setPass(false);
            return;
        }

        if (result.getFactualityScore() < 70 ||
                result.getStructureScore() < 70 ||
                result.getInstructionAdherenceScore() < 70) {
            result.setPass(false);
            return;
        }

        if (combined >= 85 &&
                result.getFactualityScore() >= 80 &&
                result.getStructureScore() >= 80 &&
                result.getInstructionAdherenceScore() >= 80 &&
                result.getIssues().isEmpty()) {
            result.setPass(true);
        }
    }

    private String normalizeModel(String model, boolean useGemini) {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }

        if (useGemini) {
            return "gemini-3.1-flash-lite-preview";
        }

        return "gpt-4.1-mini";
    }

    private int normalizeScore(int score) {
        if (score <= 10) {
            return score * 10;
        }

        if (score < 0) {
            return 0;
        }

        if (score > 100) {
            return 100;
        }

        return score;
    }

    private void forceFail(
            EvaluationResult result,
            String failureType,
            int factuality,
            int structure,
            int style,
            int instruction) {
        result.setPass(false);
        result.setFailureType(failureType);
        result.setFactualityScore(factuality);
        result.setStructureScore(structure);
        result.setStyleScore(style);
        result.setInstructionAdherenceScore(instruction);
    }

    private void penalize(
            EvaluationResult result,
            int factualityPenalty,
            int structurePenalty,
            int stylePenalty,
            int instructionPenalty) {
        result.setFactualityScore(Math.max(0, result.getFactualityScore() - factualityPenalty));
        result.setStructureScore(Math.max(0, result.getStructureScore() - structurePenalty));
        result.setStyleScore(Math.max(0, result.getStyleScore() - stylePenalty));
        result.setInstructionAdherenceScore(Math.max(0, result.getInstructionAdherenceScore() - instructionPenalty));
    }

    private void addIssue(
            List<EvaluationResult.CriticIssue> issues,
            String severity,
            String issue,
            String fix) {
        if (issues == null) {
            return;
        }

        EvaluationResult.CriticIssue criticIssue = new EvaluationResult.CriticIssue(severity, issue, fix);
        if (!issues.contains(criticIssue)) {
            issues.add(criticIssue);
        }
    }

    private boolean containsPlaceholder(String lowerDraft) {
        if (lowerDraft == null || lowerDraft.isBlank()) {
            return false;
        }

        return lowerDraft.contains("todo") ||
                lowerDraft.contains("tbd") ||
                lowerDraft.contains("placeholder") ||
                lowerDraft.contains("your code here") ||
                lowerDraft.contains("implement later") ||
                lowerDraft.contains("implementation omitted") ||
                lowerDraft.contains("remaining code") ||
                lowerDraft.contains("not shown") ||
                lowerDraft.contains("for brevity") ||
                lowerDraft.contains("lorem ipsum");
    }

    private boolean containsCodeRisk(String lowerDraft) {
        if (lowerDraft == null || lowerDraft.isBlank()) {
            return false;
        }

        return lowerDraft.contains("undefined") ||
                lowerDraft.contains("missing import") ||
                lowerDraft.contains("pseudo-code") ||
                lowerDraft.contains("pseudocode") ||
                lowerDraft.contains("stub") ||
                lowerDraft.contains("not compile") ||
                lowerDraft.contains("compile error") ||
                lowerDraft.contains("replace this") ||
                lowerDraft.contains("...") && looksLikeJavaOrCode(lowerDraft);
    }

    private boolean looksLikeJavaOrCode(String text) {
        if (text == null) {
            return false;
        }

        return text.contains("public class") ||
                text.contains("private final") ||
                text.contains("public static void") ||
                text.contains("import java.") ||
                text.contains("@service") ||
                text.contains("@restcontroller") ||
                text.contains("function ") ||
                text.contains("const ") ||
                text.contains("class ");
    }

    private boolean looksLikeCodeTask(List<String> rigidRules, String draft) {
        StringBuilder sb = new StringBuilder();

        if (rigidRules != null) {
            for (String rule : rigidRules) {
                if (rule != null) {
                    sb.append(rule).append(" ");
                }
            }
        }

        if (draft != null) {
            sb.append(draft);
        }

        String lower = sb.toString().toLowerCase(Locale.ROOT);

        return lower.contains("code") ||
                lower.contains("compile") ||
                lower.contains("java") ||
                lower.contains("spring") ||
                lower.contains("class") ||
                lower.contains("method") ||
                lower.contains("function") ||
                lower.contains("import");
    }

    private boolean isLikelyMustIncludeInstruction(String instruction) {
        if (instruction == null) {
            return false;
        }

        String lower = instruction.toLowerCase(Locale.ROOT);
        return lower.contains("must include") ||
                lower.contains("include ") ||
                lower.contains("do not") ||
                lower.contains("don't") ||
                lower.contains("never") ||
                lower.contains("always") ||
                lower.contains("strictly") ||
                lower.contains("exactly");
    }

    private String simplifyForContainment(String instruction) {
        if (instruction == null) {
            return "";
        }

        String lower = instruction.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (lower.length() <= 80) {
            return lower;
        }

        return lower.substring(0, 80).trim();
    }

    private String compactHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "No history provided.";
        }

        StringBuilder sb = new StringBuilder();
        int maxMessages = Math.min(history.size(), 4);
        int start = Math.max(0, history.size() - maxMessages);

        for (int i = start; i < history.size(); i++) {
            Map<String, String> message = history.get(i);
            if (message == null) {
                continue;
            }

            String role = message.getOrDefault("role", "unknown");
            String content = message.getOrDefault("content", "");
            if (content.length() > 500) {
                content = content.substring(0, 500);
            }

            sb.append(role).append(": ").append(content.replaceAll("\\s+", " ").trim()).append("\n");
        }

        String compact = sb.toString().trim();
        return compact.isBlank() ? "No usable history provided." : compact;
    }

    private String safeDatasetSummary(Map<String, Object> dataset) {
        if (dataset == null || dataset.isEmpty()) {
            return "No dataset provided.";
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (Map.Entry<String, Object> entry : dataset.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            if (count >= 25) {
                sb.append("- ...additional dataset fields omitted for evaluator compactness.\n");
                break;
            }

            String value = String.valueOf(entry.getValue());
            if (value.length() > 500) {
                value = value.substring(0, 500) + "...";
            }

            sb.append("- ").append(entry.getKey()).append(": ").append(value).append("\n");
            count++;
        }

        return sb.toString().trim();
    }

    private List<String> sanitizeList(List<String> input) {
        if (input == null) {
            return Collections.emptyList();
        }

        List<String> cleaned = new ArrayList<>();
        for (String item : input) {
            if (item == null) {
                continue;
            }

            String normalized = item.trim().replaceAll("\\s+", " ");
            if (!normalized.isBlank()) {
                cleaned.add(normalized);
            }
        }

        return cleaned;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }

        String clean = raw.trim();

        if (clean.startsWith("```json")) {
            clean = clean.substring(7).trim();
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3).trim();
            }
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3).trim();
            if (clean.endsWith("```")) {
                clean = clean.substring(0, clean.length() - 3).trim();
            }
        }

        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');

        if (start >= 0 && end >= start) {
            return clean.substring(start, end + 1);
        }

        return clean;
    }

    private String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CriticJson {

        public boolean pass;

        @com.fasterxml.jackson.annotation.JsonProperty("factuality_score")
        public int factualityScore;

        @com.fasterxml.jackson.annotation.JsonProperty("structure_score")
        public int structureScore;

        @com.fasterxml.jackson.annotation.JsonProperty("style_score")
        public int styleScore;

        @com.fasterxml.jackson.annotation.JsonProperty("instruction_adherence_score")
        public int instructionAdherenceScore;

        @com.fasterxml.jackson.annotation.JsonProperty("failure_type")
        public String failureType;

        public List<CriticIssueJson> issues = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("factuality_fixes")
        public List<String> factualityFixes = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("structure_fixes")
        public List<String> structureFixes = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("style_fixes")
        public List<String> styleFixes = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("missing_instructions")
        public List<String> missingInstructions = new ArrayList<>();

        @com.fasterxml.jackson.annotation.JsonProperty("repair_instructions")
        public List<String> repairInstructions = new ArrayList<>();

        public List<String> strengths = new ArrayList<>();
        public String rationale = "";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CriticIssueJson {
        public String severity = "minor";
        public String issue = "";
        public String fix = "";
    }
}