package com.consilens.ai.spi;

import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;

import java.util.List;

/**
 * SPI interface for LLM backend implementations.
 */
public interface LLMBackend {

    /**
     * Sends a chat request to the LLM and returns its response.
     *
     * @param systemPrompt the system prompt to set the assistant's behavior
     * @param messages     the conversation history
     * @param functions    available tool/function definitions (may be empty)
     * @return the LLM response
     */
    LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions);

    /**
     * Performs a simple text completion.
     *
     * @param prompt the input prompt
     * @return the completed text
     */
    String complete(String prompt);

    /**
     * Returns {@code true} if this backend is reachable and ready.
     */
    boolean isAvailable();

    /**
     * Returns metadata about this backend.
     */
    BackendInfo info();
}
