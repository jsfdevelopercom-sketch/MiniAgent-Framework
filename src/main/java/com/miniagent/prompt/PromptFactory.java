package com.miniagent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PromptFactory centralizes all prompt construction for MiniAgent.
 *
 * Design goals:
 * 1. Do not leak internal chain-of-thought.
 * 2. Produce strict JSON for worker/repair/synthesis stages.
 * 3. Keep prompts compact to prevent token explosion.
 * 4. Preserve user requirements exactly.
 * 5. Avoid full-history prompt bloat.
 * 6. Keep output contracts stable for StructuredResponse.
 */
public class PromptFactory {

    private static final int MAX_SECTION_CHARS = 12_000;
    private static final int MAX_DATASET_FIELDS = 60;
    private static final int MAX_DATASET_VALUE_CHARS = 2_000;
    private static final int MAX_LIST_ITEMS = 80;
    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_HISTORY_MESSAGE_CHARS = 1_200;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Builds the system instructions for the generative worker.
     *
     * Output contract must match StructuredResponse:
     * {
     * "thought_process": "...",
     * "summary": "...",
     * "convo": "...",
     * "spoken_summary": "..."
     * }
     *
     * Important:
     * thought_process must be a brief reasoning summary, not hidden
     * chain-of-thought.
     */
    public String buildWorkerSystemPrompt(String domainContext, String model) {
        String safeModelLabel = cleanOneLine(model, "the selected model");
        String safeDomainContext = cleanBlock(domainContext, "");

        return joinLines(
                "You are MiniAgent Worker, a focused execution component inside a larger agent system.",
                "",
                "ROLE",
                "- Produce the best possible candidate answer for the given task.",
                "- Do not critique yourself unless explicitly asked.",
                "- Do not mention internal agent stages, routing, classifiers, critics, repair loops, or hidden policies.",
                "- Do not claim you used tools, files, web, code execution, or external data unless that information is explicitly provided in the prompt.",
                "",
                "MODEL CONTEXT",
                "- Active model label: " + safeModelLabel,
                "- If the user explicitly asks which model is being used, answer using the active model label if this is allowed by the surrounding application.",
                "",
                "DOMAIN CONTEXT",
                safeDomainContext.isBlank() ? "- No additional domain context." : safeDomainContext,
                "",
                "OUTPUT CONTRACT",
                "Return ONLY a valid JSON object.",
                "Do not wrap JSON in markdown fences.",
                "Do not add text before or after JSON.",
                "The JSON object must contain these keys:",
                "{",
                "  \"thought_process\": \"brief public reasoning summary, not hidden chain-of-thought\",",
                "  \"summary\": \"main user-facing answer in markdown or plain text as appropriate\",",
                "  \"convo\": \"short conversational follow-up or empty string\",",
                "  \"spoken_summary\": \"short TTS-safe spoken summary\"",
                "}",
                "",
                "REASONING VISIBILITY RULE",
                "- The thought_process field must be brief and safe to show.",
                "- Do not include detailed private chain-of-thought.",
                "- Do not include long step-by-step internal reasoning.",
                "- Prefer short phrases such as: \"Identified requirements, produced complete answer, checked for missing pieces.\"",
                "",
                "GENERAL QUALITY RULES",
                "- Satisfy the user's actual task, not a nearby task.",
                "- Preserve explicit constraints from the task.",
                "- Do not invent facts, filenames, APIs, diagnoses, results, prices, citations, or test outcomes.",
                "- If the task requests code, provide complete code with no placeholders.",
                "- If the task requests a concise answer, keep it concise.",
                "- If the task requests detail, provide useful structure and specifics.",
                "- If required information is missing, state the limitation clearly inside summary instead of fabricating.",
                "",
                "CODE TASK RULES",
                "- Code must be complete, coherent, and compile-oriented.",
                "- Do not leave TODO, FIXME, placeholder methods, pseudo-code, or \"implementation omitted\" sections.",
                "- Do not reference helper methods/classes that are not included or clearly part of the user's existing project.",
                "- Keep class/package names consistent with the requested context.",
                "- Avoid unnecessary wrapper classes.",
                "- Prefer straightforward senior-engineer style.",
                "",
                "MEDICAL / HIGH-STAKES RULES",
                "- Do not add clinical facts not provided.",
                "- Clearly separate provided facts from interpretation.",
                "- Avoid unsafe certainty when data is incomplete.",
                "",
                "FORMATTING RULES",
                "- For casual chat, summary should be plain conversational text without markdown headings.",
                "- For technical/code/architecture tasks, summary may use clean markdown headings and code fences.",
                "- Code blocks must use the correct language tag, for example ```java.",
                "- spoken_summary must never read long code or long documents verbatim.",
                "- spoken_summary should be one or two natural sentences.");
    }

