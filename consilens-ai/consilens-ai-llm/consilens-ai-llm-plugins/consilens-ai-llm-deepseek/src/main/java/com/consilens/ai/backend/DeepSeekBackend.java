package com.consilens.ai.backend;

/**
 * DeepSeek chat completion backend.
 */
public class DeepSeekBackend extends AbstractOpenAICompatibleBackend {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    public DeepSeekBackend() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, System.getenv("DEEPSEEK_API_KEY"));
    }

    public DeepSeekBackend(String baseUrl, String model, String apiKey) {
        super(baseUrl, model, apiKey);
    }

    @Override
    protected String backendName() {
        return "deepseek";
    }

    @Override
    protected String chatPath() {
        return "/chat/completions";
    }

    @Override
    protected String completionPath() {
        return "/chat/completions";
    }

    @Override
    protected String healthPath() {
        return "/models";
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
