package com.consilens.ai.backend;

/**
 * OpenAI chat completion backend.
 */
public class OpenAIBackend extends AbstractOpenAICompatibleBackend {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4.1-mini";

    public OpenAIBackend() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, System.getenv("OPENAI_API_KEY"));
    }

    public OpenAIBackend(String baseUrl, String model, String apiKey) {
        super(baseUrl, model, apiKey);
    }

    @Override
    protected String backendName() {
        return "openai";
    }

    @Override
    protected boolean supportsToolCalls() {
        return true;
    }

    @Override
    protected boolean supportsStreaming() {
        return true;
    }
}
