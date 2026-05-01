package com.miniagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.AgentConfig;
import com.miniagent.core.ModelConstants;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ModelTester {

    public static void main(String[] args) {
        // Retrieve API key from environment for testing
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Please set the OPENAI_API_KEY environment variable.");
            return;
        }
        
        AgentConfig config = new AgentConfig();
        config.setOpenaiApiKey(apiKey);
        
        ObjectMapper mapper = new ObjectMapper();
        OpenAiHttpClient client = new OpenAiHttpClient(config, mapper);

        List<String> modelsToTest = new ArrayList<>();
        try {
            for (Field field : ModelConstants.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    String value = (String) field.get(null);
                    // Filter out non-OpenAI chat models
                    if (!value.contains("claude") && 
                        !value.contains("gemini") && 
                        !value.contains("veo") && 
                        !value.contains("lyria") && 
                        !value.contains("deep-research-pro") &&
                        !value.contains("dall-e") && 
                        !value.contains("whisper") && 
                        !value.contains("tts") && 
                        !value.contains("embedding") && 
                        !value.contains("moderation") && 
                        !value.contains("audio") && 
                        !value.contains("image") && 
                        !value.contains("sora") && 
                        !value.contains("realtime") && 
                        !value.contains("babbage") && 
                        !value.contains("davinci") && 
                        !value.contains("codex") && 
                        !value.contains("computer-use")) {
                        modelsToTest.add(value);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        System.out.println("Starting tests for " + modelsToTest.size() + " models...\n");

        for (String model : modelsToTest) {
            System.out.println("--------------------------------------------------");
            System.out.println("Testing model: " + model);
            try {
                // Testing plain text call
                String reply = client.executeTextCall(model, "You are a helpful assistant.", "hello");
                System.out.println("SUCCESS (executeTextCall) -> " + reply.replace("\n", " "));
            } catch (Exception e) {
                System.out.println("FAILED (executeTextCall) -> " + e.getMessage());
                // We'll dump the stack trace logic later if we want more details, but the root cause is usually in the message now
            }

            try {
                // Testing structured call
                String structuredReply = client.executeStructuredCall(model, "You are a helpful assistant. Output JSON.", "{\"message\": \"hello\"}");
                System.out.println("SUCCESS (executeStructuredCall) -> " + structuredReply.replace("\n", " "));
            } catch (Exception e) {
                System.out.println("FAILED (executeStructuredCall) -> " + e.getMessage());
            }
        }
        System.out.println("--------------------------------------------------");
        System.out.println("Tests completed.");
    }
}
