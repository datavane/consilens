package com.consilens.ai.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

/**
 * Response from a LLM backend.
 */
@Data
@Builder
public class LLMResponse {

    /**
     * Token usage statistics.
     */
    @Data
    @Builder
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
    }

    /** Text content of the response. */
    private String text;

    /** Tool calls requested by the model. */
    @Singular
    private List<ChatMessage.ToolCall> toolCalls;

    /** Reason the model stopped generating. */
    private String finishReason;

    /** Token usage information. */
    private Usage usage;

    public boolean hasTextContent() {
        return text != null && !text.isEmpty();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
