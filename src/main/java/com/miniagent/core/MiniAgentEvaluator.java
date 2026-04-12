package com.miniagent.core;

import com.miniagent.api.GeminiHttpClient;
import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.EvaluationResult;
import com.miniagent.prompt.PromptFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniAgentEvaluator represents the "Critic" node in the Agent architecture.
 * <p>
 * This class introduces a multi-dimensional algorithm that assesses Factuality,
 * Structure, Style, and Instruction Adherence separately, producing highly
 * targeted arrays of specific fixes.
 */
public class MiniAgentEvaluator {

    private final OpenAiHttpClient openAiHttpClient;
    private final GeminiHttpClient geminiHttpClient;
    private final PromptFactory promptFactory;

    public MiniAgentEvaluator(
            OpenAiHttpClient openAiHttpClient,
            GeminiHttpClient geminiHttpClient,
            PromptFactory promptFactory
    ) {
        this.openAiHttpClient = openAiHttpClient;
        this.geminiHttpClient = geminiHttpClient;
        this.promptFactory = promptFactory;
    }

    /**
     * Executes the multi-dimensional critique against the worker's output.
     * 
     * @param model the evaluator model
     * @param useGemini if true, routing occurs over Gemini rather than OpenAI (often preferred for fact-checking)
     * @param draft the worker generated output payload
     * @param rigidRules strict constraints that must be met
     * @param dataset original variables for fact checking
     * @param liveInjections instructions the user injected dynamically
     * @return Annotated EvaluationResult containing specific dimensions
     */
    public EvaluationResult evaluateDraft(
            String model,
            boolean useGemini,
            String draft,
            List<String> rigidRules,
            Map<String, Object> dataset,
            List<String> liveInjections,
            List<Map<String, String>> history
    ) {
        String sysPrompt = promptFactory.buildEvaluatorSystemPrompt();
        String userPrompt = promptFactory.buildEvaluatorUserPrompt(draft, rigidRules, dataset, liveInjections, history);

        String rawTextOutput;
        if (useGemini) {
            rawTextOutput = geminiHttpClient.executeTextCall(model, sysPrompt, userPrompt);
        } else {
            rawTextOutput = openAiHttpClient.executeTextCall(model, sysPrompt, userPrompt);
        }

        return parseMultiDimensionalResult(rawTextOutput);
    }

    /**
     * Parses the strict block-text formatting required by the Evaluator's prompt into Java objects.
     */
    private EvaluationResult parseMultiDimensionalResult(String rawTextOutput) {
        EvaluationResult result = new EvaluationResult();
        result.setRawOutput(rawTextOutput);

        result.setFactualityScore(parseNumericField(rawTextOutput, "FACTUALITY_SCORE:"));
        result.setStructureScore(parseNumericField(rawTextOutput, "STRUCTURE_SCORE:"));
        result.setStyleScore(parseNumericField(rawTextOutput, "STYLE_SCORE:"));
        result.setInstructionAdherenceScore(parseNumericField(rawTextOutput, "INSTRUCTION_ADHERENCE_SCORE:"));

        result.setPass(parseBooleanField(rawTextOutput, "PASS:"));

        result.setFactualityFixes(parseListSection(rawTextOutput, "FACTUALITY_FIXES:"));
        result.setStructureFixes(parseListSection(rawTextOutput, "STRUCTURE_FIXES:"));
        result.setStyleFixes(parseListSection(rawTextOutput, "STYLE_FIXES:"));
        result.setMissingInstructions(parseListSection(rawTextOutput, "MISSING_INSTRUCTIONS:"));

        result.setGeneralRationale(parseParagraphSection(rawTextOutput, "RATIONALE:"));

        return result;
    }

    // --- PARSING HELPERS --- //

    private int parseNumericField(String text, String fieldName) {
        Pattern pattern = Pattern.compile(fieldName + "\\s*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0; // Default pessimistic if parsing fails
    }

    private boolean parseBooleanField(String text, String fieldName) {
        Pattern pattern = Pattern.compile(fieldName + "\\s*(true|false|yes|no)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String val = matcher.group(1).toLowerCase();
            return val.equals("true") || val.equals("yes");
        }
        return false;
    }

    private List<String> parseListSection(String text, String header) {
        List<String> items = new ArrayList<>();
        boolean inSection = false;
        String[] lines = text.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(header)) {
                inSection = true;
                continue;
            }
            if (inSection && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                break; // Moving to next block
            }
            if (inSection && trimmed.startsWith("-")) {
                String bulletText = trimmed.substring(1).trim();
                if (!bulletText.isBlank() && !bulletText.equalsIgnoreCase("none")) {
                    items.add(bulletText);
                }
            }
        }
        return items;
    }

    private String parseParagraphSection(String text, String header) {
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        String[] lines = text.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(header)) {
                inSection = true;
                continue;
            }
            if (inSection && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                break;
            }
            if (inSection) {
                if (!trimmed.isBlank()) {
                    sb.append(trimmed).append(" ");
                }
            }
        }
        return sb.toString().trim();
    }
}
