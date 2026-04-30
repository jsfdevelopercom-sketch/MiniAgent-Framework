package com.miniagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Context object for simple direct model entities.
 *
 * This class avoids passing a giant raw string blob into GPT/Gemini/Claude.
 * It lets each model know:
 * - current user query
 * - previous room messages
 * - who already said what
 * - whether the model is solo or group-chat mode
 */
public class GroupChatContext {

    private String userQuery;
    private ModelEntityMode mode;
    private List<GroupChatMessage> messages;
    private int maxWords;
    private boolean allowSilence;
    private boolean allowMarkdown;
    private String roomName;

    public GroupChatContext() {
        this.userQuery = "";
        this.mode = ModelEntityMode.GROUP_CHAT_MEMBER;
        this.messages = new ArrayList<>();
        this.maxWords = 80;
        this.allowSilence = true;
        this.allowMarkdown = false;
        this.roomName = "GD Room";
    }

    public static GroupChatContext groupMember(String userQuery, List<GroupChatMessage> messages) {
        GroupChatContext context = new GroupChatContext();
        context.setUserQuery(userQuery);
        context.setMode(ModelEntityMode.GROUP_CHAT_MEMBER);
        context.setMessages(messages);
        context.setMaxWords(80);
        context.setAllowSilence(true);
        context.setAllowMarkdown(false);
        return context;
    }

    public static GroupChatContext singleModel(String userQuery, List<GroupChatMessage> messages) {
        GroupChatContext context = new GroupChatContext();
        context.setUserQuery(userQuery);
        context.setMode(ModelEntityMode.SINGLE_MODEL_EXCLUSIVE);
        context.setMessages(messages);
        context.setMaxWords(5000);
        context.setAllowSilence(false);
        context.setAllowMarkdown(true);
        return context;
    }
private static boolean looksLikeCodeRequest(String text) {
    String q = text == null ? "" : text.toLowerCase();

    return q.contains("code") ||
            q.contains("html") ||
            q.contains("javascript") ||
            q.contains("java") ||
            q.contains("python") ||
            q.contains("working") ||
            q.contains("complete") ||
            q.contains("elaborate") ||
            q.contains("app") ||
            q.contains("editor");
}
    public static GroupChatContext fromRawQuery(String rawUserQuery, ModelEntityMode mode) {
        GroupChatContext context = new GroupChatContext();
        context.setUserQuery(rawUserQuery);
        context.setMode(mode == null ? ModelEntityMode.GROUP_CHAT_MEMBER : mode);

        if (context.getMode() == ModelEntityMode.SINGLE_MODEL_EXCLUSIVE) {
            context.setMaxWords(5000);
            context.setAllowSilence(false);
            context.setAllowMarkdown(true);
        } 
        if (looksLikeCodeRequest(rawUserQuery)) {
            context.setMaxWords(2500);
            context.setAllowSilence(false);
            context.setAllowMarkdown(true);
        } else {
            context.setMaxWords(80);
            context.setAllowSilence(true);
            context.setAllowMarkdown(false);
        }
        return context;
    }

    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("ROOM NAME: ").append(safe(roomName)).append("\n");
        sb.append("MODE: ").append(mode).append("\n");
        sb.append("MAX WORDS: ").append(maxWords).append("\n");
        sb.append("ALLOW SILENCE: ").append(allowSilence).append("\n");
        sb.append("ALLOW MARKDOWN: ").append(allowMarkdown).append("\n\n");

        sb.append("CURRENT USER QUERY:\n");
        sb.append(safe(userQuery)).append("\n\n");

        sb.append("DISCUSSION SO FAR:\n");
        if (messages == null || messages.isEmpty()) {
            sb.append("- No previous messages.\n");
        } else {
            int count = 0;
            int start = Math.max(0, messages.size() - 12);

            for (int i = start; i < messages.size(); i++) {
                GroupChatMessage message = messages.get(i);
                if (message == null) {
                    continue;
                }

                sb.append("- ")
                        .append(message.getSpeaker())
                        .append(": ")
                        .append(message.getContent())
                        .append("\n");

                count++;

                if (count >= 12) {
                    break;
                }
            }
        }

        return sb.toString();
    }

    public String getUserQuery() {
        return safe(userQuery);
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = safe(userQuery);
    }

    public ModelEntityMode getMode() {
        return mode == null ? ModelEntityMode.GROUP_CHAT_MEMBER : mode;
    }

    public void setMode(ModelEntityMode mode) {
        this.mode = mode == null ? ModelEntityMode.GROUP_CHAT_MEMBER : mode;
    }

    public List<GroupChatMessage> getMessages() {
        if (messages == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(messages);
    }

    public void setMessages(List<GroupChatMessage> messages) {
        if (messages == null) {
            this.messages = new ArrayList<>();
        } else {
            this.messages = new ArrayList<>(messages);
        }
    }

    public int getMaxWords() {
        return maxWords;
    }

    public void setMaxWords(int maxWords) {
        this.maxWords = Math.max(20, Math.min(1200, maxWords));
    }

    public boolean isAllowSilence() {
        return allowSilence;
    }

    public void setAllowSilence(boolean allowSilence) {
        this.allowSilence = allowSilence;
    }

    public boolean isAllowMarkdown() {
        return allowMarkdown;
    }

    public void setAllowMarkdown(boolean allowMarkdown) {
        this.allowMarkdown = allowMarkdown;
    }

    public String getRoomName() {
        return safe(roomName);
    }

    public void setRoomName(String roomName) {
        this.roomName = safe(roomName);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static class GroupChatMessage {

        private String speaker;
        private String content;
        private long timestampMillis;

        public GroupChatMessage() {
            this.speaker = "";
            this.content = "";
            this.timestampMillis = System.currentTimeMillis();
        }

        public GroupChatMessage(String speaker, String content) {
            this.speaker = safe(speaker);
            this.content = safe(content);
            this.timestampMillis = System.currentTimeMillis();
        }

        public GroupChatMessage(String speaker, String content, long timestampMillis) {
            this.speaker = safe(speaker);
            this.content = safe(content);
            this.timestampMillis = timestampMillis <= 0 ? System.currentTimeMillis() : timestampMillis;
        }

        public static GroupChatMessage of(String speaker, String content) {
            return new GroupChatMessage(speaker, content);
        }

        public String getSpeaker() {
            return safe(speaker);
        }

        public void setSpeaker(String speaker) {
            this.speaker = safe(speaker);
        }

        public String getContent() {
            return safe(content);
        }

        public void setContent(String content) {
            this.content = safe(content);
        }

        public long getTimestampMillis() {
            return timestampMillis;
        }

        public void setTimestampMillis(long timestampMillis) {
            this.timestampMillis = timestampMillis <= 0 ? System.currentTimeMillis() : timestampMillis;
        }
    }
}
