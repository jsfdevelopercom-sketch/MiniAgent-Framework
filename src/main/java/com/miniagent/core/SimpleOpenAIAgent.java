package com.miniagent.core;

import com.miniagent.api.OpenAiHttpClient;
import com.miniagent.model.StructuredResponse;
import com.miniagent.prompt.PromptFactory;
import com.miniagent.trace.AgentTraceLogger;
import com.miniagent.trace.NoOpAgentTraceLogger;

import java.util.Map;
import java.util.UUID;

/**
 * Direct OpenAI model entity for single-model and group-chat experiences.
 *
 * This bypasses recursive MiniAgent loops intentionally.
 */
public class SimpleOpenAIAgent implements SimpleModelAgent {

    private static final String AGENT_NAME = "GPT";
    private static final String PROVIDER_NAME = "OpenAI";

    private final OpenAiHttpClient client;
    private final PromptFactory promptFactory;
    private final TokenCostManager costManager;
    private final AgentTraceLogger traceLogger;

    private final String defaultModel;

    public SimpleOpenAIAgent(OpenAiHttpClient client, PromptFactory promptFactory) {
        this(client, promptFactory, null, null, null);
    }

    public SimpleOpenAIAgent(
            OpenAiHttpClient client,
            PromptFactory promptFactory,
            TokenCostManager costManager,
            AgentTraceLogger traceLogger,
            String defaultModel) {
        if (client == null) {
            throw new IllegalArgumentException("OpenAiHttpClient cannot be null.");
        }

        this.client = client;
        this.promptFactory = promptFactory;
        this.costManager = costManager;
        this.traceLogger = traceLogger == null ? new NoOpAgentTraceLogger() : traceLogger;
        this.defaultModel = defaultModel == null || defaultModel.isBlank()
                ? client.getConfig().getDefaultOpenaiModel()
                : defaultModel.trim();
    }

    @Override
    public String getAgentName() {
        return AGENT_NAME;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    @Override
    public StructuredResponse respond(String userQuery, String apiKeyOverride, Double temperature) {
        GroupChatContext context = GroupChatContext.fromRawQuery(
                userQuery,
                ModelEntityMode.GROUP_CHAT_MEMBER);

        return respond(context, apiKeyOverride, temperature);
    }

    public StructuredResponse respondSingle(String userQuery, String apiKeyOverride, Double temperature) {
        GroupChatContext context = GroupChatContext.fromRawQuery(
                userQuery,
                ModelEntityMode.SINGLE_MODEL_EXCLUSIVE);

        return respond(context, apiKeyOverride, temperature);
    }

    public StructuredResponse respondGroup(String userQuery, String apiKeyOverride, Double temperature) {
        GroupChatContext context = GroupChatContext.fromRawQuery(
                userQuery,
                ModelEntityMode.GROUP_CHAT_MEMBER);

        return respond(context, apiKeyOverride, temperature);
    }

    @Override
    public StructuredResponse respond(GroupChatContext context, String apiKeyOverride, Double temperature) {
        String runId = "simple-openai-" + UUID.randomUUID();
        String model = getDefaultModel();

        GroupChatContext safeContext = context == null
                ? GroupChatContext.fromRawQuery("", ModelEntityMode.GROUP_CHAT_MEMBER)
                : context;

        String sysPrompt = SimpleAgentSupport.buildSystemPrompt(
                AGENT_NAME,
                PROVIDER_NAME,
                "Highly analytical, logical, data-driven, precise, and concise. Prefer factual structure over speculation.",
                safeContext);

        String userPrompt = SimpleAgentSupport.buildUserPrompt(safeContext);

        try {
            long startedAt = System.currentTimeMillis();

            String rawOutput = client.executeTextCall(
                    model,
                    sysPrompt,
                    userPrompt,
                    SimpleAgentSupport.safeTemperature(temperature, 0.2));

            StructuredResponse response = SimpleAgentSupport.toStructuredResponse(
                    AGENT_NAME,
                    model,
                    rawOutput);

            recordCost("simple-openai", model, userPrompt, response.getSummary());

            traceLogger.modelEvent(
                    runId,
                    "anonymous",
                    com.miniagent.trace.AgentTraceEventType.RUN_FINISHED,
                    "simple-openai",
                    model,
                    AGENT_NAME + " direct response completed.",
                    System.currentTimeMillis() - startedAt,
                    TokenCostManager.estimateTokens(userPrompt),
                    TokenCostManager.estimateTokens(response.getSummary()),
                    Map.of(
                            "agentName", AGENT_NAME,
                            "mode", safeContext.getMode().name(),
                            "silence", SimpleAgentSupport.isSilence(response.getSummary())));

            return response;
        } catch (Exception e) {
            traceLogger.error(
                    runId,
                    "anonymous",
                    "simple-openai",
                    AGENT_NAME + " direct response failed.",
                    e);

            return SimpleAgentSupport.disruption(AGENT_NAME, model, e);
        }
    }

    private void recordCost(String stage, String model, String input, String output) {
        if (costManager == null) {
            return;
        }

        try {
            costManager.addModelUsage(
                    "anonymous",
                    "simple-openai",
                    stage,
                    model,
                    TokenCostManager.estimateTokens(input),
                    TokenCostManager.estimateTokens(output),
                    true);
        } catch (Exception ignored) {
            // Direct room agents must not fail because cost tracking failed.
        }
    }
}