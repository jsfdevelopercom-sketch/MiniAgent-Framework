package com.miniagent.core;

import com.miniagent.model.StructuredResponse;

/**
 * Common contract for direct single-model room entities.
 */
public interface SimpleModelAgent {

    String getAgentName();

    String getDefaultModel();

    StructuredResponse respond(String userQuery, String apiKeyOverride, Double temperature);

    StructuredResponse respond(GroupChatContext context, String apiKeyOverride, Double temperature);
}