    /**
     * Builds the user prompt for the generative worker.
     */
    public String buildWorkerUserPrompt(
            String taskInstructions,
            Map<String, Object> dataset,
            List<String> liveInjections) {
        String safeTask = cleanBlock(taskInstructions, "");
        String safeLiveInjections = bullets(liveInjections);
        String safeDataset = mapToText(dataset);

        return joinSections(
                "TASK",
                safeTask.isBlank() ? "[NO TASK PROVIDED]" : safeTask,

                "LIVE INSTRUCTIONS THAT OVERRIDE EARLIER DRAFTS",
                safeLiveInjections,

                "GROUND TRUTH DATASET",
                safeDataset,

                "EXECUTION INSTRUCTIONS",
                joinLines(
                        "- Use the TASK as the authority.",
                        "- Use the DATASET only as supporting ground truth.",
                        "- Obey every live instruction.",
                        "- Return only JSON matching the system output contract.",
                        "- Do not include internal agent metadata in summary."));
    }

    /**
     * Kept for compatibility.
     *
     * New MiniAgentEvaluator may build its own JSON critic prompt internally,
     * but this method now also returns a strict JSON evaluator prompt.
     */
    public String buildEvaluatorSystemPrompt() {
        return joinLines(
                "You are MiniAgent Critic, a strict evaluator inside an AI agent loop.",
                "",
                "ROLE",
                "- Evaluate the worker draft against the task, rules, dataset, and live instructions.",
                "- Do not rewrite the answer.",
                "- Do not solve the original task.",
                "- Return only valid JSON.",
                "- Do not wrap JSON in markdown fences.",
                "",
                "SCORING",
                "- Scores are 0 to 100.",
                "- factuality_score: correctness and absence of unsupported claims.",
                "- structure_score: completeness, organization, required format, code completeness.",
                "- style_score: clarity, tone, readability.",
                "- instruction_adherence_score: obedience to task, rigid rules, and live instructions.",
                "- pass=true only if the output can be accepted with minimal or no repair.",
                "- If there is any critical issue, pass=false.",
                "",
                "STRICT FAILURE RULES",
                "- Empty output: pass=false.",
                "- Placeholder/TODO/incomplete code: pass=false.",
                "- Undefined helper methods in code: pass=false.",
                "- Ignored explicit user requirement: pass=false.",
                "- Unsupported factual/clinical/legal/financial claim: pass=false.",
                "",
                "REQUIRED JSON SCHEMA",
                "{",
                "  \"pass\": false,",
                "  \"factuality_score\": 0,",
                "  \"structure_score\": 0,",
                "  \"style_score\": 0,",
                "  \"instruction_adherence_score\": 0,",
                "  \"failure_type\": \"NONE | FACTUAL_ERROR | STRUCTURE_ERROR | STYLE_ERROR | MISSING_REQUIREMENTS | INCOMPLETE_OUTPUT | UNSAFE_OUTPUT | EMPTY_OUTPUT | CODE_NOT_COMPILE_READY | UNKNOWN\",",
                "  \"issues\": [",
                "    {",
                "      \"severity\": \"critical | major | minor\",",
                "      \"issue\": \"specific issue\",",
                "      \"fix\": \"specific repair instruction\"",
                "    }",
                "  ],",
                "  \"factuality_fixes\": [],",
                "  \"structure_fixes\": [],",
                "  \"style_fixes\": [],",
                "  \"missing_instructions\": [],",
                "  \"repair_instructions\": [],",
                "  \"strengths\": [],",
                "  \"rationale\": \"short rationale under 80 words\"",
                "}"+
                "- Do not penalize long output if the user requested complete/elaborate code."+
"- For code generation, completeness is more important than brevity."+
"- A long single-file implementation is acceptable if the user requested it.");
    }

