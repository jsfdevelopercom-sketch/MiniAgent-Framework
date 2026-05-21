package com.miniagent.api;

/**
 * Signals that a provider produced a real response but stopped because the
 * configured output budget was exhausted.
 *
 * This is different from network failure or an empty response. It means the
 * model started producing useful output, but max_output_tokens/max_tokens cut it
 * off before the answer was complete.
 */
public class ModelOutputIncompleteException extends RuntimeException {

    private final String model;
    private final String incompleteReason;
    private final String partialText;
    private final int requestedMaxOutputTokens;
    private final int outputTokens;
    private final int reasoningTokens;

    public ModelOutputIncompleteException(
            String model,
            String incompleteReason,
            String partialText,
            int requestedMaxOutputTokens,
            int outputTokens,
            int reasoningTokens
    ) {
        super(buildMessage(
                model,
                incompleteReason,
                requestedMaxOutputTokens,
                outputTokens,
                reasoningTokens,
                partialText
        ));

        this.model = model;
        this.incompleteReason = incompleteReason == null ? "" : incompleteReason;
        this.partialText = partialText == null ? "" : partialText;
        this.requestedMaxOutputTokens = requestedMaxOutputTokens;
        this.outputTokens = outputTokens;
        this.reasoningTokens = reasoningTokens;
    }

    public String getModel() {
        return model;
    }

    public String getIncompleteReason() {
        return incompleteReason;
    }

    public String getPartialText() {
        return partialText;
    }

    public int getRequestedMaxOutputTokens() {
        return requestedMaxOutputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getReasoningTokens() {
        return reasoningTokens;
    }

    public boolean isMaxOutputTokenExhaustion() {
        return "max_output_tokens".equalsIgnoreCase(incompleteReason)
                || "max_tokens".equalsIgnoreCase(incompleteReason);
    }

    private static String buildMessage(
            String model,
            String incompleteReason,
            int requestedMaxOutputTokens,
            int outputTokens,
            int reasoningTokens,
            String partialText
    ) {
        return "Model output incomplete. model=" + model
                + ", reason=" + incompleteReason
                + ", requestedMaxOutputTokens=" + requestedMaxOutputTokens
                + ", outputTokens=" + outputTokens
                + ", reasoningTokens=" + reasoningTokens
                + ", partialChars=" + (partialText == null ? 0 : partialText.length());
    }
}
