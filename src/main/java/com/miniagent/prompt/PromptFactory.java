package com.miniagent.prompt;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PromptFactory encapsulates the abstract framing and prompt engineering mechanics
 * for the generic Worker-Evaluator-Prompter loops.
 * <p>
 * This class abstracts away structural string concatenations, allowing the agent
 * layer to just feed Lists and Maps of context without worrying about spacing,
 * bullets, or JSON-string escaping.
 */
public class PromptFactory {

    /**
     * Builds the system instructions for a highly obedient generator worker.
     * 
     * @param domainContext Specific domain constraints (e.g., "You are a medical scribe").
     * @return The formatted System prompt.
     */
    public String buildWorkerSystemPrompt(String domainContext, String model) {
        String safeModelLabel = (model != null && !model.isBlank()) ? model : "an advanced AI";
        return joinLines(
                "You are an autonomous worker agent strictly adhering to the user's constraints.",
                "Your identity is Agent-Nero, powered primarily by " + safeModelLabel + " architecture.",
                "DIRECTIVE: If the user explicitly asks 'What model are you?' or 'What model is being used?', you MUST proudly disclose that you are Agent-Nero running via the explicit model: " + safeModelLabel + "!",
                domainContext != null ? domainContext : "",
                "Return your output as a valid JSON object with the exact keys instructed.",
                "Always populate 'thought_process' first to plan your response.",
                "Ensure your main output resides only in the 'summary' key.",
                "CRITICAL FORMATTING LOGIC:",
                "1. If the user engages in simple casual dialogue (e.g., 'Hello', 'Hi', 'Thanks'), your 'summary' MUST be simple conversational text WITHOUT markdown titles, bold headers, or explanations. Do not generate large headers like 'Greetings'.",
                "2. If the user asks for technical tasks, code, essays, or heavy data, format the 'summary' into polished Markdown. Place the markdown title ABOVE the content, and place explanations purely BELOW the content. Wrap code in proper markdown tags (e.g. ```html).",
                "VOICE SYNTHESIS DIRECTIVE: You MUST include a 'spoken_summary' key tailored perfectly for our Text-to-Speech logic.",
                "Our TTS MUST NOT read verbatim long essays or code. Your 'spoken_summary' must be a human-like, FIRST-PERSON abstract overview of what you did. Be highly intelligent and conversational.",
                "Example 1 (Casual): User says 'Hello!', spoken_summary: 'Hello there! How can I assist you today?'",
                "Example 2 (Heavy Task): User asks for complex Python script, spoken_summary: 'I have compiled the robust Python script resolving your request. You can read the code logic on the screen, and ask me if you don't understand anything!'"
        );
    }

    /**
     * Builds the user prompt for the generative worker.
     * 
     * @param taskInstructions The immediate task at hand.
     * @param dataset A map of facts or variables needed to complete the task.
     * @param liveInjections Instructions dynamically added mid-way that MUST be addressed.
     * @return The formatted User prompt.
     */
    public String buildWorkerUserPrompt(String taskInstructions, Map<String, Object> dataset, List<String> liveInjections) {
        return joinSections(
                "TASK", taskInstructions,
                "LIVE INJECTIONS (MUST FOLLOW)", bullets(liveInjections),
                "DATASET", mapToText(dataset)
        );
    }

    /**
     * Builds the system prompt for the multi-dimensional evaluator.
     * <p>
     * Instead of a one-dimensional pass/fail, the evaluator uses strict scoring logic
     * mapped across four dimensions.
     * 
     * @return The formatted Evaluator System prompt.
     */
    public String buildEvaluatorSystemPrompt() {
        return joinLines(
                "You are an elite, merciless Evaluator AI.",
                "Your job is to analyze the Worker's draft output against the provided instructions and Dataset.",
                "Output MUST be in the following plain-text block format:",
                "FACTUALITY_SCORE: (0-100)",
                "STRUCTURE_SCORE: (0-100)",
                "STYLE_SCORE: (0-100)",
                "INSTRUCTION_ADHERENCE_SCORE: (0-100)",
                "PASS: (true/false)",
                "FACTUALITY_FIXES:",
                "- (list)",
                "STRUCTURE_FIXES:",
                "- (list)",
                "STYLE_FIXES:",
                "- (list)",
                "MISSING_INSTRUCTIONS:",
                "- (list instructions ignored by worker)",
                "RATIONALE:",
                "(short paragraph reasoning)"
        );
    }

    /**
     * Builds the user prompt for the Evaluator, supplying the worker's draft and the 
     * original context to score against.
     * 
     * @param draft The unverified output generated by the worker.
     * @param rules The rigid rules the worker was supposed to follow.
     * @param dataset The original data.
     * @param liveInjections Mid-way instructions. If missing from the draft, score drops drastically.
     * @return The formatted Evaluator user prompt.
     */
    public String buildEvaluatorUserPrompt(String draft, List<String> rules, Map<String, Object> dataset, List<String> liveInjections, List<Map<String, String>> history) {
        return joinSections(
                "RECENT CONVERSATION HISTORY", historyToText(history),
                "WORKER DRAFT OUTPUT", draft,
                "RIGID RULES & STRUCTURE", bullets(rules),
                "LIVE INJECTIONS REQUIRED", bullets(liveInjections),
                "ORIGINAL DATASET", mapToText(dataset),
                "EVALUATION CRITERIA", joinLines(
                        "1. Identify pure hallucinations or facts unsupported by the DATASET (Factuality).",
                        "2. Ensure all RIGID RULES are followed verbatim (Structure).",
                        "3. Ensure the tone is appropriate for the context (Style).",
                        "4. Ensure conversational continuity is strictly maintained against the history.",
                        "5. CRITICAL: If ANY item in LIVE INJECTIONS is ignored, the INSTRUCTION_ADHERENCE_SCORE must be below 50, and you must explicitly list it in MISSING_INSTRUCTIONS."
                )
        );
    }