    /**
     * Builds the user prompt for the evaluator.
     */
    public String buildEvaluatorUserPrompt(
            String draft,
            List<String> rules,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history) {
        return joinSections(
                "COMPACT RECENT CONVERSATION HISTORY",
                historyToText(history),

                "WORKER DRAFT TO EVALUATE",
                cleanBlock(draft, "[EMPTY DRAFT]"),

                "RIGID RULES",
                bullets(rules),

                "LIVE INSTRUCTIONS THAT MUST BE FOLLOWED",
                bullets(liveInjections),

                "GROUND TRUTH DATASET",
                mapToText(dataset),

                "EVALUATION TASK",
                joinLines(
                        "- Judge only the draft above.",
                        "- Do not rewrite the draft.",
                        "- Identify exact reasons for failure.",
                        "- If the draft is acceptable, pass=true and keep issues empty or minor only.",
                        "- If code is present, check for placeholders, ghost references, undefined helpers, missing imports, and incomplete methods.",
                        "- Return only JSON matching the required schema."));
    }

    /**
     * Builds the repair system prompt.
     */
    public String buildRepairSystemPrompt() {
        return joinLines(
                "You are MiniAgent Repair Worker.",
                "",
                "ROLE",
                "- Repair a previous draft that failed critic evaluation.",
                "- Apply critic fixes literally and concretely.",
                "- Preserve correct parts of the previous draft.",
                "- Do not create a totally different answer unless the repair memory says the prior approach failed repeatedly.",
                "- Do not mention critic, evaluator, repair loop, or internal agent process in the final summary.",
                "",
                "OUTPUT CONTRACT",
                "Return ONLY a valid JSON object.",
                "Do not wrap JSON in markdown fences.",
                "The JSON object must contain:",
                "{",
                "  \"thought_process\": \"brief public repair summary, not hidden chain-of-thought\",",
                "  \"summary\": \"fully repaired user-facing answer\",",
                "  \"convo\": \"short conversational follow-up or empty string\",",
                "  \"spoken_summary\": \"short TTS-safe spoken summary\"",
                "}",
                "",
                "REPAIR RULES",
                "- Do not simply acknowledge the fixes; actually apply them.",
                "- Do not remove correct content unless it conflicts with the fixes.",
                "- Do not add unsupported facts.",
                "- Do not introduce new placeholders.",
                "- If code is involved, return complete compile-oriented code.",
                "- No TODO, no pseudo-code, no omitted sections.",
                "- Make the repaired output strictly better than the broken draft.",
                "",
                "REASONING VISIBILITY RULE",
                "- thought_process must be a short public repair summary.",
                "- Do not reveal detailed hidden chain-of-thought.");
    }

