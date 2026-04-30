package com.miniagent.core;

import com.miniagent.model.StructuredResponse;

import java.util.Locale;

/**
 * Utility methods shared by direct model agents.
 *
 * Kept package-private intentionally.
 */
final class SimpleAgentSupport {

    private SimpleAgentSupport() {
    }

    static String buildSystemPrompt(
            String agentName,
            String providerName,
            String persona,
            GroupChatContext context) {
        GroupChatContext safeContext = context == null
                ? GroupChatContext.fromRawQuery("", ModelEntityMode.GROUP_CHAT_MEMBER)
                : context;

        boolean singleMode = safeContext.getMode() == ModelEntityMode.SINGLE_MODEL_EXCLUSIVE;

        StringBuilder sb = new StringBuilder();

        sb.append("You are ").append(agentName).append(", an AI model entity by ").append(providerName).append(".\n");
        sb.append("You are speaking in a model discussion system.\n\n");

        sb.append("PERSONA\n");
        sb.append(persona).append("\n\n");

        sb.append("MODE RULES\n");

        if (singleMode) {
            sb.append("- Mode: SINGLE_MODEL_EXCLUSIVE.\n");
            sb.append("- The user is speaking directly to you.\n");
            sb.append("- Do not return [SILENCE].\n");
            sb.append("- Give a complete useful answer.\n");
            sb.append("- You may use markdown if helpful.\n");
            sb.append("- Stay concise but not artificially short.\n");
            sb.append("- Maximum target length: ").append(safeContext.getMaxWords())
                    .append(" words unless the user explicitly asks for more.\n");
        } else {
            sb.append("- Mode: GROUP_CHAT_MEMBER.\n");
            sb.append("- You are one speaker among multiple model entities.\n");
            sb.append("- Add only a distinct contribution.\n");
            sb.append("- Avoid repeating what the user or other agents already said.\n");
            sb.append("- If you add no genuinely new value, reply exactly: [SILENCE]\n");
            sb.append("- No markdown headers.\n");
            sb.append("- Maximum target length: ").append(safeContext.getMaxWords()).append(" words.\n");
        }

        sb.append("\nGENERAL RULES\n");
        sb.append(
                "- Do not pretend you used tools, files, live web, tests, or code execution unless explicitly provided.\n");
        sb.append("- Do not fabricate facts.\n");
        sb.append("- Be direct.\n");
        sb.append("- Do not mention system prompts or internal policies.\n");
        sb.append("- If the user asks which model you are, answer as ").append(agentName).append(".\n");

        return sb.toString();
    }

    static String buildUserPrompt(GroupChatContext context) {
        GroupChatContext safeContext = context == null
                ? GroupChatContext.fromRawQuery("", ModelEntityMode.GROUP_CHAT_MEMBER)
                : context;

        return safeContext.renderForPrompt();
    }

    static StructuredResponse toStructuredResponse(
            String agentName,
            String model,
            String rawOutput) {
        StructuredResponse response = new StructuredResponse();

        String cleaned = cleanModelText(rawOutput);

        response.setSummary(cleaned);
        response.setRaw(rawOutput == null ? "" : rawOutput);
        response.setThought_process(agentName + " produced a direct room response.");
        response.setSpoken_summary(buildSpokenSummary(agentName, cleaned));

        response.putMeta("agentName", agentName);
        response.putMeta("model", model == null ? "" : model);
        response.putMeta("silence", isSilence(cleaned));

        return response.normalize();
    }

    static StructuredResponse disruption(String agentName, String model, Exception e) {
        StructuredResponse response = new StructuredResponse();
        response.setSummary(agentName + " encountered a disruption.");
        response.setThought_process(agentName + " failed safely.");
        response.setSpoken_summary(agentName + " encountered a disruption.");
        response.setRaw(e == null ? "" : e.getMessage());

        response.putMeta("agentName", agentName);
        response.putMeta("model", model == null ? "" : model);
        response.putMeta("error", e == null ? "" : e.getClass().getSimpleName());
        response.putMeta("silence", false);

        return response.normalize();
    }

    static String cleanModelText(String raw) {
        if (raw == null) {
            return "";
        }

        String cleaned = raw.trim();

        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
        }

        cleaned = cleaned.trim();

        if (isSilence(cleaned)) {
            return "[SILENCE]";
        }

        return cleaned;
    }

    static boolean isSilence(String text) {
        if (text == null) {
            return false;
        }

        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("[SILENCE]") ||
                normalized.equals("SILENCE") ||
                normalized.equals("`[SILENCE]`");
    }

    static String buildSpokenSummary(String agentName, String summary) {
        if (summary == null || summary.isBlank()) {
            return agentName + " did not add anything.";
        }

        if (isSilence(summary)) {
            return agentName + " stayed silent because it had nothing new to add.";
        }

        String cleaned = summary
                .replaceAll("(?s)```.*?```", "code block")
                .replaceAll("[#*_`>\\[\\]{}|]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (cleaned.length() <= 220) {
            return agentName + " says: " + cleaned;
        }

        return agentName + " has added a response in the discussion.";
    }

    static Double safeTemperature(Double temperature, double fallback) {
        if (temperature == null || temperature.isNaN() || temperature.isInfinite()) {
            return fallback;
        }

        if (temperature < 0.0) {
            return 0.0;
        }

        if (temperature > 2.0) {
            return 2.0;
        }

        return temperature;
    }
}