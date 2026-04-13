package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a single message in a chat conversation.
 */
@Data
@Builder
public class ChatMessage {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    /**
     * Represents a tool call request from the assistant.
     */
    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }

    private Role role;
    private String content;

    @Singular
    private List<ToolCall> toolCalls;

    /** Tool call ID when this message is a tool result. */
    private String toolCallId;

    /** Optional name identifier. */
    private String name;

    private Instant timestamp;

    public static ChatMessage system(String content) {
        return ChatMessage.builder()
                .role(Role.SYSTEM)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static ChatMessage user(String content) {
        return ChatMessage.builder()
                .role(Role.USER)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static ChatMessage assistant(String content) {
        return ChatMessage.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return ChatMessage.builder()
                .role(Role.ASSISTANT)
                .content(content)
                .toolCalls(toolCalls)
                .timestamp(Instant.now())
                .build();
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return ChatMessage.builder()
                .role(Role.TOOL)
                .toolCallId(toolCallId)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }
}