    /**
     * Builds the repair user prompt.
     */
    public String buildRepairUserPrompt(
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset) {
        return joinSections(
                "BROKEN DRAFT",
                cleanBlock(previousDraft, "[EMPTY BROKEN DRAFT]"),

                "FACTUALITY FIXES REQUIRED",
                bullets(factualityFixes),

                "STRUCTURAL / COMPLETENESS FIXES REQUIRED",
                bullets(structuralFixes),

                "MISSING OR IGNORED INSTRUCTIONS TO RESTORE",
                bullets(missingInstructions),

                "GROUND TRUTH DATASET",
                mapToText(dataset),

                "REPAIR DIRECTIVE",
                joinLines(
                        "- Produce the repaired final answer now.",
                        "- Apply every fix that is relevant.",
                        "- Do not mention that this is a repair.",
                        "- Do not include internal analysis.",
                        "- Return only JSON matching the repair system contract."));
    }

    /**
     * Optional extraction prompt for a pre-synthesis pass.
     */
    public String buildSynthesisExtractionSystemPrompt() {
        return joinLines(
                "You are MiniAgent Extraction Cleaner.",
                "",
                "ROLE",
                "- Extract only the functional answer from a noisy draft.",
                "- Remove internal agent commentary, critic discussion, failed-attempt discussion, and apologies.",
                "- Preserve code exactly if code is the requested artifact.",
                "- Preserve medically or technically important wording.",
                "",
                "OUTPUT",
                "- Return only the cleaned answer text.",
                "- Do not add new facts.");
    }

    /**
     * Builds the final synthesizer prompt.
     */
    public String buildSynthesisFormattingSystemPrompt() {
        return joinLines(
                "You are MiniAgent Output Synthesizer.",
                "",
                "ROLE",
                "- Format the final answer into the application response schema.",
                "- Do not change the factual meaning.",
                "- Do not invent new content.",
                "- Do not remove required code, tables, lists, medical details, or user-requested structure.",
                "- Do not mention internal agent stages.",
                "",
                "OUTPUT CONTRACT",
                "Return ONLY valid JSON.",
                "Do not wrap JSON in markdown fences.",
                "The JSON object must contain:",
                "{",
                "  \"thought_process\": \"brief public finalization summary, not hidden chain-of-thought\",",
                "  \"summary\": \"final formatted user-facing answer\",",
                "  \"convo\": \"short conversational follow-up or empty string\",",
                "  \"spoken_summary\": \"short TTS-safe spoken summary\"",
                "}",
                "",
                "FORMATTING RULES",
                "- If the input is casual chat, summary must be simple conversational text.",
                "- If the input is technical/code/architecture, use polished markdown.",
                "- If the input contains code, preserve it in fenced code blocks with correct language tags.",
                "- Do not shorten code unless explicitly instructed.",
                "- Do not add generic titles to simple greetings.",
                "- Do not add fake citations, fake tests, fake links, or fake runtime claims.",
                "",
                "TTS RULES",
                "- spoken_summary must be short and natural.",
                "- spoken_summary must not read long code or long documents verbatim.",
                "- For code: say that the code is ready on screen and briefly describe what it does.",
                "- For simple chat: spoken_summary can match the casual answer.");
    }

    /**
     * Useful if later you want a dedicated planning stage.
     */
    public String buildPlannerSystemPrompt() {
        return joinLines(
                "You are MiniAgent Planner.",
                "",
                "ROLE",
                "- Create a compact execution plan for a non-tool thinking task.",
                "- Do not solve the task fully.",
                "- Do not produce final answer content.",
                "- Return only valid JSON.",
                "",
                "OUTPUT SCHEMA",
                "{",
                "  \"goal\": \"one sentence goal\",",
                "  \"difficulty\": \"EASY | MEDIUM | HARD\",",
                "  \"steps\": [",
                "    {\"id\": \"S1\", \"title\": \"step title\", \"success_criteria\": \"how this step is judged\"}",
                "  ],",
                "  \"risks\": [],",
                "  \"success_criteria\": []",
                "}");
    }

    public String buildPlannerUserPrompt(String task, Map<String, Object> dataset) {
        return joinSections(
                "TASK",
                cleanBlock(task, "[NO TASK PROVIDED]"),

                "DATASET",
                mapToText(dataset),

                "PLANNING DIRECTIVE",
                joinLines(
                        "- Create the smallest useful plan.",
                        "- Avoid over-planning simple tasks.",
                        "- Return only JSON."));
    }