    /**
     * Builds the system prompt for the repair cycle. It mandates that critics' warnings
     * are functionally resolved, not just acknowledged.
     * 
     * @return The formatted Repair System prompt.
     */
    public String buildRepairSystemPrompt() {
        return joinLines(
                "You are fixing a broken draft that failed an elite Critic Evaluation.",
                "Your objective is to ingest the exact FIXES provided by the Critic and integrate them.",
                "Return a JSON object with 'thought_process', 'summary', and 'convo'.",
                "Explain how you resolved the Critic's issues step-by-step in 'thought_process'."
        );
    }

    /**
     * Builds the highly specific error-correcting user prompt for the repair cycle.
     * 
     * @param previousDraft The broken draft.
     * @param factualityFixes Factuality errors flagged.
     * @param structuralFixes Formatting errors flagged.
     * @param missingInstructions Dropped live-injections.
     * @param dataset The original facts.
     * @return The formatted Repair user prompt.
     */
    public String buildRepairUserPrompt(
            String previousDraft,
            List<String> factualityFixes,
            List<String> structuralFixes,
            List<String> missingInstructions,
            Map<String, Object> dataset
    ) {
        return joinSections(
                "BROKEN DRAFT", previousDraft,
                "FACTUALITY FIXES REQUIRED", bullets(factualityFixes),
                "STRUCTURAL FIXES REQUIRED", bullets(structuralFixes),
                "IGNORED INSTRUCTIONS REQUIRED", bullets(missingInstructions),
                "ORIGINAL DATASET", mapToText(dataset),
                "DIRECTIVE", "Do not ignore the fixes. Apply them literally."
        );
    }


    /**
     * System prompt for the Gemini extraction pass.
     */
    public String buildSynthesisExtractionSystemPrompt() {
        return joinLines(
                "You are an Elite Data Extractor.",
                "Your single goal is to read a noisy AI conversation draft and extract ONLY the functional answer to the original user query.",
                "Remove ALL AI apologies, meta-commentary, 'Here is the code' introductions, and internal critic reasoning.",
                "Return ONLY the pure requested content."
        );
    }

    /**
     * System prompt for the OpenAI formatting pass.
     */
    public String buildSynthesisFormattingSystemPrompt() {
        return joinLines(
                "You are an intelligent output synthesizer and formatter.",
                "Your job is to format the provided text gracefully into a JSON schema.",
                "CRITICAL FORMATTING OVERWRITE:",
                "1. If the input is a simple, casual conversational exchange (e.g. 'Hello', 'Hi', 'Thanks', 'How are you?'), DO NOT add any markdown titles, headers, or explanations. Just return the conversational text directly.",
                "2. If the input is a complex technical task, essay, or coding response, you MUST use polished Markdown. Expand arrays into markdown bullet points. Use appropriate headers. Place the title ABOVE the content, and any explanations BELOW the content.",
                "3. If the content contains code, you MUST wrap it in a proper Markdown code block (e.g., ```java) so it renders natively as a scrollable code element.",
                "Fill the 'summary' field with your chosen output.",
                "",
                "VOICE SYNTHESIS DIRECTIVE: We have a Text-to-Speech engine. You MUST generate a 'spoken_summary' string designed to be spoken aloud.",
                "The 'spoken_summary' must NEVER read the full long output verbatim! Instead, it must speak intelligently and conversationally about what you just did.",
                "Example 1: If summary has a massive Python script for Hello World:",
                "  spoken_summary: 'I have written a robust Python application resolving your query. You can read the rest of the code blocks right on your screen, and ask me if you don\\'t understand anything!'",
                "Example 2: If the summary is a heavily structured technical list:",
                "  spoken_summary: 'I published the comprehensive tutorial you requested. I highly recommend reading through the third section regarding integrations. Let me know if you need any clarifications!'",
                "Example 3: If the summary is a simple greeting like 'Hello there!':",
                "  spoken_summary: 'Hello there! How can I assist you today?'"
        );
    }

    /* --- HELPER METHODS --- */

    /**
     * Bullet formatter used across prompt sections. Converts flat lists into markdown dashes.
     */
    private String bullets(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "- none";
        }
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .map(item -> item.startsWith("-") ? item : "- " + item)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Renders a nested dataset map into highly readable structured prompt text.
     */
    private String mapToText(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        return map.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Translates structured chat arrays into elegant readable Markdown dialog flows for the Evaluator Observer.
     */
    private String historyToText(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "No previous context. This is the start of the conversation.";
        }
        return history.stream()
                .map(msg -> {
                    String role = "user".equalsIgnoreCase(msg.getOrDefault("role", "")) ? "User" : "Agent Nero";
                    return role + ": " + msg.getOrDefault("content", "");
                })
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Joins multiple titled sections with stable, standardized line-break spacing.
     */
    private String joinSections(String... pieces) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pieces.length; i += 2) {
            String title = pieces[i];
            String body = pieces[i + 1] == null ? "" : pieces[i + 1];
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(title).append("\n").append(body);
        }
        return sb.toString();
    }

    /**
     * Joins lines with unified newline separators.
     */
    private String joinLines(String... lines) {
        return String.join("\n", lines);
    }
}
