# MiniAgent Framework: Deployment Notes & Recent Changes

This document outlines the most recent structural and configuration updates made to the MiniAgent Framework to ensure fault tolerance, accurate billing, deep observability, and seamless deployment on platforms like Railway.

## 1. Recent Codebase Updates

*   **Model Upgrades to GPT-5 Series**: 
    *   The framework's primary models have been upgraded. The reliable "thinker" model is now `gpt-5.4-2026-03-05`.
    *   The fast/cheap routing model is now `gpt-5-nano-2025-08-07`.
*   **TokenCostManager Overhaul**:
    *   Replaced arbitrary token heuristics with exact provider pricing tiers. 
    *   Implemented `UsageSnapshot` to accurately track input tokens, output tokens, call counts, and INR costs per run.
*   **Restored Fallback & Recovery Architecture**:
    *   Rebuilt `ModelFallbackPolicy` and `ThoughtRecoveryDecision` to act as deterministic safety nets. If an API request times out or returns malformed JSON, the agent seamlessly degrades to a cheaper fallback model or initiates a replan.
*   **Deep-Insight Observability (Trace Logging)**:
    *   Fixed missing trace enum declarations (`TOOL_LOOP_STARTED`, `TOOL_CALL_FINISHED`, etc.).
    *   Added profound Javadoc insights across orchestration nodes (`SafeThoughtExecutor`, `MiniAgentTools`, `Agent`), explicitly documenting *why* fault-tolerant loops and sanity checkers are designed the way they are to assist future debugging.
*   **Zero-Error Compilation**:
    *   Resolved all outstanding compilation mismatches between the `AgentRunState`, `EvaluationResult`, and Tool interfaces.

## 2. Required Environment Variables

To run the MiniAgent Framework securely on GitHub Actions, Railway, or any other hosting environment, you must provide the following Environment Variables. 

**DO NOT hardcode these into the source code.**

### API Keys
*   `OPENAI_API_KEY`: Required for the primary GPT-5 and fallback models.
*   `GEMINI_API_KEY`: Required if routing to Google's models.
*   `CLAUDE_API_KEY`: Required if routing to Anthropic's Haiku/Sonnet models.

### CodeWeaver Integration
CodeWeaver provides the file-system and terminal sandbox for the Agent.
*   `CODEWEAVER_BASE_URL`: The HTTP URL of your CodeWeaver service (e.g., `http://codeweaver.railway.internal:8080`).
*   `CODEWEAVER_TOKEN`: The shared secret token authorizing MiniAgent to execute sandbox tools.

### Agent Configuration & Safety Limits
*   `MINIAGENT_TOOL_MAX_STEPS`: The maximum number of consecutive tool uses allowed before forcing a stop (Default: 6).
*   `MINIAGENT_TOOLS_ALLOW_WRITES`: Set to `true` to allow the agent to edit files. (Recommended initial: `false` until read-only tests pass).
*   `MINIAGENT_TOOLS_ALLOW_RAW_CODEMOD`: Set to `true` to allow raw `/apply` codemod capabilities.
*   `MINIAGENT_TOOLS_ALLOW_COMMANDS`: Set to `true` to allow arbitrary terminal command execution. **Use with extreme caution.**
*   `MINIAGENT_TRACE_DIR`: Path to output JSONL trace logs (e.g., `/app/miniagent-traces`).
*   `MINIAGENT_HTTP_CONNECT_TIMEOUT_SECONDS`: Connection timeout for HTTP requests (Default: 10).
*   `MINIAGENT_HTTP_TIMEOUT_SECONDS`: Total timeout duration for an HTTP request (Default: 120).

---

*Note: Ensure these variables are populated in your Railway Project Settings -> Variables panel for both the `agent-api` and `codeweaver` services.*
