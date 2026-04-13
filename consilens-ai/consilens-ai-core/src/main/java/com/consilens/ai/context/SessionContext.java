package com.consilens.ai.context;

import com.consilens.ai.chat.ConversationContext;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.LLMBackend;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Holds all state for an active AI session.
 */
@Data
@Builder
public class SessionContext {

    /** The conversation history and connection info. */
    private ConversationContext conversation;

    /** The LLM backend active for this session. */
    private LLMBackend backend;

    /** The AI analyzer active for this session. */
    private AIAnalyzer analyzer;

    /** When this session was created. */
    private Instant startTime;

    /** When this session last had activity. */
    private Instant lastActivity;

    /**
     * Returns the existing conversation or creates a new one.
     */
    public ConversationContext getOrCreateConversation() {
        if (conversation == null) {
            conversation = new ConversationContext();
        }
        return conversation;
    }

    /**
     * Updates the last activity timestamp.
     */
    public void touch() {
        this.lastActivity = Instant.now();
    }
}
