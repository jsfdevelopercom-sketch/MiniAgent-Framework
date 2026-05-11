package com.miniagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miniagent.config.AgentConfig;

/**
 * Direct hardcoded OpenAiHttpClient test.
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
public final class OpenAiDirectHardcodedTest {

        /*
         * Put your actual values here for local testing.
         * Keep temperature null for GPT-5 / o-series / high-control models.
         */
        private static final String OPENAI_API_KEY = "";

        private static final String MODEL = "gpt-5.4";
        // private static final String MODEL = "gpt-5.4";

        private static final String PROMPT = """
                        Write a complex complete index.html in html and js to create texteditor at the level of microsoft vs studio complete with all features
                        Return only the code and a one-line explanation.Return only valid JSON.
                        """;

        private OpenAiDirectHardcodedTest() {
        }

        public static void main(String[] args) {
                try {
                        if (OPENAI_API_KEY == null
                                        || OPENAI_API_KEY.isBlank()
                                        || OPENAI_API_KEY.equals("PASTE_YOUR_OPENAI_API_KEY_HERE")) {
                                throw new IllegalStateException(
                                                "Paste your real OpenAI API key inside OPENAI_API_KEY first.");
                        }

                        AgentConfig config = new AgentConfig();
                        config.setOpenaiApiKey(OPENAI_API_KEY);
                        config.setDefaultOpenaiModel(MODEL);

                        ObjectMapper mapper = new ObjectMapper();
                        mapper.registerModule(new JavaTimeModule());

                        OpenAiHttpClient client = new OpenAiHttpClient(config, mapper);

                        System.out.println("==================================================");
                        System.out.println("Testing OpenAiHttpClient directly");
                        System.out.println("Model: " + MODEL);
                        System.out.println("==================================================");

                        String systemPrompt = """
                                        You are a direct OpenAI API test.
                                        Give a useful answer.
                                        """;

                        /*
                         * Keep this null for GPT-5 / o-series models.
                         * Your OpenAiHttpClient already has supportsTemperature(...),
                         * but null keeps this test clean.
                         */
                        Double temperature = null;

                        String textReply = client.executeStructuredCall(
                                        MODEL,
                                        systemPrompt,
                                        PROMPT,
                                        temperature, null);

                        System.out.println();
                        System.out.println("TEXT CALL RESULT:");
                        System.out.println("--------------------------------------------------");
                        System.out.println(textReply);
                        System.out.println("--------------------------------------------------");

                        String structuredSystemPrompt = """
                                        You are a direct OpenAI structured JSON test.

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
                        System.out.println("SUCCESS: OpenAiHttpClient direct test completed.");

                } catch (Exception e) {
                        System.err.println();
                        System.err.println("FAILED: OpenAiHttpClient direct test failed.");
                        System.err.println("Type: " + e.getClass().getName());
                        System.err.println("Message: " + e.getMessage());
                        e.printStackTrace(System.err);
                        System.exit(1);
                }
        }
}