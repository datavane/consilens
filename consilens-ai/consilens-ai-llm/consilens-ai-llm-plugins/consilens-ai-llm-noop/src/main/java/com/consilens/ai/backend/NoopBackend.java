package com.consilens.ai.backend;

import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;

import java.util.List;

/**
 * A no-op LLM backend that returns a fallback message without calling any external service.
 * Used when no real LLM backend is configured.
 */
public class NoopBackend implements LLMBackend {

    private static final String FALLBACK_MESSAGE =
            "No LLM backend is configured. Please set up an Ollama server or another supported backend. "
                    + "I can still run rule-based analysis on your diff results.";

    @Override
    public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
        return LLMResponse.builder()
                .text(FALLBACK_MESSAGE)
                .finishReason("stop")
                .build();
    }

    @Override
    public String complete(String prompt) {
        return FALLBACK_MESSAGE;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public BackendInfo info() {
        return BackendInfo.builder()
                .name("noop")
                .model("none")
                .version("0.0.0")
                .supportsFunctionCalling(false)
                .supportsStreaming(false)
                .build();
    }
}
