package com.miniagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miniagent.config.AgentConfig;

/**
 * Direct hardcoded ClaudeHttpClient test.
 *
 * This bypasses:
 * - Agent
 * - ModelRouter
 * - MiniAgentWorker
 * - Evaluator
 * - Synthesizer
 *
 * Do not commit your real API key to GitHub.
 */
public final class ClaudeDirectHardcodedTest {

        /*
         * Put your actual values here for local testing.
         * Keep temperature null for default model temperature.
         */
        private static final String CLAUDE_API_KEY = "";

        private static final String MODEL = "claude-3-5-sonnet-20241022";

        private static final String PROMPT = """
                        Write a complex complete index.html in html and js to create texteditor at the level of microsoft vs studio complete with all features
                        Return only the code and a one-line explanation.Return only valid JSON.
                        """;

        private ClaudeDirectHardcodedTest() {
        }

        public static void main(String[] args) {
                try {
                        if (CLAUDE_API_KEY == null
                                        || CLAUDE_API_KEY.isBlank()
                                        || CLAUDE_API_KEY.equals("PASTE_YOUR_CLAUDE_API_KEY_HERE")) {
                                throw new IllegalStateException(
                                                "Paste your real Claude API key inside CLAUDE_API_KEY first.");
                        }

                        AgentConfig config = new AgentConfig();
                        config.setClaudeApiKey(CLAUDE_API_KEY);
                        config.setDefaultClaudeModel(MODEL);

                        ObjectMapper mapper = new ObjectMapper();
                        mapper.registerModule(new JavaTimeModule());

                        ClaudeHttpClient client = new ClaudeHttpClient(config);

                        System.out.println("==================================================");
                        System.out.println("Testing ClaudeHttpClient directly");
                        System.out.println("Model: " + MODEL);
                        System.out.println("==================================================");

                        String systemPrompt = """
                                        You are a direct Claude API test.
                                        Give a useful answer.
                                        """;

                        Double temperature = null;

                        String textReply = client.executeTextCall(
                                        MODEL,
                                        systemPrompt,
                                        PROMPT,
                                        temperature);

                        System.out.println();
                        System.out.println("TEXT CALL RESULT:");
                        System.out.println("--------------------------------------------------");
                        System.out.println(textReply);
                        System.out.println("--------------------------------------------------");

                        String structuredSystemPrompt = """
                                        You are a direct Claude structured JSON test.

                                        Return only valid JSON in this exact shape:
                                        {
                                          "thought_process": "short public diagnostic note",
                                          "summary": "main answer",
                                          "convo": "short closing line"
                                        }
                                        """;

                        String structuredReply = client.executeStructuredCall(
                                        MODEL,
                                        structuredSystemPrompt,
                                        PROMPT,
                                        temperature,
                                        null);

                        System.out.println();
                        System.out.println("STRUCTURED CALL RESULT:");
                        System.out.println("--------------------------------------------------");
                        System.out.println(structuredReply);
                        System.out.println("--------------------------------------------------");

                        System.out.println();
                        System.out.println("SUCCESS: ClaudeHttpClient direct test completed.");

                } catch (Exception e) {
                        System.err.println();
                        System.err.println("FAILED: ClaudeHttpClient direct test failed.");
                        System.err.println("Type: " + e.getClass().getName());
                        System.err.println("Message: " + e.getMessage());
                        e.printStackTrace(System.err);
                        System.exit(1);
                }
        }
}