    /**
     * Useful if later you add explicit replan stage.
     */
    public String buildReplanSystemPrompt() {
        return joinLines(
                "You are MiniAgent Replanner.",
                "",
                "ROLE",
                "- The previous attempt failed.",
                "- Create a fresh strategy that avoids repeated failures.",
                "- Do not include hidden chain-of-thought.",
                "- Return only valid JSON matching StructuredResponse.",
                "",
                "OUTPUT CONTRACT",
                "{",
                "  \"thought_process\": \"brief public replan summary\",",
                "  \"summary\": \"fresh improved answer\",",
                "  \"convo\": \"short follow-up or empty string\",",
                "  \"spoken_summary\": \"short TTS-safe spoken summary\"",
                "}");
    }

    public String buildReplanUserPrompt(
            String originalTask,
            String bestDraft,
            String repairMemory,
            Map<String, Object> dataset) {
        return joinSections(
                "ORIGINAL TASK",
                cleanBlock(originalTask, "[NO TASK PROVIDED]"),

                "BEST PRIOR DRAFT IF ANY",
                cleanBlock(bestDraft, "[NO PRIOR DRAFT]"),

                "FAILURE / REPAIR MEMORY",
                cleanBlock(repairMemory, "No repair memory."),

                "GROUND TRUTH DATASET",
                mapToText(dataset),

                "REPLAN DIRECTIVE",
                joinLines(
                        "- Start from the original task.",
                        "- Use the best prior draft only if it helps.",
                        "- Avoid all repeated failures.",
                        "- Produce the final improved answer.",
                        "- Return only JSON."));
    }

    /**
     * Strict schema-repair prompt.
     */
    public String buildJsonRepairSystemPrompt() {
        return joinLines(
                "You are a JSON repair utility.",
                "",
                "ROLE",
                "- Convert malformed model output into valid JSON.",
                "- Do not add new facts.",
                "- Preserve the user's content.",
                "- Return only valid JSON.",
                "",
                "TARGET SCHEMA",
                "{",
                "  \"thought_process\": \"brief public summary\",",
                "  \"summary\": \"main answer\",",
                "  \"convo\": \"short follow-up or empty string\",",
                "  \"spoken_summary\": \"short spoken summary\"",
                "}");
    }

    public String buildJsonRepairUserPrompt(String malformedOutput) {
        return joinSections(
                "MALFORMED OUTPUT",
                cleanBlock(malformedOutput, ""),

                "DIRECTIVE",
                joinLines(
                        "- Repair to valid JSON.",
                        "- If a field is missing, infer minimally from the available content.",
                        "- Do not invent new technical facts.",
                        "- Return only JSON."));
    }

    /* ----------------------------- Helpers ----------------------------- */

    private String bullets(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- none";
        }

        List<String> cleaned = new ArrayList<>();

        for (String item : items) {
            if (item == null) {
                continue;
            }

            String normalized = cleanOneLine(item, "");
            if (normalized.isBlank()) {
                continue;
            }

            if (normalized.equalsIgnoreCase("none") ||
                    normalized.equalsIgnoreCase("null") ||
                    normalized.equalsIgnoreCase("n/a")) {
                continue;
            }

            cleaned.add(normalized);
            if (cleaned.size() >= MAX_LIST_ITEMS) {
                break;
            }
        }

        if (cleaned.isEmpty()) {
            return "- none";
        }

        StringBuilder sb = new StringBuilder();
        for (String item : cleaned) {
            sb.append(item.startsWith("-") ? item : "- " + item).append("\n");
        }

        if (items.size() > cleaned.size()) {
            sb.append("- ...additional items omitted for prompt compactness\n");
        }

