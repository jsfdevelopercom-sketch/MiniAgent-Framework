package com.miniagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miniagent.config.AgentConfig;

/**
 * Direct hardcoded GeminiHttpClient test.
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
public final class GeminiDirectHardcodedTest {

        /*
         * Put your actual values here for local testing.
         * Keep temperature null for default model temperature.
         */
        private static final String GEMINI_API_KEY = "";

        private static final String MODEL = "gemini-2.5-pro";

        private static final String PROMPT = """
                        Write a complex complete index.html in html and js to create texteditor at the level of microsoft vs studio complete with all features
                        Return only the code and a one-line explanation.Return only valid JSON.
                        """;

        private GeminiDirectHardcodedTest() {
        }

        public static void main(String[] args) {
                try {
                        if (GEMINI_API_KEY == null
                                        || GEMINI_API_KEY.isBlank()
                                        || GEMINI_API_KEY.equals("PASTE_YOUR_GEMINI_API_KEY_HERE")) {
                                throw new IllegalStateException(
                                                "Paste your real Gemini API key inside GEMINI_API_KEY first.");
                        }

                        AgentConfig config = new AgentConfig();
                        config.setGeminiApiKey(GEMINI_API_KEY);
                        config.setDefaultGeminiModel(MODEL);

                        ObjectMapper mapper = new ObjectMapper();
                        mapper.registerModule(new JavaTimeModule());

                        GeminiHttpClient client = new GeminiHttpClient(config, mapper);

                        System.out.println("==================================================");
                        System.out.println("Testing GeminiHttpClient directly");
                        System.out.println("Model: " + MODEL);
                        System.out.println("==================================================");

                        String systemPrompt = """
                                        You are a direct Gemini API test.
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
                                        You are a direct Gemini structured JSON test.

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
                        System.out.println("SUCCESS: GeminiHttpClient direct test completed.");

                } catch (Exception e) {
                        System.err.println();
                        System.err.println("FAILED: GeminiHttpClient direct test failed.");
                        System.err.println("Type: " + e.getClass().getName());
                        System.err.println("Message: " + e.getMessage());
                        e.printStackTrace(System.err);
                        System.exit(1);
                }
        }
}