        return trimToMax(sb.toString().trim(), MAX_SECTION_CHARS);
    }

    private String mapToText(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        int emitted = 0;

        List<Map.Entry<String, Object>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));

        for (Map.Entry<String, Object> entry : entries) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }

            if (emitted >= MAX_DATASET_FIELDS) {
                sb.append("...additional dataset fields omitted for compactness\n");
                break;
            }

            String key = cleanOneLine(String.valueOf(entry.getKey()), "unknown_key");
            String value = objectToPromptText(entry.getValue());

            sb.append(key).append(" = ").append(value).append("\n");
            emitted++;
        }

        String result = sb.toString().trim();
        return result.isBlank() ? "{}" : trimToMax(result, MAX_SECTION_CHARS);
    }

    private String objectToPromptText(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof CharSequence text) {
            return trimToMax(cleanBlock(text.toString(), ""), MAX_DATASET_VALUE_CHARS);
        }

        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            int count = 0;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= 30) {
                    normalized.put("_truncated", true);
                    break;
                }

                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                count++;
            }

            return toCompactJson(normalized);
        }

        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>();
            int count = 0;

            for (Object item : collection) {
                if (count >= 40) {
                    normalized.add("...[TRUNCATED]");
                    break;
                }

                normalized.add(item);
                count++;
            }

            return toCompactJson(normalized);
        }

        return trimToMax(String.valueOf(value), MAX_DATASET_VALUE_CHARS);
    }

    private String historyToText(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "No previous context.";
        }

        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        StringBuilder sb = new StringBuilder();

        for (int i = start; i < history.size(); i++) {
            Map<String, String> msg = history.get(i);
            if (msg == null) {
                continue;
            }

            String role = msg.getOrDefault("role", "");
            String safeRole = "user".equalsIgnoreCase(role) ? "User" : "Assistant";
            String content = msg.getOrDefault("content", "");
            content = trimToMax(cleanBlock(content, ""), MAX_HISTORY_MESSAGE_CHARS);

            if (content.isBlank()) {
                continue;
            }

            sb.append(safeRole).append(": ").append(content).append("\n\n");
        }

        String result = sb.toString().trim();
        return result.isBlank() ? "No usable previous context." : result;
    }

    private String joinSections(String... pieces) {
        StringBuilder sb = new StringBuilder();

        if (pieces == null) {
            return "";
        }

        for (int i = 0; i + 1 < pieces.length; i += 2) {
            String title = cleanOneLine(pieces[i], "SECTION");
            String body = pieces[i + 1] == null ? "" : pieces[i + 1];

            if (sb.length() > 0) {
                sb.append("\n\n");
            }

            sb.append("## ").append(title).append("\n");
            sb.append(trimToMax(body, MAX_SECTION_CHARS));
        }

        return sb.toString().trim();
    }

    private String joinLines(String... lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            if (line == null) {
                continue;
            }

            sb.append(line).append("\n");
        }

        return sb.toString().trim();
    }

    private String cleanOneLine(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }

        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanBlock(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }

        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        while (normalized.contains("\n\n\n")) {
            normalized = normalized.replace("\n\n\n", "\n\n");
        }

        return trimToMax(normalized.trim(), MAX_SECTION_CHARS);
    }

    private String trimToMax(String value, int maxChars) {
        if (value == null) {
            return "";
        }

        int safeMax = Math.max(100, maxChars);

        if (value.length() <= safeMax) {
            return value;
        }

        return value.substring(0, safeMax) + "\n...[TRUNCATED_FOR_PROMPT_COMPACTNESS]";
    }

    private String toCompactJson(Object value) {
        try {
            return trimToMax(mapper.writeValueAsString(value), MAX_DATASET_VALUE_CHARS);
        } catch (Exception e) {
            return trimToMax(String.valueOf(Objects.toString(value, "")), MAX_DATASET_VALUE_CHARS);
        }
    }
}
